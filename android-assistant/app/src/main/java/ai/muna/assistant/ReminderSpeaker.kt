package ai.muna.assistant

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Speaks a reminder aloud with the device's Arabic voice, then releases itself.
 * Used from ReminderReceiver where there is no Activity/Service lifecycle.
 */
object ReminderSpeaker {

    fun speak(ctx: Context, text: String, onDone: () -> Unit) {
        var tts: TextToSpeech? = null
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finish() {
            if (done.getAndSet(true)) return
            runCatching { tts?.stop(); tts?.shutdown() }
            onDone()
        }
        // Safety timeout so the receiver never hangs.
        android.os.Handler(ctx.mainLooper).postDelayed({ finish() }, 12_000)

        tts = TextToSpeech(ctx) { status ->
            if (status != TextToSpeech.SUCCESS) { finish(); return@TextToSpeech }
            val t = tts ?: return@TextToSpeech
            runCatching { t.language = Locale("ar") }
            // Pick the best Arabic voice if available.
            runCatching {
                t.voices?.firstOrNull { it.locale?.language == "ar" && !it.isNetworkConnectionRequired }
                    ?.let { t.voice = it }
            }
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { finish() }
                @Deprecated("deprecated") override fun onError(id: String?) { finish() }
                override fun onError(id: String?, code: Int) { finish() }
            })
            val params = Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
            }
            val r = t.speak(text, TextToSpeech.QUEUE_FLUSH, params, "reminder")
            if (r != TextToSpeech.SUCCESS) finish()
        }
    }
}
