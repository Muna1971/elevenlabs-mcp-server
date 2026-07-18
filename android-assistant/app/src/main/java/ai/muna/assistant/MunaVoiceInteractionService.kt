package ai.muna.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession

/**
 * Declares the app as a device assistant. The system binds to this service
 * when the user selects "مطراش" as the default digital assistant.
 * The actual UI is shown by [MunaSessionService] / [MunaVoiceInteractionSession].
 *
 * Also exposes a SILENT screen capture ([captureScreen]) so the hands-free wake
 * service can "read the screen" without popping the assist orb.
 */
class MunaVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        instance = this
    }

    override fun onShutdown() {
        super.onShutdown()
        if (instance === this) instance = null
    }

    /**
     * Grab the current screen (text + screenshot) into [ScreenContext] without
     * launching the orb. Works only while Matrash is the active assistant and
     * the user enabled "analyze screen text/images".
     */
    fun captureScreen() {
        val args = Bundle().apply { putBoolean(EXTRA_CAPTURE_ONLY, true) }
        runCatching {
            showSession(
                args,
                VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT
            )
        }
    }

    companion object {
        const val EXTRA_CAPTURE_ONLY = "capture_only"
        @Volatile
        var instance: MunaVoiceInteractionService? = null
    }
}
