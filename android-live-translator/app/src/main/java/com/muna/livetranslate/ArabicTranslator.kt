package com.muna.livetranslate

import android.content.Context
import android.util.Log
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Translates any detected language to Arabic.
 *
 * Uses Mozilla's open-source Firefox Translations models (English -> Arabic directly,
 * other languages through English). Google ML Kit covers the few languages Mozilla
 * has no model for. Language packs are fetched in the background the first time a
 * language is heard; until then the original text is shown.
 */
class ArabicTranslator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onStatus: (String) -> Unit,
) : AutoCloseable {

    private val engineThread = Executors.newSingleThreadExecutor()
    private val engineDispatcher = engineThread.asCoroutineDispatcher()
    private val engine = BergamotEngine()
    private val mlKit = ConcurrentHashMap<String, Translator>()
    private val mlKitReady = ConcurrentHashMap.newKeySet<String>()
    private val busy = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap.newKeySet<String>()

    suspend fun translate(text: String, whisperLanguage: String): String {
        val code = WHISPER_TO_MOZILLA[whisperLanguage] ?: whisperLanguage
        if (code == "ar") return text
        return try {
            when {
                code == "en" ->
                    if (bergamotReady(EN_AR, code)) onEngine { engine.translate(EN_AR, text) } ?: text
                    else text
                ModelManager.hasBergamotPair(context, "$code-en") -> {
                    val first = "$code-en"
                    if (bergamotReady(EN_AR, code) && bergamotReady(first, code)) {
                        onEngine { engine.pivot(first, EN_AR, text) } ?: text
                    } else {
                        text
                    }
                }
                else -> translateWithMlKit(code, text)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Translation failed", e)
            text
        }
    }

    /** True when [pair] is loaded; otherwise starts fetching it in the background. */
    private suspend fun bergamotReady(pair: String, spokenLanguage: String): Boolean {
        if (onEngine { engine.isLoaded(pair) }) return true
        if (pair in failed) return false
        if (ModelManager.isBergamotReady(context, pair)) {
            val files = ModelManager.bergamotFiles(context, pair)
            val ok = onEngine { engine.load(pair, files) }
            if (!ok) failed.add(pair)
            return ok
        }
        if (busy.add(pair)) {
            scope.launch {
                onStatus("جارٍ تنزيل ترجمة ${arabicName(spokenLanguage)} (مرة واحدة)…")
                try {
                    ModelManager.downloadBergamot(context, pair)
                    onStatus("تم تنزيل ترجمة ${arabicName(spokenLanguage)} ✓")
                } catch (e: Exception) {
                    Log.w(TAG, "Download of $pair failed", e)
                    onStatus("تعذّر تنزيل ترجمة ${arabicName(spokenLanguage)}، تحقق من الإنترنت")
                } finally {
                    busy.remove(pair)
                }
            }
        }
        return false
    }

    private suspend fun translateWithMlKit(code: String, text: String): String {
        val lang = ModelManager.mlKitLanguage(code) ?: return text
        if (lang == TranslateLanguage.ARABIC) return text
        val translator = mlKit.getOrPut(lang) { ModelManager.newMlKitArabicTranslator(lang) }
        if (lang in mlKitReady) return translator.translate(text).await()
        if (busy.add("mlkit-$lang")) {
            scope.launch {
                onStatus("جارٍ تنزيل ترجمة ${arabicName(code)} (مرة واحدة)…")
                try {
                    ModelManager.ensureDownloaded(translator)
                    mlKitReady.add(lang)
                } catch (e: Exception) {
                    Log.w(TAG, "ML Kit download failed", e)
                } finally {
                    busy.remove("mlkit-$lang")
                }
            }
        }
        return text
    }

    private suspend fun <T> onEngine(block: () -> T): T = withContext(engineDispatcher) { block() }

    override fun close() {
        mlKit.values.forEach { it.close() }
        engineThread.execute { engine.close() }
        engineThread.shutdown()
    }

    companion object {
        const val EN_AR = "en-ar"
        private const val TAG = "ArabicTranslator"

        /** Whisper language codes that Mozilla names differently. */
        private val WHISPER_TO_MOZILLA = mapOf("no" to "nb", "jw" to "jv")

        fun arabicName(code: String): String =
            Locale.forLanguageTag(code).getDisplayLanguage(Locale.forLanguageTag("ar")).ifBlank { code }
    }
}
