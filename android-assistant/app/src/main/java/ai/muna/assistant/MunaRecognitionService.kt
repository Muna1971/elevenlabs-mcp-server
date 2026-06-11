package ai.muna.assistant

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Minimal RecognitionService required by the voice-interaction metadata.
 * Muna performs its own speech recognition via the platform recognizer inside
 * the app, so this stub simply reports that it does no recognition itself.
 */
class MunaRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        runCatching { listener?.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }
    }

    override fun onCancel(listener: Callback?) {}

    override fun onStopListening(listener: Callback?) {}
}
