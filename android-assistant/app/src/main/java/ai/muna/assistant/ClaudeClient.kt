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
 * The "brain": Anthropic Messages API (Claude Opus 4.8) with tool use.
 * Called over raw HTTPS — the standard, lightweight approach for an Android app.
 */
class ClaudeClient(private val prefs: Prefs) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** A file attached to the final user turn. kind = "image" or "document". */
    data class Attachment(val kind: String, val data: String, val mime: String)

    /** Executes a device tool call and returns a short result string. */
    fun interface ToolExecutor {
        fun run(name: String, input: JSONObject): String
    }

    @Throws(IOException::class)
    fun complete(
        history: List<Message>,
        attachment: Attachment? = null,
        executor: ToolExecutor? = null
    ): String {
        val key = prefs.anthropicKey
        if (key.isBlank()) throw IOException("MISSING_ANTHROPIC_KEY")

        // Build the initial message list from the conversation history.
        val messages = JSONArray()
        val lastIndex = history.size - 1
        for ((i, m) in history.withIndex()) {
            if (i == lastIndex && m.role == "user" && attachment != null) {
                val source = JSONObject().put("type", "base64")
                    .put("media_type", attachment.mime).put("data", attachment.data)
                val block = JSONObject()
                    .put("type", if (attachment.kind == "image") "image" else "document")
                    .put("source", source)
                val textBlock = JSONObject().put("type", "text").put("text", m.text)
                messages.put(JSONObject().put("role", m.role)
                    .put("content", JSONArray().put(block).put(textBlock)))
            } else {
                messages.put(JSONObject().put("role", m.role).put("content", m.text))
            }
        }

        var guard = 0
        while (true) {
            val body = JSONObject()
                .put("model", MODEL)
                .put("max_tokens", 1024)
                .put("system", prefs.systemPrompt())
                .put("messages", messages)
            if (executor != null) body.put("tools", Commands.toolsJson())

            val json = post(key, body)
            if (json.optString("stop_reason") == "refusal") {
                return "أعتذر، لا أستطيع المساعدة في هذا الطلب."
            }
            val content = json.optJSONArray("content") ?: return ""

            if (json.optString("stop_reason") == "tool_use" && executor != null && guard++ < 5) {
                // Echo the assistant turn (text + tool_use) back unchanged.
                messages.put(JSONObject().put("role", "assistant").put("content", content))
                // Execute each tool and return the results.
                val results = JSONArray()
                for (i in 0 until content.length()) {
                    val block = content.getJSONObject(i)
                    if (block.optString("type") == "tool_use") {
                        val result = try {
                            executor.run(block.optString("name"), block.optJSONObject("input") ?: JSONObject())
                        } catch (e: Exception) {
                            "تعذّر تنفيذ الإجراء."
                        }
                        results.put(
                            JSONObject().put("type", "tool_result")
                                .put("tool_use_id", block.optString("id"))
                                .put("content", result)
                        )
                    }
                }
                messages.put(JSONObject().put("role", "user").put("content", results))
                continue
            }

            // Final answer — collect the text blocks.
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") sb.append(block.optString("text"))
            }
            return sb.toString().trim()
        }
    }

    @Throws(IOException::class)
    private fun post(key: String, body: JSONObject): JSONObject {
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
            return JSONObject(raw)
        }
    }

    companion object {
        private const val MODEL = "claude-opus-4-8"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
