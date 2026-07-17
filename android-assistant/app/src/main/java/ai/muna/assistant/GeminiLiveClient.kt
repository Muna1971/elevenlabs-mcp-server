package ai.muna.assistant

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Real-time voice conversation with Google Gemini Live (bidirectional audio
 * over WebSocket). Streams the mic to Gemini and plays its expressive speech,
 * while still letting Matrash control the phone via function calls.
 */
class GeminiLiveClient(
    private val apiKey: String,
    private val systemInstruction: String,
    private val onStatus: (String) -> Unit,
    private val onToolCall: (name: String, args: JSONObject) -> String
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var ws: WebSocket? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    @Volatile private var running = false
    private val playQueue = LinkedBlockingQueue<ByteArray>()

    fun start() {
        val req = Request.Builder().url(WS_URL + apiKey).build()
        ws = http.newWebSocket(req, listener)
    }

    fun stop() {
        running = false
        runCatching { ws?.close(1000, "bye") }
        ws = null
        runCatching { record?.stop(); record?.release() }
        record = null
        runCatching { track?.stop(); track?.release() }
        track = null
        playQueue.clear()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(setupMessage().toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) =
            handle(bytes.utf8())

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            onStatus("تعذّر الاتصال: ${t.message ?: response?.code ?: ""}")
            running = false
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            running = false
        }
    }

    private fun handle(text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        when {
            json.has("setupComplete") -> {
                running = true
                onStatus("أستمع إليك…")
                startPlayback()
                startCapture()
            }
            json.has("serverContent") -> {
                val sc = json.getJSONObject("serverContent")
                if (sc.optBoolean("interrupted")) playQueue.clear()
                sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                    for (i in 0 until parts.length()) {
                        val inline = parts.getJSONObject(i).optJSONObject("inlineData")
                        val data = inline?.optString("data")
                        if (!data.isNullOrEmpty()) {
                            playQueue.offer(Base64.decode(data, Base64.DEFAULT))
                        }
                    }
                }
            }
            json.has("toolCall") -> {
                val calls = json.getJSONObject("toolCall").optJSONArray("functionCalls") ?: return
                val responses = JSONArray()
                for (i in 0 until calls.length()) {
                    val c = calls.getJSONObject(i)
                    val result = runCatching {
                        onToolCall(c.optString("name"), c.optJSONObject("args") ?: JSONObject())
                    }.getOrDefault("تعذّر التنفيذ")
                    responses.put(
                        JSONObject().put("id", c.optString("id")).put("name", c.optString("name"))
                            .put("response", JSONObject().put("result", result))
                    )
                }
                ws?.send(JSONObject().put("toolResponse",
                    JSONObject().put("functionResponses", responses)).toString())
            }
        }
    }

    private fun setupMessage(): JSONObject {
        val speech = JSONObject()
            .put("languageCode", "ar-XA")
            .put("voiceConfig", JSONObject().put("prebuiltVoiceConfig",
                JSONObject().put("voiceName", "Charon")))
        val genConfig = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", speech)
        val setup = JSONObject()
            .put("model", "models/$MODEL")
            .put("generationConfig", genConfig)
            .put("systemInstruction", JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", systemInstruction))))
            .put("tools", JSONArray().put(JSONObject()
                .put("functionDeclarations", Commands.geminiTools())))
        return JSONObject().put("setup", setup)
    }

    // ---- Mic capture (16 kHz PCM16 -> Gemini) ----

    private fun startCapture() {
        Thread {
            val minBuf = AudioRecord.getMinBufferSize(
                16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val buf = ByteArray(maxOf(minBuf, 3200))
            val rec = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf.size * 2
                )
            } catch (e: SecurityException) { return@Thread }
            record = rec
            runCatching { rec.startRecording() }
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    val chunk = if (n == buf.size) buf else buf.copyOf(n)
                    val b64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
                    val msg = JSONObject().put("realtimeInput", JSONObject().put("mediaChunks",
                        JSONArray().put(JSONObject()
                            .put("mimeType", "audio/pcm;rate=16000").put("data", b64))))
                    ws?.send(msg.toString())
                }
            }
        }.start()
    }

    // ---- Playback (24 kHz PCM16 from Gemini) ----

    private fun startPlayback() {
        val minBuf = AudioTrack.getMinBufferSize(
            24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val t = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            AudioFormat.Builder().setSampleRate(24000)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            maxOf(minBuf, 8192), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        track = t
        t.play()
        Thread {
            while (running) {
                val data = runCatching { playQueue.poll(200, TimeUnit.MILLISECONDS) }.getOrNull()
                if (data != null) runCatching { t.write(data, 0, data.size) }
            }
        }.start()
    }

    companion object {
        // Native-audio dialog model = the most expressive (sighs, tone, pauses).
        private const val MODEL = "gemini-2.5-flash-preview-native-audio-dialog"
        private const val WS_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key="
    }
}
