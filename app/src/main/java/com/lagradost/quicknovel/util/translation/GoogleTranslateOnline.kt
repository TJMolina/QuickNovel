package com.lagradost.quicknovel.util.translation

import android.net.Uri
import com.lagradost.nicehttp.Requests
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.util.translation.models.FailedContext
import com.lagradost.quicknovel.util.translation.models.GoogleTranslationResponse
import com.lagradost.quicknovel.util.translation.models.OnlineTranslator
import com.lagradost.quicknovel.util.translation.models.TranslationResult
import kotlinx.coroutines.delay
import java.net.UnknownHostException
import kotlin.math.pow

class GoogleTranslateOnline(
    private val client: Requests
) : OnlineTranslator {
    data class FragmentMeta(val shell: String, val content: String, val originalIndex: Int, val tags: List<String>)

    companion object {
        private const val BASEURL = "https://translate.googleapis.com/translate_a/single?client=gtx&sl="
        private const val PARAGRAPH_DELIMITER = "\n\n\n\nFDHJEJHGYRSTJFDGLKDFGJREWY\n\n\n\n"
        private val paragraphsSeparatorRegex = Regex("\\n?FDHJEJHGYRSTJFDGLKDFGJREWY\\n?")
        private const val MAX_CHARS_PER_CHUNK: Int = 2500
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
        depth: Int = 0,
        isHtml: Boolean = false
    ): List<String> {
        // If there are no failed chunks to fix, or we reached the maximum retry limit (3)
        // we stop recursion and return the lines as they are
        if (translationResult.failedChunks.isEmpty()  || depth >= 3) return translationResult.translatedLines

        // Create a simple list of strings containing only the text of the paragraphs that failed
        val textsToFix = translationResult.failedChunks.map { it.text }
        // Call the main translate function again, but ONLY for the failed texts
        // This returns a new TranslationResult which might still contain some failures
        val retryResult = translate(textsToFix, from, to, isHtml, { _, _ -> })
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
            return fixFailures(
                TranslationResult(finalLines, deeperFailedContexts),
                from,
                to,
                depth + 1,
                isHtml = isHtml
            )
        }
        return finalLines
    }

    override suspend fun translate(
        textList: List<String>,
        from: String,
        to: String,
        isHtml: Boolean,
        progress: suspend (Int, Int) -> Unit
    ): TranslationResult {
        if (textList.isEmpty()) return TranslationResult(emptyList(), emptyList())

        val allTranslatedLines = Array(textList.size) { "" }
        val failedParagraphs = mutableListOf<FailedContext>()

        // Separate and extract shell (tags) from content
        val contentFragments = mutableListOf<FragmentMeta>()

        textList.forEachIndexed { index, text ->
            if (!TranslationsUtils.isTranslatable(text, isHtml)) {
                allTranslatedLines[index] = text
            } else {
                if (isHtml) {
                    val (shell, content, tags) = TranslationsUtils.extractDeepShell(text)
                    val sanitizedContent = TranslationsUtils.sanitize(content)
                    contentFragments.add(FragmentMeta(shell, sanitizedContent, index, tags))
                } else {
                    val sanitizedText = TranslationsUtils.sanitize(text)
                    contentFragments.add(FragmentMeta("%s", sanitizedText, index, emptyList()))
                }
            }
        }

        if (contentFragments.isNotEmpty()) {
            val chunks = contentFragments.chunkByLimit()
            chunks.forEachIndexed { i, chunk ->
                progress.invoke(i, chunks.size)

                val combinedText = chunk.joinToString(PARAGRAPH_DELIMITER) { it.content }
                val translatedBatch = translateChunk(combinedText, from, to)

                val splitParts = translatedBatch.split(paragraphsSeparatorRegex)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                val expectedSize = chunk.size
                if (splitParts.size == expectedSize) {
                    chunk.forEachIndexed { localIndex, meta ->
                        var translatedText = splitParts[localIndex]
                        
                        if (isHtml) {
                            translatedText = translatedText.replace(Regex("\\n+"), " ")
                            
                            // Escape < and > to ensure literal terms are displayed as text
                            translatedText = translatedText.replace("<", "&lt;").replace(">", "&gt;")

                            // Restore tags sequentially
                            val tagPattern = TranslationsUtils.TAG_DELIMITER.trim()
                            for (tag in meta.tags) {
                                translatedText = translatedText.replaceFirst(Regex("\\s?" + Regex.escape(tagPattern) + "\\s?"), tag)
                            }
                            translatedText = translatedText.trim()
                        }

                        val finalResult = try { meta.shell.replaceFirst("%s", translatedText) } catch (e: Exception) { translatedText }
                        val originalGlobalIndex = meta.originalIndex
                        allTranslatedLines[originalGlobalIndex] = finalResult

                        if (translatedText == meta.content && meta.content.any { it.isLetter() } && meta.content.split(" ").size >= 3) {
                            failedParagraphs.add(FailedContext(originalGlobalIndex, meta.content))
                        }
                    }
                } else {
                    // Mismatch: Mark all as failed to trigger fixFailures (individual translation)
                    chunk.forEach { meta ->
                        val originalGlobalIndex = meta.originalIndex
                        failedParagraphs.add(FailedContext(originalGlobalIndex, meta.content))
                        allTranslatedLines[originalGlobalIndex] = try { meta.shell.replaceFirst("%s", meta.content) } catch (e: Exception) { meta.content }
                    }
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
                val sentences = response.sentences
                if (sentences.isEmpty()) return text
                
                return sentences.joinToString("") { it.trans }
            } catch (t: Throwable) {
                logError(t)
                if (t is UnknownHostException) throw t
                retryNumber++
                if (retryNumber >= maxRetry) throw t
                delay(1000L * (2.0.pow(retryNumber).toLong()))
            }
        }
        return text
    }

    private fun List<FragmentMeta>.chunkByLimit(): List<List<FragmentMeta>> {
        if (this.isEmpty()) return emptyList()
        val chunks = mutableListOf<List<FragmentMeta>>()
        var currentChunk = mutableListOf<FragmentMeta>()
        var currentLength = 0

        for (item in this) {
            val itemLength = Uri.encode(item.content + PARAGRAPH_DELIMITER).length
            if (currentChunk.isNotEmpty() && currentLength + itemLength > MAX_CHARS_PER_CHUNK) {
                chunks.add(currentChunk)
                currentChunk = mutableListOf()
                currentLength = 0
            }
            currentChunk.add(item)
            currentLength += itemLength
        }
        if (currentChunk.isNotEmpty()) chunks.add(currentChunk)
        return chunks
    }
}