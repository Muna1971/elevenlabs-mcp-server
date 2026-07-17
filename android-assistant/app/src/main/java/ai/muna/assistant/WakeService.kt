package ai.muna.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Always-listening "call her name" service. Keeps the microphone open in a
 * foreground service; when it hears the wake word ("منى"), it treats the rest
 * of the sentence as the request, answers with Claude (or a device command),
 * and speaks the reply — fully hands-free (e.g. while driving).
 */
class WakeService : Service() {

    private lateinit var prefs: Prefs
    private lateinit var claude: ClaudeClient
    private lateinit var eleven: ElevenLabsClient
    private val convo = mutableListOf<Message>()

    private var recognizer: SpeechRecognizer? = null
    private var player: MediaPlayer? = null
    private lateinit var androidTts: AndroidTts
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var awaitingCommand = false
    private var awaitingRetries = 0
    private var working = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        claude = ClaudeClient(prefs)
        eleven = ElevenLabsClient(prefs, cacheDir)
        androidTts = AndroidTts(this)
        startAsForeground()
        // Non-robotic "ready" cue (two soft beeps) — no synthetic voice.
        beep()
        main.postDelayed({ beep() }, 350)
        main.postDelayed({ startListening() }, 950)
        if (prefs.geminiKey.isBlank()) {
            toast("⚠️ احفظي مفتاح Gemini في الإعدادات ليردّ مطراش بصوته المعبّر")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_RESUME) { resumeAfterLive(); return START_STICKY }
        return START_STICKY
    }

    private fun startAsForeground() {
        val channelId = "muna_wake"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "وضع النداء", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = Notification.Builder(this, channelId)
            .setContentTitle("مطراش يستمع لندائك")
            .setContentText("قل «مطراش» متبوعةً بطلبك")
            .setSmallIcon(R.drawable.ic_mic_dark)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "إيقاف", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notif)
        }
    }

    // ---- Listening loop ----

    private fun startListening() {
        if (working) return
        main.post {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) return@post
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(listener)
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-AE")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar-AE")
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L)
            }
            runCatching { recognizer?.startListening(intent) }
        }
    }

    private fun restartSoon(delay: Long = 600) {
        if (!working) main.postDelayed({ startListening() }, delay)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            // While waiting for her command, retry a few times before giving up.
            if (awaitingCommand) { awaitRetry(); return }
            restartSoon()
        }
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            handleUtterance(text)
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun toast(msg: String) = main.post {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun handleUtterance(text: String) {
        if (text.isEmpty()) { if (awaitingCommand) awaitRetry() else restartSoon(); return }
        toast("👂 سمعت: $text")
        if (awaitingCommand) {
            awaitingCommand = false
            awaitingRetries = 0
            process(text)
            return
        }
        val rest = afterWake(text)
        if (rest == null) {
            // Not addressed to her — keep listening.
            restartSoon()
            return
        }
        // Wake mode always uses the live, expressive Gemini voice — never the
        // robotic on-device pipeline. If the key isn't saved, say so plainly.
        if (prefs.geminiKey.isNotBlank()) {
            goLive(rest)
        } else {
            toast("⚠️ احفظي مفتاح Gemini في الإعدادات ثم أعيدي تفعيل وضع النداء")
            restartSoon()
        }
    }

    /**
     * Wake word heard → open the Gemini Live session (expressive, hands-free).
     * We release our own recognizer so the live client can take the mic, and
     * resume wake-listening when the live screen closes (ACTION_RESUME).
     */
    private fun goLive(opening: String) {
        working = true
        awaitingCommand = false
        awaitingRetries = 0
        toast("🔴 مطراش المباشر (Gemini)…")
        beep()
        main.post { recognizer?.destroy(); recognizer = null }
        stopPlayback()
        val i = Intent(this, LiveActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (opening.isNotBlank()) i.putExtra(LiveActivity.EXTRA_OPENING, opening)
        runCatching { startActivity(i) }
    }

    private fun resumeAfterLive() {
        working = false
        awaitingCommand = false
        awaitingRetries = 0
        restartSoon(400)
    }

    /** No command captured yet while awaiting — give her a couple more tries. */
    private fun awaitRetry() {
        awaitingRetries++
        if (awaitingRetries <= 2) {
            startListening()
        } else {
            awaitingCommand = false
            awaitingRetries = 0
            restartSoon()
        }
    }

    private fun beep() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            main.postDelayed({ runCatching { tone.release() } }, 400)
        }
    }

    /**
     * Returns the request after the wake word, "" if only the name was said,
     * or null if she wasn't addressing Muna at all.
     */
    private fun afterWake(text: String): String? {
        val tokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        var lastWake = -1
        for (i in tokens.indices) {
            if (isWakeToken(norm(tokens[i]))) lastWake = i
        }
        if (lastWake == -1) return null
        return tokens.drop(lastWake + 1).joinToString(" ").trim()
    }

    private fun isWakeToken(n: String): Boolean =
        n.contains("مطراش") || n.contains("مطرش") || n.contains("متراش") ||
            n in WAKE_TOKENS || n.contains("matrash") || n.contains("mutrash")

    private fun process(text: String) {
        if (prefs.anthropicKey.isBlank()) { speak("لم يُضبط مفتاح الذكاء بعد."); return }
        working = true
        convo.add(Message("user", text))
        if (convo.size > 12) convo.subList(0, convo.size - 12).clear()
        val executor = ClaudeClient.ToolExecutor { name, input ->
            val r = Commands.exec(this, name, input)
            toast("🔧 $name → $r")
            r
        }
        io.execute {
            val reply = try { claude.complete(convo, null, executor) } catch (e: Exception) {
                "تعذّر الاتصال: ${e.message ?: ""}"
            }
            convo.add(Message("assistant", reply))
            main.post {
                working = false
                // Keep the conversation going — her next reply needs no wake word.
                awaitingCommand = true
                speak(reply)
            }
        }
    }

    private val audio by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private var focusReq: AudioFocusRequest? = null
    private val speechAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun grabFocus() {
        if (focusReq != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(speechAttrs).build()
        runCatching { audio.requestAudioFocus(req) }
        focusReq = req
    }

    private fun dropFocus() {
        focusReq?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
        focusReq = null
    }

    private fun speak(text: String) {
        if (!prefs.speakReplies || text.isBlank()) { restartSoon(); return }
        working = true
        io.execute {
            val file = if (eleven.hasKey()) eleven.synthesize(text) else null
            main.post {
                grabFocus()
                if (file == null) {
                    // ElevenLabs unavailable → free Android voice.
                    androidTts.speak(text) { dropFocus(); working = false; restartSoon(300) }
                    return@post
                }
                stopPlayback()
                player = MediaPlayer().apply {
                    setAudioAttributes(speechAttrs)
                    setDataSource(file.absolutePath)
                    setOnCompletionListener {
                        it.release(); if (player === it) player = null
                        dropFocus(); working = false; restartSoon(300)
                    }
                    setOnPreparedListener { it.start() }
                    prepareAsync()
                }
            }
        }
    }

    private fun stopPlayback() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    private fun norm(s: String): String =
        s.lowercase().trim()
            .replace(Regex("[ً-ْـ]"), "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
            .replace('ى', 'ي').replace('ة', 'ه')

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
        recognizer?.destroy()
        androidTts.shutdown()
        io.shutdownNow()
    }

    companion object {
        const val ACTION_STOP = "ai.muna.assistant.STOP_WAKE"
        const val ACTION_RESUME = "ai.muna.assistant.RESUME_WAKE"
        // Name variants the recognizer may produce for "مطراش".
        private val WAKE_TOKENS = setOf(
            "مطراش", "مطرش", "متراش", "مطراج", "مطروش", "مطرااش"
        )
    }
}
