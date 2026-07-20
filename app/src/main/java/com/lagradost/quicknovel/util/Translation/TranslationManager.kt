package com.lagradost.quicknovel.util.translation

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.MainActivity
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.util.translation.models.TranslatorAgent
import com.lagradost.safefile.closeQuietly
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TranslationManager {
    private val geminiTranslator = GeminiTranslateOnline(
        apiKey = "",
        client = MainActivity.app
    )

    private val onlineTranslator = GoogleTranslateOnline(MainActivity.app)

    private var translator: Translator? = null // MLKit Offline
    private var currentFrom: String? = null
    private var currentTo: String? = null
    private var currentAgent: TranslatorAgent = TranslatorAgent.OFFLINE

    /**
     * Configura los idiomas y el agente activo.
     */
    fun setSettings(from: String, to: String, agent: TranslatorAgent) {
        if (currentFrom != from || currentTo != to || currentAgent != agent) {
            currentFrom = from
            currentTo = to
            currentAgent = agent

            if (agent != TranslatorAgent.OFFLINE) {
                translator?.closeQuietly()
                translator = null
            }
        }
    }

    suspend fun isModelDownloaded(source: String, target: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val modelManager = RemoteModelManager.getInstance()
            val sDownloaded = if (source == "en") true
            else Tasks.await(modelManager.isModelDownloaded(
                TranslateRemoteModel.Builder(source).build()
            ))
            val tDownloaded = if (target == "en") true
            else Tasks.await(modelManager.isModelDownloaded(
                TranslateRemoteModel.Builder(target).build()
            ))
            return@withContext sDownloaded && tDownloaded
        } catch (e: Exception) {
            logError(e)
            return@withContext false
        }
    }
    suspend fun prepareModel(from: String, to: String): Translator? {
        try {
            if (translator != null && currentFrom == from && currentTo == to) {
                return translator
            }

            translator?.closeQuietly()

            val sourceTag = TranslateLanguage.fromLanguageTag(from) ?: throw ErrorLoadingException("Language $from doesn't exist")
            val targetTag = TranslateLanguage.fromLanguageTag(to) ?: throw ErrorLoadingException("Language $to doesn't exist")

            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceTag)
                .setTargetLanguage(targetTag)
                .build()

            val client = Translation.getClient(options)

            if (!isModelDownloaded(from, to)) {
                Tasks.await(client.downloadModelIfNeeded(DownloadConditions.Builder().build()))
            }

            translator = client
            return translator
        } catch (e: Exception) {
            logError(e)
            return null
        }
    }

    suspend fun translate(
        textList: List<String>,
        progress: suspend (Int, Int) -> Unit = { _, _ -> },
    ): List<String> {
        if (textList.isEmpty()) return emptyList()

        val from = currentFrom ?: throw Exception("Source language not set")
        val to = currentTo ?: throw Exception("Target language not set")

        return when (currentAgent) {
            TranslatorAgent.ONLINE -> {
                val result = onlineTranslator.translate(textList, from, to, progress)
                onlineTranslator.fixFailures(result, from, to)
            }

            TranslatorAgent.OFFLINE -> {
                offlineTranslate(textList, from, to, progress)
            }

            TranslatorAgent.GEMINI -> {
                val result = geminiTranslator.translate(textList, from, to, progress)
                onlineTranslator.fixFailures(result, from, to)
            }
        }
    }

    private suspend fun offlineTranslate(
        textList: List<String>,
        from: String,
        to: String,
        progress: suspend (Int, Int) -> Unit
    ): List<String> {
        val client = translator ?: prepareModel(from, to) ?: throw Exception("Offline model not available")
        return textList.mapIndexed { index, text ->
            if (!text.trim().any { it.isLetter() }) return@mapIndexed text
            progress(index + 1, textList.size)
            Tasks.await(client.translate(text))
        }
    }

    fun release() {
        translator?.closeQuietly()
        translator = null
        currentFrom = null
        currentTo = null
    }
}