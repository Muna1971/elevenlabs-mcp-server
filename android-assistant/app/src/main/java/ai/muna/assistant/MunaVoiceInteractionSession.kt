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
 * the current screen (if the user enabled screen analysis) and brings up the
 * full-screen Muna voice orb.
 */
class MunaVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        ScreenContext.set(screenshot)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intent = Intent(context, AssistActivity::class.java).apply {
            action = Intent.ACTION_ASSIST
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(intent)
        // Give the system a moment to deliver the screenshot before dismissing.
        Handler(Looper.getMainLooper()).postDelayed({ runCatching { hide() } }, 1200)
    }
}
