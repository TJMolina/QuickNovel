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
        private const val TRANSLATION_FAILED = "[TRANSLATION_FAILED]"
        private val MODELS = listOf("gemini-3.1-flash-lite")

        private const val MAX_RPM = 8
        private const val WINDOW_MILLIS = 60_000L
        private val timestamps = ArrayDeque<Long>()
        private val rateLimitMutex = Mutex()

        suspend fun acquireRateLimit() {
            rateLimitMutex.withLock {
                val now = System.currentTimeMillis()
                while (timestamps.isNotEmpty() && now - timestamps.first >= WINDOW_MILLIS) {
                    timestamps.removeFirst()
                }

                if (timestamps.size >= MAX_RPM) {
                    val oldest = timestamps.first
                    val waitTime = WINDOW_MILLIS - (now - oldest)
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
            if (details == null) return null
            for (detail in details) {
                detail.retryDelay?.let { raw->
                    val parsed = when (raw) {
                        is Number -> raw.toLong() * 1000L
                        is String -> parseDurationToMillis(raw)
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
                    cleaned.endsWith("ms", ignoreCase = true) -> {
                        cleaned.substring(0, cleaned.length - 2).toDoubleOrNull()?.toLong()
                    }
                    cleaned.endsWith("s", ignoreCase = true) -> {
                        (cleaned.substring(0, cleaned.length - 1).toDoubleOrNull()?.times(1000))?.toLong()
                    }
                    else -> {
                        cleaned.toDoubleOrNull()?.times(1000)?.toLong() ?: cleaned.toLongOrNull()
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
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
                    val leftHalf = lines.subList(0, mid)
                    val rightHalf = lines.subList(mid, lines.size)

                    recursiveTranslate(leftHalf, from, to, isHtml) +
                            recursiveTranslate(rightHalf, from, to, isHtml)
                }
                is GeminiSafetyError -> {
                    // Safety block on single line: return failed so Google Translate fallback can take over
                    lines.map { false to it }
                }
                // for rate limits, overloads, exhausted retries, connection errors: throw exception (no Google Translate fallback)
                else -> {
                    throw e
                }
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
                    if(res.error != null){
                        //rate limit or model overload error
                        val errorMessage = res.error.message
                        val isRateLimit = res.error.code == 429
                        val isOverloaded = res.error.code == 503 ||
                                errorMessage.contains("overloaded", ignoreCase = true) ||
                                errorMessage.contains("busy", ignoreCase = true) ||
                                errorMessage.contains("experiencing high demand", ignoreCase = true) ||
                                errorMessage.contains("resource_exhausted", ignoreCase = true)

                        if (isRateLimit || isOverloaded) {
                            val serverDelay = parseRetryDelay(res.error.details)
                            val baseDelay = serverDelay ?: (2000L * (attempt + 1))
                            val jitter = kotlin.random.Random.nextLong(0, 1000L)
                            val delay = baseDelay + jitter
                            delay(delay.milliseconds)

                            if (isOverloaded) {
                                throw GeminiOverloadException(errorMessage)
                            } else {
                                throw GeminiRateLimitException(errorMessage)
                            }
                        } else {
                            throw GeminiApiException(errorMessage)
                        }
                    }else{
                        // Check for safety blocks in prompt or candidates
                        val blockReason = res.promptFeedback?.blockReason ?: res.candidates?.firstOrNull()?.finishReason
                        val isBlocked = res.promptFeedback?.blockReason?.isNotBlank() == true ||
                                res.candidates?.firstOrNull()?.finishReason in listOf("SAFETY", "PROHIBITED_CONTENT")

                        if (isBlocked) throw GeminiSafetyError(blockReason ?: "SAFETY BLOCKED")
                        return res.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text?.trim()
                            ?: throw GeminiEmptyResponseException("Empty response from Gemini")
                    }

                } catch (e: Exception) {
                    logError(e)
                    lastError = e

                    //Wi-Fi error, don't retry
                    if (e is UnknownHostException) throw e
                    // Don't retry if it's a safety block
                    else if (e is GeminiSafetyError) throw e
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
            Act as an elite professional literary translator specializing in web novels, light novels, and serialized fiction. Your goal is to deliver an immersive, natural, and stylistically rich translation that feels as if it were originally written in the target language.

            Follow these rigorous translation guidelines strictly:

            0. DEEP CONTEXTUAL AWARENESS & NARRATIVE COHESION (CRITICAL):
            - CONTEXT IS PARAMOUNT: Always analyze preceding and succeeding sentences/paragraphs carefully before translating. A sentence's exact meaning, emotional resonance, and subtext depend heavily on the broader narrative context, character relationships, and plot developments.
            - POLYSEMY & AMBIGUITY RESOLUTION: Words frequently have multiple meanings depending on the language. Always choose the precise shade of meaning that fits the scene's mood, genre (romance, action, dark fantasy, comedy), and character persona.
            - LEXICAL VARIETY (SYNONYMS): If the source text uses varied vocabulary across paragraphs, avoid repeating the same translated word monotonously. Utilize rich synonyms and stylistic variation in the target language to keep the prose engaging and natural.
            - CULTURAL SUBTEXT & WEB NOVEL IDIOMS: Be acutely aware of genre-specific idioms and cultural tropes (e.g., specialized terminology in Chinese, Japanese, or Korean web novels such as metaphorical kinship terms like "grand mother" for queens/stepmothers, honorifics, cultivation concepts, or idioms). Translate them with culturally or functionally equivalent expressions that capture the author's exact intent rather than awkward literal translations.

            1. TONE, STYLE & FLUIDITY:
            - Prioritize literary fluency and natural phrasing over word-for-word literalness ("translator-ese").
            - Maintain consistent character voices and emotional tone throughout the excerpt.
            - [NO SUPPRESSION]: If you encounter strange characters, symbols, gibberish, or repetitive text, DO NOT omit or delete them. Include them exactly as they are in the corresponding line. Every piece of source information must be preserved.

            2. CONSISTENCY OF PROPER NONS & TERMINOLOGY:
            - Respect the provided glossary to ensure absolute consistency for character names, titles, and locations.
            - Do not translate proper names, fictional places, or specialized system terms (e.g., LitRPG stats/skills) unless requested or contextually required.
            - Glossary/Key Terms: {$glossary}

            3. ADAPTATION OF SENSITIVE CONTENT (VIOLENCE AND EROTICISM):
            - If the text contains extremely explicit descriptions of violence,OGS, or sexual acts that might trigger strict safety filters, DO NOT refuse to translate.
            - Instead, apply a "literary softening" (light translation): preserve the core drama, emotional intensity, and plot intent of the scene while using more suggestive, abstract, or poetic phrasing instead of raw anatomical or graphic detail. Maintain narrative coherence without unnecessary vulgarity.

            4. OUTPUT STRUCTURE & LINE ALIGNMENT (ABSOLUTE REQUIREMENT):
            - ONE-TO-ONE MAPPING: Each individual line in the source MUST correspond to exactly one line in the translation.
            - NO MERGING: Never merge two or more source lines into a single line.
            - NO SPLITTING: Never split a single source line into multiple lines.
            - NO ADDITIONS: Return ONLY the translated text. Do not include introductions, explanations, translator notes, apologies, or markdown wrappers unless part of the source.
            - FAILURE CRITERIA: If the source contains N lines, the output MUST contain exactly N lines. Any discrepancy in line count is an unacceptable failure.

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
                "temperature" to 0.32,
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