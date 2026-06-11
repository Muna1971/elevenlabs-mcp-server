package ai.muna.assistant

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The "voice": ElevenLabs text-to-speech.
 * Downloads MP3 audio for [text] and returns a temp file to play.
 */
class ElevenLabsClient(private val prefs: Prefs, private val cacheDir: File) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Reason for the last failed synthesis (for diagnostics), or null. */
    var lastError: String? = null
        private set

    fun hasKey(): Boolean = prefs.elevenKey.isNotBlank()

    /** Synchronous — call from a background dispatcher. Returns null on failure. */
    fun synthesize(text: String): File? {
        lastError = null
        val key = prefs.elevenKey
        if (key.isBlank() || text.isBlank()) { lastError = "لا يوجد مفتاح صوت"; return null }

        val body = JSONObject()
            .put("text", text)
            .put("model_id", "eleven_multilingual_v2")

        val voiceId = prefs.voiceId.ifBlank { Prefs.DEFAULT_VOICE }
        val request = Request.Builder()
            .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId")
            .header("xi-api-key", key)
            .header("accept", "audio/mpeg")
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()

        return try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string().orEmpty()
                    lastError = runCatching {
                        val detail = org.json.JSONObject(errBody).get("detail")
                        if (detail is org.json.JSONObject) detail.optString("message", detail.toString())
                        else detail.toString()
                    }.getOrNull()?.take(160) ?: "HTTP ${resp.code}"
                    return null
                }
                val bytes = resp.body?.bytes() ?: return null
                val out = File(cacheDir, "muna_reply.mp3")
                out.writeBytes(bytes)
                out
            }
        } catch (e: IOException) {
            lastError = e.message ?: "خطأ في الشبكة"
            null
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
