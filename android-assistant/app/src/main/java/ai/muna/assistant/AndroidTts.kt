package ai.muna.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Free, offline fallback voice using Android's built-in Text-to-Speech.
 * Used when ElevenLabs is unavailable (e.g. out of credits) so Muna always
 * speaks.
 */
class AndroidTts(context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var pending: (() -> Unit)? = null
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts.setLanguage(Locale("ar", "AE"))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    runCatching { tts.setLanguage(Locale("ar")) }
                }
                selectBestArabicVoice()
                ready = true
            }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finish()
            @Deprecated("deprecated") override fun onError(utteranceId: String?) = finish()
            override fun onError(utteranceId: String?, errorCode: Int) = finish()
        })
    }

    /** Pick the highest-quality Arabic voice available on the device. */
    private fun selectBestArabicVoice() {
        runCatching {
            val best = tts.voices
                ?.filter { it.locale?.language == "ar" }
                ?.maxByOrNull { it.quality }
            if (best != null) tts.voice = best
        }
    }

    private fun finish() {
        val cb = pending
        pending = null
        cb?.let { main.post(it) }
    }

    /** Speak [text]; [onDone] runs on the main thread when finished. */
    fun speak(text: String, onDone: () -> Unit) {
        if (text.isBlank()) { onDone(); return }
        pending = onDone
        val spoken = fixName(text)
        if (!ready) main.postDelayed({ doSpeak(spoken, onDone) }, 700)
        else doSpeak(spoken, onDone)
    }

    private fun doSpeak(text: String, onDone: () -> Unit) {
        val res = runCatching { tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "muna") }
            .getOrDefault(TextToSpeech.ERROR)
        if (res == TextToSpeech.ERROR) { pending = null; onDone() }
    }

    private fun fixName(text: String): String =
        text.replace('ق', 'گ')
            .replace(Regex("(?<![\\u0621-\\u064A])منى(?![\\u0621-\\u064A])"), "مُنى")

    fun stop() { runCatching { tts.stop() } }

    fun shutdown() { runCatching { tts.shutdown() } }
}
