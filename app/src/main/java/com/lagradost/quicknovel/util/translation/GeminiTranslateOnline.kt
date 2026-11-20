package com.lagradost.quicknovel.util.translation

import com.lagradost.nicehttp.Requests
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.util.translation.models.FailedContext
import com.lagradost.quicknovel.util.translation.models.GeminiApiException
import com.lagradost.quicknovel.util.translation.models.GeminiEmptyResponseException
import com.lagradost.quicknovel.util.translation.models.GeminiErrorDetail
import com.lagradost.quicknovel.util.translation.models.GeminiOverloadException
import com.lagradost.quicknovel.util.translation.models.GeminiRateLimitException
import com.lagradost.quicknovel.util.translation.models.GeminiSafetyError
import com.lagradost.quicknovel.util.translation.models.GeminiTranslationException
import com.lagradost.quicknovel.util.translation.models.GeminiTranslationResponse
import com.lagradost.quicknovel.util.translation.models.OnlineTranslator
import com.lagradost.quicknovel.util.translation.models.TranslationResult
import kotlinx.coroutines.delay
import java.net.UnknownHostException
import kotlin.time.Duration.Companion.milliseconds
import java.util.ArrayDeque
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Get an API key at: https://aistudio.google.com/
 */
class GeminiTranslateOnline(
    private var apiKey: String,
    private val client: Requests,
) : OnlineTranslator {

    fun updateApiKey(key: String) {
        apiKey = key
    }

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
        private const val MAX_CHARS_PER_CHUNK = 5000
        private const val MAX_RETRIES = 15
        private const val MAX_RPM = 15
        private const val WINDOW_MILLIS = 60_000L
        private const val TRANSLATION_FAILED = "[TRANSLATION_FAILED]"
        private val MODELS = listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite")

        private val timestamps = ArrayDeque<Long>()
        private val rateLimitMutex = Mutex()

        suspend fun acquireRateLimit() {
            rateLimitMutex.withLock {
                val now = System.currentTimeMillis()
                while (timestamps.isNotEmpty() && now - timestamps.first >= WINDOW_MILLIS) {
                    timestamps.removeFirst()
                }

                if (timestamps.size >= MAX_RPM) {
                    val waitTime = WINDOW_MILLIS - (now - timestamps.first)
                    if (waitTime > 0) {
                        delay((waitTime + 50).milliseconds)
                    }
                    val updatedNow = System.currentTimeMillis()
                    while (timestamps.isNotEmpty() && updatedNow - timestamps.first >= WINDOW_MILLIS) {
                        timestamps.removeFirst()
                    }
                    timestamps.addLast(updatedNow)
                } else {
                    timestamps.addLast(now)
                }
            }
        }

        private fun parseRetryDelay(details: List<GeminiErrorDetail>?): Long? {
            if (details.isNullOrEmpty()) return null
            for (detail in details) {
                detail.retryDelay?.let { raw ->
                    val parsed = when (raw) {
                        is Number -> raw.toLong() * 1000L
                        else -> parseDurationToMillis(raw.toString())
                    }
                    if (parsed != null && parsed > 0) return parsed
                }
            }
            return null
        }

        private fun parseDurationToMillis(durationStr: String): Long? {
            return try {
                val cleaned = durationStr.trim()
                when {
                    cleaned.endsWith("ms", ignoreCase = true) ->
                        cleaned.removeSuffix("ms").trim().toDoubleOrNull()?.toLong()
                    cleaned.endsWith("s", ignoreCase = true) ->
                        (cleaned.removeSuffix("s").trim().toDoubleOrNull()?.times(1000))?.toLong()
                    else -> cleaned.toDoubleOrNull()?.times(1000)?.toLong() ?: cleaned.toLongOrNull()
                }
            } catch (e: Exception) {
                null
            }
        }

        private const val SYSTEM_INSTRUCTION = """
            Act as an elite professional literary translator specializing in web novels, light novels, and serialized fiction. Your goal is to deliver an immersive, natural, and stylistically rich translation that feels as if it were originally written in the target language.

            Follow these rigorous translation guidelines strictly:

            0. DEEP CONTEXTUAL AWARENESS & NARRATIVE COHESION (CRITICAL):
            - CONTEXT IS PARAMOUNT: Always analyze preceding and succeeding sentences/paragraphs carefully before translating.
            - POLYSEMY & AMBIGUITY RESOLUTION: Choose the precise shade of meaning fitting the scene's mood and genre.
            - LEXICAL VARIETY: Avoid repeating translated words monotonously; use rich synonyms.
            - CULTURAL SUBTEXT & IDIOMS: Adapt genre-specific tropes and idioms with functionally equivalent expressions.

            1. TONE, STYLE & FLUIDITY:
            - Prioritize literary fluency and natural phrasing over word-for-word literalness.
            - Maintain consistent character voices and emotional tone.
            - [NO SUPPRESSION]: Preserve strange characters, symbols, or repetitive text exactly as they are.

            2. CONSISTENCY OF PROPER NOUNS & TERMINOLOGY:
            - Respect glossary items for character names, titles, and locations.
            - Glossary/Key Terms: {glossary}

            3. ADAPTATION OF SENSITIVE CONTENT:
            - If text contains explicit descriptions, apply literary softening (preserve core drama/intent without raw graphic detail).

            4. OUTPUT STRUCTURE & LINE ALIGNMENT (ABSOLUTE REQUIREMENT):
            - ONE-TO-ONE MAPPING: Each source line corresponds to exactly one translation line.
            - NO MERGING or SPLITTING lines.
            - NO ADDITIONS: Return ONLY translated text.
            - FAILURE CRITERIA: N source lines must yield exactly N output lines.
        """

        private const val HTML_INSTRUCTION = """
            5. HTML TAG PRESERVATION (STRICT):
            - Preserve HTML tags (e.g., <b>, <i>, <p>, <h1>) exactly as in source.
            - DO NOT translate, remove, or modify tags.
        """
    }

    override suspend fun translate(
        textList: List<String>,
        from: String,
        to: String,
        isHtml: Boolean,
        progress: suspend (Int, Int) -> Unit
    ): TranslationResult {
        if (textList.isEmpty()) return TranslationResult(emptyList(), emptyList())

        val allTranslatedLines = textList.toMutableList()
        val failedParagraphs = mutableListOf<FailedContext>()
        val contentParagraphs = mutableListOf<String>()
        val paragraphIndices = mutableListOf<Int>()

        textList.forEachIndexed { index, text ->
            if (TranslationsUtils.getTranslatableText(text, isHtml) != null) {
                contentParagraphs.add(TranslationsUtils.sanitize(text))
                paragraphIndices.add(index)
            }
        }

        val chunks = chunkByLimit(contentParagraphs)
        var contentPointer = 0

        chunks.forEachIndexed { index, chunkLines ->
            progress(index, chunks.size)
            val result = recursiveTranslate(chunkLines, from, to, isHtml)

            result.forEach { (isSuccess, translatedText) ->
                if (contentPointer < paragraphIndices.size) {
                    val originalGlobalIndex = paragraphIndices[contentPointer]
                    if (isSuccess) {
                        allTranslatedLines[originalGlobalIndex] = translatedText
                    } else {
                        failedParagraphs.add(FailedContext(originalGlobalIndex, translatedText))
                        allTranslatedLines[originalGlobalIndex] = TRANSLATION_FAILED
                    }
                    contentPointer++
                }
            }
        }

        return TranslationResult(allTranslatedLines.toList(), failedParagraphs)
    }
    /**
     * Recursively handles translation. If a block of text is flagged by safety filters,
     * it splits the block in half and tries again for each part.
     */
    private suspend fun recursiveTranslate(
        lines: List<String>,
        from: String,
        to: String,
        isHtml: Boolean
    ): List<Pair<Boolean, String>> {
        return try {
            // Try to translate the entire block at once
            val translated = callGeminiWithRetry(lines.joinToString("\n"), from, to, isHtml)
            val translatedLines = translated.lines().filter { it.isNotBlank() }

            // Each line is paired with 'true' to indicate success
            if (translatedLines.size != lines.size) {
                lines.map { false to it }
            } else {
                translatedLines.map { true to it }
            }

        } catch (e: Exception) {
            // If Gemini says "SAFETY_BLOCKED", allow retries by splitting or mark as failed for Google Translate fallback.
            when (e) {
                is GeminiSafetyError if lines.size > 1 -> {
                    val mid = lines.size / 2
                    recursiveTranslate(lines.subList(0, mid), from, to, isHtml) +
                            recursiveTranslate(lines.subList(mid, lines.size), from, to, isHtml)
                }
                is GeminiSafetyError -> lines.map { false to it }
                else -> throw e
            }
        }
    }

    /**
     * Handles the network request, model fallback, and retries (429 errors).
     */
    private suspend fun callGeminiWithRetry(text: String, from: String, to: String, isHtml: Boolean): String {
        var lastError: Exception? = null

        for (model in MODELS) {
            repeat(MAX_RETRIES) { attempt ->
                try {
                    // Respect the RPM rate limiter before making any request attempt
                    acquireRateLimit()

                    val res = client.post(
                        url = "$BASE_URL$model:generateContent",
                        json = buildRequestBody(text, from, to, isHtml = isHtml),
                        headers = mapOf("X-Goog-API-Key" to apiKey)
                    ).parsed<GeminiTranslationResponse>()

                    res.error?.let { err ->
                        val msg = err.message
                        val isRateLimit = err.code == 429
                        val isOverloaded = err.code == 503 ||
                                msg.contains("overloaded", true) ||
                                msg.contains("busy", true) ||
                                msg.contains("high demand", true) ||
                                msg.contains("resource_exhausted", true)

                        if (isRateLimit || isOverloaded) {
                            val serverDelay = parseRetryDelay(err.details)
                            val baseDelay = serverDelay ?: (2000L * (attempt + 1))
                            val delayTime = baseDelay + kotlin.random.Random.nextLong(0, 1000L)
                            delay(delayTime.milliseconds)

                            throw if (isOverloaded) GeminiOverloadException(msg) else GeminiRateLimitException(msg)
                        } else {
                            throw GeminiApiException(msg)
                        }
                    }

                    val candidate = res.candidates?.firstOrNull()
                    val blockReason = res.promptFeedback?.blockReason ?: candidate?.finishReason
                    val isBlocked = !res.promptFeedback?.blockReason.isNullOrBlank() ||
                            candidate?.finishReason in listOf("SAFETY", "PROHIBITED_CONTENT")

                    if (isBlocked) throw GeminiSafetyError(blockReason ?: "SAFETY BLOCKED")

                    return candidate?.content?.parts?.firstOrNull()?.text?.trim()
                        ?: throw GeminiEmptyResponseException("Empty response from Gemini")

                } catch (e: Exception) {
                    logError(e)
                    lastError = e
                    if (e is UnknownHostException || e is GeminiSafetyError) throw e
                }
            }
        }
        throw lastError ?: GeminiTranslationException("All retries failed")
    }

    /**
     * Logic to group lines into blocks to minimize API calls.
     */
    private fun chunkByLimit(lines: List<String>): List<List<String>> {
        val chunks = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var count = 0
        for (line in lines) {
            if (count + line.length > MAX_CHARS_PER_CHUNK && current.isNotEmpty()) {
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
     * Constructs the JSON payload for the Gemini API
     */
    private fun buildRequestBody(
        text: String,
        from: String,
        to: String,
        glossary: String = "",
        isHtml: Boolean = false
    ): Map<String, Any> {
        val promptText = """
            [LITERARY PRESERVATION TASK]
            Translate the following web/light novel excerpt from [$from] to [$to]. Maintain absolute narrative flow, emotional depth, precise paragraph cohesion, and exact line alignment.
            TEXT:
            $text
        """.trimIndent()

        val systemInstruction = SYSTEM_INSTRUCTION.replace("{glossary}", glossary) +
                if (isHtml) "\n$HTML_INSTRUCTION" else ""

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
                "temperature" to 0.32,
                "topP" to 0.9,
                "responseMimeType" to "text/plain"
            ),
            "safetySettings" to listOf(
                "HARM_CATEGORY_HARASSMENT",
                "HARM_CATEGORY_HATE_SPEECH",
                "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                "HARM_CATEGORY_DANGEROUS_CONTENT"
            ).map { mapOf("category" to it, "threshold" to "BLOCK_NONE") },
            "tools" to emptyList<Any>()
        )
    }
}