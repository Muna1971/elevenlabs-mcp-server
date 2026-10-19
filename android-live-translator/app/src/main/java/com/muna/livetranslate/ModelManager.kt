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
import java.util.zip.ZipInputStream

/** Downloads (once) and locates the offline speech and translation models. */
object ModelManager {

    fun speechModelDir(context: Context, lang: SourceLanguage) =
        File(context.filesDir, "vosk/${lang.voskModel}")

    fun isSpeechModelReady(context: Context, lang: SourceLanguage) =
        File(speechModelDir(context, lang), READY_MARKER).exists()

    fun newArabicTranslator(lang: SourceLanguage): Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(lang.mlKitCode)
            .setTargetLanguage(TranslateLanguage.ARABIC)
            .build()
    )

    suspend fun ensureTranslationModel(lang: SourceLanguage) {
        val translator = newArabicTranslator(lang)
        try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        } finally {
            translator.close()
        }
    }

    suspend fun downloadSpeechModel(
        context: Context,
        lang: SourceLanguage,
        onProgress: (Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "vosk").apply { mkdirs() }
        val zip = File(context.cacheDir, "${lang.voskModel}.zip")
        val conn = URL(lang.modelUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                zip.outputStream().use { out ->
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
                }
            }
        } finally {
            conn.disconnect()
        }

        val tmp = File(root, "${lang.voskModel}.tmp").apply { deleteRecursively(); mkdirs() }
        val tmpPath = tmp.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val f = File(tmp, entry.name)
                if (!f.canonicalPath.startsWith(tmpPath)) throw IOException("Bad zip entry")
                if (entry.isDirectory) {
                    f.mkdirs()
                } else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { zis.copyTo(it) }
                }
                entry = zis.nextEntry
            }
        }
        zip.delete()

        // The archive normally contains a single top-level folder holding the model.
        val modelRoot = tmp.listFiles()?.singleOrNull { it.isDirectory } ?: tmp
        val dest = speechModelDir(context, lang).apply { deleteRecursively() }
        if (!modelRoot.renameTo(dest)) throw IOException("Could not install model")
        tmp.deleteRecursively()
        File(dest, READY_MARKER).writeText("ok")
    }

    private const val READY_MARKER = ".ready"
}
