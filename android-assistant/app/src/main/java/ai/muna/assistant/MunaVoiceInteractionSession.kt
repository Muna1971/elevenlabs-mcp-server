package ai.muna.assistant

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import android.service.voice.VoiceInteractionSession

/**
 * Invoked when the user triggers the assistant (long-press side key). Captures
 * the current screen (screenshot + on-screen text) so Muna/Matrash can "see"
 * and "read" what's on screen, then opens the voice orb.
 */
class MunaVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    private val main = Handler(Looper.getMainLooper())
    private var launched = false

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        launched = false
        // Fallback: open even if no screen data arrives (toggles off).
        main.postDelayed({ launchOrb() }, 800)
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        ScreenContext.set(screenshot)
        launchOrb()
    }

    // API 29+ delivers assist data here.
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        runCatching { extractText(state.assistStructure) }
        launchOrb()
    }

    // Older devices use this signature.
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onHandleAssist(
        data: Bundle?,
        structure: AssistStructure?,
        content: AssistContent?
    ) {
        super.onHandleAssist(data, structure, content)
        runCatching { extractText(structure) }
        launchOrb()
    }

    private fun extractText(structure: AssistStructure?) {
        if (structure == null) return
        val sb = StringBuilder()
        for (i in 0 until structure.windowNodeCount) {
            collect(structure.getWindowNodeAt(i).rootViewNode, sb)
        }
        if (sb.isNotBlank()) ScreenContext.setText(sb.toString().trim().take(8000))
    }

    private fun collect(node: AssistStructure.ViewNode?, sb: StringBuilder) {
        if (node == null) return
        node.text?.let { if (it.isNotBlank()) sb.append(it).append('\n') }
        node.contentDescription?.let { if (it.isNotBlank()) sb.append(it).append('\n') }
        for (i in 0 until node.childCount) collect(node.getChildAt(i), sb)
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
