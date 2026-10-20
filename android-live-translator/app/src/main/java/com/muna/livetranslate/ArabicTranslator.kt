package com.muna.livetranslate

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/** Translates text in any detected language to Arabic, fetching new language packs on demand. */
class ArabicTranslator(private val onDownloadingLanguage: (String) -> Unit) : AutoCloseable {

    private val translators = HashMap<String, Translator>()
    private val mutex = Mutex()

    /** Returns the Arabic text, or the original text when the language cannot be translated. */
    suspend fun translate(text: String, languageCode: String): String {
        val lang = ModelManager.mlKitLanguage(languageCode) ?: return text
        if (lang == TranslateLanguage.ARABIC) return text
        return translatorFor(lang).translate(text).await()
    }

    private suspend fun translatorFor(lang: String): Translator = mutex.withLock {
        translators[lang] ?: ModelManager.newArabicTranslator(lang).also { t ->
            val model = TranslateRemoteModel.Builder(lang).build()
            if (!RemoteModelManager.getInstance().isModelDownloaded(model).await()) onDownloadingLanguage(lang)
            t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            translators[lang] = t
        }
    }

    override fun close() {
        translators.values.forEach { it.close() }
        translators.clear()
    }
}
