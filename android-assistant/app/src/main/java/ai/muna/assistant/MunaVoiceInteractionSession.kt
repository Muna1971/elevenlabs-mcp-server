package ai.muna.assistant

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession

/**
 * Invoked when the user triggers the assistant (long-press side key). Captures
 * the current screen first (if screen analysis is enabled), THEN opens the
 * voice orb — so Muna reliably "sees" what's on screen.
 */
class MunaVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    private val main = Handler(Looper.getMainLooper())
    private var launched = false

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        launched = false
        // Fallback: if no screenshot is delivered (toggle off), open anyway.
        main.postDelayed({ launchOrb() }, 700)
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        ScreenContext.set(screenshot)
        // Now that we have the screen, open the orb.
        launchOrb()
    }

    private fun launchOrb() {
        if (launched) return
        launched = true
        val intent = Intent(context, AssistActivity::class.java).apply {
            action = Intent.ACTION_ASSIST
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(intent)
        main.postDelayed({ runCatching { hide() } }, 1500)
    }
}
