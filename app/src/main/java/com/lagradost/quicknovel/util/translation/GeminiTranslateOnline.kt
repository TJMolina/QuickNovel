package com.lagradost.quicknovel.util.translation
import com.lagradost.nicehttp.Requests
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.util.translation.models.FailedContext
import com.lagradost.quicknovel.util.translation.models.GeminiTranslationResponse
import com.lagradost.quicknovel.util.translation.models.TranslationResult
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import java.net.UnknownHostException


class GeminiTranslationException(message: String) : Exception(message)

/**
 * Get an API key at: https://aistudio.google.com/
 */
class GeminiTranslateOnline(
    private val apiKey: String,
    private val client: Requests,
) {
    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
        private const val MAX_CHARS_PER_CHUNK = 10000
        private const val MAX_RETRIES = 3
        private const val SAFETY_ERROR = "SAFETY_BLOCKED"
        private val MODELS = listOf("gemini-3.1-flash-lite")
    }

    suspend fun translate(
        originalText: List<String>,
        sourceLanguage: String = "Auto-detect",
        targetLanguage: String = "English",
        isHtml: Boolean = false,
        progress: suspend (Int, Int) -> Unit = { _, _ -> }
    ): TranslationResult {
        if (originalText.isEmpty()) return TranslationResult(emptyList(), emptyList())

        val allTranslatedLines = Array(originalText.size) { "" }
        val failedContexts = mutableListOf<FailedContext>()

        val contentParagraphs = mutableListOf<String>()
        val contentIndices = mutableListOf<Int>()

        originalText.forEachIndexed { index, text ->
            val sanitizedText = TranslationsUtils.sanitize(text)
            val isTranslatable = if (isHtml) {
                val plainText = Jsoup.parse(sanitizedText).text()
                plainText.isNotBlank() && plainText.any { it.isLetter() }
            } else {
                sanitizedText.isNotBlank() && sanitizedText.any { it.isLetter() }
            }

            if (!isTranslatable) {
                allTranslatedLines[index] = sanitizedText
            } else {
                contentParagraphs.add(sanitizedText)
                contentIndices.add(index)
            }
        }

        if (contentParagraphs.isEmpty()) {
            return TranslationResult(allTranslatedLines.toList(), emptyList())
        }

        val chunks = chunkByLimit(contentParagraphs, MAX_CHARS_PER_CHUNK)
        var contentPointer = 0

        chunks.forEachIndexed { index, chunkLines ->
            progress(index, chunks.size)

            val result = recursiveTranslate(chunkLines, sourceLanguage, targetLanguage, isHtml)

            result.forEach { (isSuccess, translatedText) ->
                if (contentPointer < contentIndices.size) {
                    val originalGlobalIndex = contentIndices[contentPointer]

                    if (isSuccess) {
                        allTranslatedLines[originalGlobalIndex] = translatedText
                    } else {
                        failedContexts.add(FailedContext(originalGlobalIndex, translatedText))
                        allTranslatedLines[originalGlobalIndex] = "[TRANSLATION_FAILED]"
                    }
                    contentPointer++
                }
            }
        }

        return TranslationResult(allTranslatedLines.toList(), failedContexts)
    }
    /**
     * Recursively handles translation. If a block of text is flagged by safety filters,
     * it splits the block in half and tries again for each part.
     */
    private suspend fun recursiveTranslate(
        lines: List<String>,
        source: String,
        target: String,
        isHtml: Boolean
    ): List<Pair<Boolean, String>> {
        return try {
            // Try to translate the entire block at once
            val translated = callGeminiWithRetry(lines.joinToString("\n"), source, target, isHtml)
            val translatedLines = translated.lines().filter { it.isNotBlank() }

            // If successful, split the response back into individual lines
            // We trim and filter blank lines to avoid empty paragraphs
            // Each line is paired with 'true' to indicate success
            if (translatedLines.size != lines.size) {
                lines.map { false to it }
            } else {
                translatedLines.map { true to it }
            }

        } catch (e: Exception) {
            // If Gemini says "SAFETY_BLOCKED" and we have more than one line, we split.
            when (e) {
                is GeminiTranslationException if e.message == SAFETY_ERROR && lines.size > 1 -> {
                    val mid = lines.size / 2
                    val leftHalf = lines.subList(0, mid)
                    val rightHalf = lines.subList(mid, lines.size)

                    // It processes the left part, then the right part, and joins them (+).
                    // This structure physically forces the original order to be maintained.
                    recursiveTranslate(leftHalf, source, target, isHtml) +
                            recursiveTranslate(rightHalf, source, target, isHtml)
                }
                //CONNECTION ERROR
                is UnknownHostException -> throw e
                else -> {
                    // If we are down to 1 line, and it still fails, or it's a non-safety error,
                    // we mark these lines as 'false' (failed) so the secondary translator can take over.
                    lines.map { false to it }
                }
            }
        }
    }

    /**
     * Handles the network request, model fallback, and retries (429 errors).
     */
    private suspend fun callGeminiWithRetry(text: String, source: String, target: String, isHtml: Boolean): String {
        var lastError: Exception? = null

        for (model in MODELS) {
            repeat(MAX_RETRIES) { attempt ->
                try {
                    val res = client.post(
                        url = "$BASE_URL$model:generateContent",
                        json = buildRequestBody(text, source, target, isHtml = isHtml),
                        headers = mapOf("X-Goog-API-Key" to apiKey)
                    ).parsed<GeminiTranslationResponse>()
                    if(res.error != null){
                        //rate limit error
                        if (res.error.code == 429) {
                            val retryDelayStr = res.error.details?.find { it.retryDelay != null }?.retryDelay
                            val waitTime = retryDelayStr?.removeSuffix("s")?.toDoubleOrNull()?.let { (it * 1000).toLong() }
                                ?: (15000L * (attempt + 1))
                            delay(waitTime)
                            throw GeminiTranslationException("Rate limit hit")
                        } else {
                            throw GeminiTranslationException("API Error: ${res.error.message}")
                        }
                    }else{
                        // Check for safety blocks in prompt or candidates
                        val isBlocked = res.promptFeedback?.blockReason?.isNotBlank() == true ||
                                res.candidates?.firstOrNull()?.finishReason in listOf("SAFETY", "PROHIBITED_CONTENT")

                        if (isBlocked) throw GeminiTranslationException(SAFETY_ERROR)
                        return res.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text?.trim()
                            ?: throw GeminiTranslationException("Empty response")
                    }

                } catch (e: Exception) {
                    logError(e)
                    lastError = e

                    //Wi-Fi error, don't retry
                    if (e is UnknownHostException) throw e
                    // Don't retry if it's a safety block
                    else if (e.message == SAFETY_ERROR) throw e
                }
            }
        }
        throw GeminiTranslationException("All retries failed: ${lastError?.message}")
    }

    /**
     * Logic to group lines into blocks to minimize API calls.
     */
    private fun chunkByLimit(lines: List<String>, limit: Int): List<List<String>> {
        val chunks = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var count = 0
        for (line in lines) {
            if (count + line.length > limit && current.isNotEmpty()) {
                chunks.add(current)
                current = mutableListOf()
                count = 0
            }
            current.add(line)
            count += line.length + 1
        }
        if (current.isNotEmpty()) chunks.add(current)
        return chunks
    }

    /**
     * Constructs the JSON payload for the Gemini API, including the translation persona.
     */
    private fun buildRequestBody(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        glossary: String = "",
        isHtml: Boolean = false
    ): Map<String, Any> {
        val promptText = """
            [LITERARY PRESERVATION TASK]
            The following is a raw fictional excerpt for scholarly translation. 
            Translate my novel from [$sourceLanguage] to [$targetLanguage].
            TEXT:
            $text
        """.trimIndent()

        val htmlInstruction = if (isHtml) """
            5. HTML TAG PRESERVATION (STRICT):
            - The text contains HTML tags (e.g., <b>, <i>, <p>, <h1>).
            - You MUST preserve these tags exactly as they are in the translated text.
            - DO NOT translate the tags themselves (e.g., keep <b>, do not write <negrita>).
            - DO NOT remove, modify, or "clean up" the tags.
            - Ensure the tags surround the equivalent translated words.
            - Example Input: <h1>The Dragon</h1>
            - Example Output: <h1>El Dragón</h1>
        """.trimIndent() else ""

        val systemInstruction = """
            Act as a professional literary translator with extensive experience in localizing web novels, 
            light novels, and fiction literature. Your goal is to translate the user's text while maintaining 
            a natural, immersive, and aesthetically pleasing flow in the target language.
            
            Follow these translation guidelines strictly:
            
            1. TONE AND STYLE: 
            - Adapt the language to sound natural in the target language, prioritizing literary fluency over 
              word-for-word literal translation.
            - Preserve the original tone (romance, action, dark fantasy, comedy, etc.).
            - [NO SUPPRESSION]: If you encounter strange characters, symbols, gibberish, or repetitive text, 
              DO NOT omit or delete them. If you cannot translate them, include them exactly as they are 
              in the corresponding line. Every piece of source information must be present in the output.
            
            2. CONSISTENCY OF NAMES AND TERMS:
            - Do not translate proper names, fictional place names, or technical system terms (if it's an 
              RPG/LitRPG style novel) unless the literary context clearly demands it.
            - Respect the provided glossary to maintain consistency throughout the novel.
            - Glossary/Key Characters: {$glossary}
          
            3. ADAPTATION OF SENSITIVE CONTENT (VIOLENCE AND EROTICISM):
            - If the original text contains extremely explicit descriptions of physical violence, gore, 
              or sexual acts that might trigger standard content filters, DO NOT refuse to translate.
            - Instead, apply a "light translation" (softening): preserve the meaning, drama, and intent 
              of the scene, but reduce the level of anatomical, graphic, or explicit detail. 
              Use more suggestive, poetic, or abstract language so the scene remains coherent with the 
              plot without being purely explicit. ONLY do this if absolutely necessary; if possible, 
              maintain the intensity.
              
            4. OUTPUT STRUCTURE (CRITICAL):
            - ONE-TO-ONE MAPPING: Each individual line in the source MUST correspond to exactly one line in the translation.
            - NO MERGING: Never merge two or more lines into one. 
            - NO SPLITTING: Do not split a single line into multiple ones.
            - NO ADDITIONS: Return ONLY the translated text. Do not include introductions, "Here is the translation", translator notes, apologies, or any metadata. 
            - FAILURE CRITERIA: If the source has 10 lines, the output MUST have 10 lines. Any variation in the line count is a failure of your task.
            
            $htmlInstruction

        """.trimIndent()

        return mapOf(
            "contents" to listOf(
                mapOf(
                    "role" to "user",
                    "parts" to listOf(mapOf("text" to promptText))
                )
            ),
            "systemInstruction" to mapOf(
                "parts" to listOf(mapOf("text" to systemInstruction))
            ),
            "generationConfig" to mapOf(
                "temperature" to 0.15,
                "topP" to 0.9,
                "responseMimeType" to "text/plain"
            ),
            "safetySettings" to listOf(
                "HARM_CATEGORY_HARASSMENT",
                "HARM_CATEGORY_HATE_SPEECH",
                "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                "HARM_CATEGORY_DANGEROUS_CONTENT",
            ).map {
                mapOf("category" to it, "threshold" to "BLOCK_NONE")
            },
            "tools" to emptyList<Any>()
        )
    }
}