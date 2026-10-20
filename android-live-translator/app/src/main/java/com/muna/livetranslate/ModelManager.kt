package com.muna.livetranslate

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Downloads (once) and locates the offline speech and translation models. */
object ModelManager {

    private const val SPEECH_MODEL = "ggml-base-q5_1.bin"
    const val SPEECH_MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$SPEECH_MODEL"

    fun speechModelFile(context: Context) = File(context.filesDir, SPEECH_MODEL)

    fun isSpeechModelReady(context: Context) = speechModelFile(context).exists()

    /** ML Kit language for a Whisper/ISO code, or null if it cannot be translated offline. */
    fun mlKitLanguage(code: String): String? = TranslateLanguage.fromLanguageTag(code)

    fun newArabicTranslator(sourceMlKitLanguage: String): Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(sourceMlKitLanguage)
            .setTargetLanguage(TranslateLanguage.ARABIC)
            .build()
    )

    suspend fun ensureTranslationModel(code: String) {
        val lang = mlKitLanguage(code) ?: return
        if (lang == TranslateLanguage.ARABIC) return
        val translator = newArabicTranslator(lang)
        try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        } finally {
            translator.close()
        }
    }

    suspend fun downloadSpeechModel(context: Context, onProgress: (Int) -> Unit) =
        withContext(Dispatchers.IO) {
            val dest = speechModelFile(context)
            val part = File(dest.path + ".part")
            val conn = URL(SPEECH_MODEL_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            try {
                if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPercent = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val percent = (done * 100 / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    withContext(Dispatchers.Main) { onProgress(percent) }
                                }
                            }
                        }
                        if (total > 0 && done != total) throw IOException("Download incomplete")
                    }
                }
            } finally {
                conn.disconnect()
            }
            if (!part.renameTo(dest)) throw IOException("Could not install model")
        }
}
