package com.lagradost.quicknovel.util.translation

import android.net.Uri
import com.lagradost.nicehttp.Requests
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.util.translation.models.FailedContext
import com.lagradost.quicknovel.util.translation.models.GoogleTranslationResponse
import com.lagradost.quicknovel.util.translation.models.TranslationResult
import kotlinx.coroutines.delay
import java.net.UnknownHostException
import kotlin.math.pow

class GoogleTranslateOnline(
    private val client: Requests,
    private val charsLimit: Int = 2000
) {

    companion object {
        private const val BASEURL = "https://translate.googleapis.com/translate_a/single?client=gtx&sl="
        private const val PARAGRAPH_DELIMITER = "\nXQZX\n"
        private val paragraphsSeparatorRegex = Regex("(?i)\\n?XQZX\\n?")
    }

    /**
     * Recursively attempts to fix paragraphs that failed to translate (where trans == orig).
     *
     * @param translationResult The result from the previous translation attempt.
     * @param depth Current recursion level to prevent infinite loops.
     * @return A list of strings where failed paragraphs have been replaced by successful retries.
     */
    suspend fun fixFailures(
        translationResult: TranslationResult,
        from: String,
        to: String,
        depth: Int = 0
    ): List<String> {
        // If there are no failed chunks to fix, or we reached the maximum retry limit (3)
        // we stop recursion and return the lines as they are
        if (translationResult.failedChunks.isEmpty()  || depth >= 3) return translationResult.translatedLines

        // Create a simple list of strings containing only the text of the paragraphs that failed
        val textsToFix = translationResult.failedChunks.map { it.text }

        // Call the main translate function again, but ONLY for the failed texts
        // This returns a new TranslationResult which might still contain some failures
        val retryResult = translate(textsToFix, from, to)

        // Create a mutable copy of the current translated lines to update them
        val finalLines = translationResult.translatedLines.toMutableList()

        retryResult.translatedLines.forEachIndexed { index, fixedText ->
            // We use 'index' to find the corresponding metadata from the previous attempt
            // 'index' in retryResult refers to the position in 'textsToFix'
            val originalMeta = translationResult.failedChunks.getOrNull(index)
            if (originalMeta != null) {
                // Use the 'originalIndex' (the global position in the book)
                // to place the newly translated text in the correct spot
                finalLines[originalMeta.originalIndex] = fixedText
            }
        }


        // If the retry attempt also produced failures, we need to handle them.
        if (retryResult.failedChunks.isNotEmpty()) {
            // Map the new failures back to their global indices
            // retryFailed.originalIndex points to the index in 'textsToFix'
            // We need to look up what that index was in the original book
            val deeperFailedContexts = retryResult.failedChunks.mapNotNull { retryFailed ->
                translationResult.failedChunks.getOrNull(retryFailed.originalIndex)
            }

            // Recursive call: try to fix the remaining failures, incrementing depth
            val recursiveResult = fixFailures(
                TranslationResult(finalLines, deeperFailedContexts),
                from,
                to,
                depth + 1
            )
            return recursiveResult
        }
        return finalLines
    }
    suspend fun translate(
        paragraphs: List<String>,
        from: String,
        to: String,
        loading: suspend (Int, Int) -> Unit = { _, _ -> }
    ): TranslationResult {
        if (paragraphs.isEmpty()) return TranslationResult(emptyList(), emptyList())

        val allTranslatedLines = Array(paragraphs.size) { "" }
        val failedParagraphs = mutableListOf<FailedContext>()

        //Separate actual text from blank/whitespace paragraphs
        val contentParagraphs = mutableListOf<String>()
        val contentIndices = mutableListOf<Int>()

        paragraphs.forEachIndexed { index, text ->
            if (text.isBlank() || !text.any {it.isLetter()}) {
                // If the paragraph is blank (tabs, newlines, spaces),
                // preserve it directly in the final result without calling the API
                allTranslatedLines[index] = text
            } else {
                // If it has text, track its content and its original position
                contentParagraphs.add(text)
                contentIndices.add(index)
            }
        }

        // Process only paragraphs that contain translatable text
        if (contentParagraphs.isNotEmpty()) {
            // Group text into chunks to stay within character limits per API call
            val chunks = contentParagraphs.chunkByLimit()
            var contentPointer = 0 // Tracks our progress through 'contentIndices'
            chunks.forEachIndexed { i, chunk ->
                loading.invoke(i, chunks.size)

                val originalParagraphsInChunk = chunk.split(paragraphsSeparatorRegex)
                    .filter { it.isNotBlank() }

                val translatedChunk = translateChunk(chunk, from, to)

                val splitParts = translatedChunk.split(paragraphsSeparatorRegex)
                    .filter { it.isNotBlank() }

                // Map translated parts back to their global positions in the book
                originalParagraphsInChunk.forEachIndexed { localIndex, originalText ->
                    val translatedText = splitParts.getOrNull(localIndex) ?: originalText
                    val originalGlobalIndex = contentIndices[contentPointer]

                    // Mark as failed if the text didn't actually change
                    // (and it's long enough to be a sentence)
                    val failed = translatedText == originalText &&
                            originalText.any { it.isLetter() } &&
                            originalText.split(" ").size >= 3

                    if (failed) {
                        failedParagraphs.add(FailedContext(originalGlobalIndex, originalText))
                    }

                    allTranslatedLines[originalGlobalIndex] = translatedText
                    contentPointer++
                }
            }
        }

        return TranslationResult(allTranslatedLines.toList(), failedParagraphs)
    }

    private suspend fun callGoogleTranslateApi(text: String, from: String, to: String) =
        client.get("$BASEURL$from&tl=$to&dt=t&q=${Uri.encode(text)}")
            .parsed<GoogleTranslationResponse>()

    private suspend fun translateChunk(
        text: String,
        from: String,
        to: String
    ): String {
        var retryNumber = 0
        val maxRetry = 3

        while (retryNumber < maxRetry) {
            try {
                val response = callGoogleTranslateApi(text, from, to)
                return response.sentences.joinToString("") { it.trans }
            } catch (t: Throwable) {
                logError(t)
                if (t is UnknownHostException) throw t
                retryNumber++
                if (retryNumber >= maxRetry) throw t
                delay(500L * (2.0.pow(retryNumber).toLong()))
            }
        }
        return text
    }


    private fun List<String>.chunkByLimit(): List<String> {
        if (this.isEmpty()) return emptyList()
        val combinedChunks = mutableListOf<String>()
        var currentChunk = StringBuilder()

        for (t in this) {
            val text =  t + PARAGRAPH_DELIMITER

            if (text.length > charsLimit) {
                if (currentChunk.isNotEmpty()) {
                    combinedChunks.add(currentChunk.toString().removeSuffix(PARAGRAPH_DELIMITER))
                    currentChunk = StringBuilder()
                }
                combinedChunks.add(text.removeSuffix(PARAGRAPH_DELIMITER))
                continue
            }

            if (currentChunk.length + text.length > charsLimit) {
                combinedChunks.add(currentChunk.toString().removeSuffix(PARAGRAPH_DELIMITER))
                currentChunk = StringBuilder()
            }
            currentChunk.append(text)
        }

        if (currentChunk.isNotEmpty()) {
            combinedChunks.add(currentChunk.toString().removeSuffix(PARAGRAPH_DELIMITER))
        }
        return combinedChunks
    }
}