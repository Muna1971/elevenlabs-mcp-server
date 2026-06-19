package ai.muna.assistant

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Holds the most recent screenshot captured by the assistant session, so the
 * voice orb can show Claude what's on screen ("شو رأيك بهذا؟").
 */
object ScreenContext {

    @Volatile private var b64: String? = null
    @Volatile private var capturedAt: Long = 0
    @Volatile private var text: String? = null
    @Volatile private var textAt: Long = 0

    fun set(bitmap: Bitmap?) {
        b64 = bitmap?.let { encode(it) }
        capturedAt = System.currentTimeMillis()
    }

    /** On-screen text extracted from the assist structure. */
    fun setText(t: String?) {
        text = t?.takeIf { it.isNotBlank() }
        textAt = System.currentTimeMillis()
    }

    /** The screenshot if captured within the last minute, else null. */
    fun recent(): String? =
        b64?.takeIf { System.currentTimeMillis() - capturedAt < 60_000 }

    /** The on-screen text if captured within the last minute, else null. */
    fun recentText(): String? =
        text?.takeIf { System.currentTimeMillis() - textAt < 60_000 }

    private fun encode(src: Bitmap): String {
        var bmp = src
        val max = 1200
        val w = bmp.width
        val h = bmp.height
        if (w > max || h > max) {
            val s = max.toFloat() / maxOf(w, h)
            bmp = Bitmap.createScaledBitmap(bmp, (w * s).toInt(), (h * s).toInt(), true)
        }
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }
}
