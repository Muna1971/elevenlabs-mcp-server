package ai.muna.assistant

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The "brain": Anthropic Messages API (Claude Opus 4.8).
 * Called over raw HTTPS — the standard, lightweight approach for an Android app.
 */
class ClaudeClient(private val prefs: Prefs) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Sends the full conversation [history] and returns the assistant's reply text.
     * Runs synchronously — call from a background dispatcher.
     */
    @Throws(IOException::class)
    fun complete(history: List<Message>): String {
        val key = prefs.anthropicKey
        if (key.isBlank()) throw IOException("MISSING_ANTHROPIC_KEY")

        val messages = JSONArray()
        for (m in history) {
            messages.put(JSONObject().put("role", m.role).put("content", m.text))
        }

        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 1024)
            .put("system", prefs.systemPrompt())
            .put("messages", messages)

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()

        http.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching {
                    JSONObject(raw).getJSONObject("error").getString("message")
                }.getOrNull() ?: "HTTP ${resp.code}"
                throw IOException(msg)
            }
            val json = JSONObject(raw)
            // Refusal handling (relevant for the latest models).
            if (json.optString("stop_reason") == "refusal") {
                return "أعتذر، لا أستطيع المساعدة في هذا الطلب."
            }
            val content = json.optJSONArray("content") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") sb.append(block.optString("text"))
            }
            return sb.toString().trim()
        }
    }

    companion object {
        private const val MODEL = "claude-opus-4-8"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
