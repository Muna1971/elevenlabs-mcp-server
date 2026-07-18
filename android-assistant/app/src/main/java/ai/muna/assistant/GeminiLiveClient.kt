package ai.muna.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
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
    private val context: Context,
    private val apiKey: String,
    private val systemInstruction: String,
    private val onStatus: (String) -> Unit,
    private val onToolCall: (name: String, args: JSONObject) -> String,
    // Optional first request (e.g. what she said right after the wake word).
    private val opening: String? = null,
    // Optional screenshots (base64 JPEG) so Matrash can "see" the screen —
    // several frames let it understand a moving video.
    private val openingImages: List<String> = emptyList(),
    // Called once when the session ends (idle, closed, or failed). Used by the
    // hands-free wake service to resume listening for the next "مطراش".
    private val onEnded: (() -> Unit)? = null,
    // If > 0, the session auto-closes after this many ms with no reply from
    // Gemini (a conversation lull) — keeps the mic from staying hot forever.
    private val idleMs: Long = 0L,
    // false = don't open the microphone (used by the "test voice" button, which
    // only needs to hear Gemini speak the opening line).
    private val captureMic: Boolean = true
) {

    // No pingInterval: the live session streams audio constantly, which keeps the
    // socket alive. A control-frame ping race here previously killed good sessions.
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var ws: WebSocket? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    @Volatile private var running = false
    @Volatile private var ended = false
    @Volatile private var speaking = false
    @Volatile private var lastReply = 0L
    private val playQueue = LinkedBlockingQueue<ByteArray>()
    private val firstTurnLock = Any()
    private var setupDone = false
    private var pendingFirst: Pair<List<String>, String?>? = null

    fun start() {
        val req = Request.Builder()
            .url(WS_URL + apiKey)
            .addHeader("x-goog-api-key", apiKey)   // supports newer AQ.* keys
            .build()
        ws = http.newWebSocket(req, listener)
    }

    /** Send one or more screenshots AND the request together as one complete turn. */
    fun sendImagesWithText(images: List<String>, text: String) {
        val parts = JSONArray()
        for (b64 in images) {
            parts.put(JSONObject().put("inlineData", JSONObject()
                .put("mimeType", "image/jpeg").put("data", b64)))
        }
        parts.put(JSONObject().put("text", text))
        val turns = JSONArray().put(JSONObject().put("role", "user").put("parts", parts))
        ws?.send(JSONObject().put("clientContent",
            JSONObject().put("turns", turns).put("turnComplete", true)).toString())
    }

    /** Send a typed/opening request as a user turn (Gemini still replies with voice). */
    fun sendText(text: String) {
        val turns = JSONArray().put(
            JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", text)))
        )
        ws?.send(JSONObject().put("clientContent",
            JSONObject().put("turns", turns).put("turnComplete", true)).toString())
    }

    /**
     * Queue the first turn (her request + optional screenshots). If the session
     * is already connected it's sent now, otherwise on setupComplete. This lets
     * the caller connect the voice session immediately and capture the screen in
     * parallel — voice is never blocked by (or dependent on) screen capture.
     */
    fun pushFirstTurn(images: List<String>, text: String?) {
        synchronized(firstTurnLock) {
            if (setupDone) sendFirstTurn(images, text) else pendingFirst = images to text
        }
    }

    private fun sendFirstTurn(images: List<String>, text: String?) {
        when {
            images.isNotEmpty() -> sendImagesWithText(images,
                text?.takeIf { it.isNotBlank() }
                    ?: "صِف للمستخدمة ما تراه في هذي اللقطات من شاشتها.")
            !text.isNullOrBlank() -> sendText(text)
        }
    }

    fun stop() = endSession(notify = false)

    /** Release everything on ANY end path (also restores nothing to "call" mode). */
    private fun endSession(notify: Boolean) {
        val already = ended
        ended = true
        running = false
        runCatching { ws?.close(1000, "bye") }
        ws = null
        runCatching { record?.stop(); record?.release() }
        record = null
        runCatching { track?.stop(); track?.release() }
        track = null
        playQueue.clear()
        if (notify && !already) onEnded?.invoke()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            onStatus("متّصل — يجهّز…")
            webSocket.send(setupMessage().toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) =
            handle(bytes.utf8())

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            onStatus("تعذّر الاتصال: ${t.message ?: response?.code ?: ""}")
            running = false
            finish()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Gemini rejects a bad setup with a close frame (e.g. 1007) — surface it.
            if (code != 1000 && reason.isNotBlank()) onStatus("انقطع الاتصال: $reason")
            running = false
            finish()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            running = false
            finish()
        }
    }

    /** Auto-close after a lull so the mic doesn't stay open forever (wake mode). */
    private fun startIdleWatch() {
        Thread {
            while (running) {
                try { Thread.sleep(2500) } catch (e: InterruptedException) { return@Thread }
                if (running && System.currentTimeMillis() - lastReply > idleMs) {
                    onStatus("انتهت الجلسة")
                    finish()
                    return@Thread
                }
            }
        }.start()
    }

    /** Notify the owner exactly once that the session is over (and clean up). */
    private fun finish() = endSession(notify = true)

    private fun handle(text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        when {
            json.has("setupComplete") -> {
                running = true
                lastReply = System.currentTimeMillis()
                onStatus("أستمع إليك…")
                startPlayback()
                if (captureMic) startCapture()
                if (idleMs > 0) startIdleWatch()
                if (openingImages.isNotEmpty()) {
                    sendImagesWithText(openingImages,
                        opening?.takeIf { it.isNotBlank() }
                            ?: "صِف للمستخدمة ما تراه في هذي اللقطات من شاشتها.")
                } else if (!opening.isNullOrBlank()) {
                    sendText(opening)
                }
                synchronized(firstTurnLock) {
                    setupDone = true
                    pendingFirst?.let { sendFirstTurn(it.first, it.second); pendingFirst = null }
                }
            }
            json.has("serverContent") -> {
                lastReply = System.currentTimeMillis()
                val sc = json.getJSONObject("serverContent")
                if (sc.optBoolean("interrupted")) playQueue.clear()
                sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                    for (i in 0 until parts.length()) {
                        val inline = parts.getJSONObject(i).optJSONObject("inlineData")
                        val data = inline?.optString("data")
                        if (!data.isNullOrEmpty()) {
                            if (!speaking) { speaking = true; onStatus("🔊 يتكلّم مطراش…") }
                            playQueue.offer(Base64.decode(data, Base64.DEFAULT))
                        }
                    }
                }
                if (sc.optBoolean("turnComplete")) speaking = false
            }
            json.has("toolCall") -> {
                lastReply = System.currentTimeMillis()
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
        // Native-audio models auto-detect language and REJECT an explicit
        // languageCode (1007 close). Only pick the voice; Arabic comes from the
        // system instruction + what the user speaks.
        val speech = JSONObject()
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
                    // Plain MIC (not VOICE_COMMUNICATION) so the system does NOT
                    // enter "call" audio routing — that made other apps (WhatsApp
                    // voice notes) think a call was active, and sent our voice to
                    // the earpiece. Echo/noise are handled by the effects below.
                    MediaRecorder.AudioSource.MIC, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf.size * 2
                )
            } catch (e: SecurityException) { return@Thread }
            record = rec
            // Cancel the media echo so she's understood while music/video plays,
            // and the mic doesn't feed the playback back into Gemini.
            val sid = rec.audioSessionId
            runCatching {
                if (AcousticEchoCanceler.isAvailable())
                    AcousticEchoCanceler.create(sid)?.enabled = true
            }
            runCatching {
                if (NoiseSuppressor.isAvailable())
                    NoiseSuppressor.create(sid)?.enabled = true
            }
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
                // MEDIA usage plays on the loudspeaker (or Bluetooth) in normal
                // mode — audible, and without pretending to be a phone call.
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            AudioFormat.Builder().setSampleRate(24000)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            maxOf(minBuf * 4, 32768), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        track = t
        runCatching { t.setVolume(AudioTrack.getMaxVolume()) }
        t.play()
        Thread {
            while (running) {
                val data = runCatching { playQueue.poll(200, TimeUnit.MILLISECONDS) }.getOrNull()
                if (data != null) runCatching { t.write(data, 0, data.size) }
            }
        }.start()
    }

    companion object {
        // Native-audio model = the most expressive (sighs, tone, pauses).
        private const val MODEL = "gemini-2.5-flash-native-audio-latest"
        private const val WS_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key="
    }
}
