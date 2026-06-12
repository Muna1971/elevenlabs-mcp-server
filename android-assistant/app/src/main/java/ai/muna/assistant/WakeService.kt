package ai.muna.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var awaitingCommand = false
    private var working = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        claude = ClaudeClient(prefs)
        eleven = ElevenLabsClient(prefs, cacheDir)
        startAsForeground()
        startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
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
            .setContentTitle("منى تستمع لندائك")
            .setContentText("قولي «منى» متبوعةً بطلبك")
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
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
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
        override fun onError(error: Int) { restartSoon() }
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            handleUtterance(text)
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun handleUtterance(text: String) {
        if (text.isEmpty()) { restartSoon(); return }
        if (awaitingCommand) {
            awaitingCommand = false
            process(text)
            return
        }
        if (norm(text).contains("مني")) {
            val rest = afterWake(text)
            if (rest.isBlank()) {
                awaitingCommand = true
                speak("نعم، تفضّلي.")
            } else {
                process(rest)
            }
        } else {
            restartSoon()
        }
    }

    private fun process(text: String) {
        // Device command first
        Commands.handle(this, text)?.let { speak(it); return }
        // Otherwise ask Claude
        if (prefs.anthropicKey.isBlank()) { speak("لم يُضبط مفتاح الذكاء بعد."); return }
        working = true
        convo.add(Message("user", text))
        if (convo.size > 12) convo.subList(0, convo.size - 12).clear()
        io.execute {
            val reply = try { claude.complete(convo) } catch (e: Exception) {
                "تعذّر الاتصال."
            }
            convo.add(Message("assistant", reply))
            main.post { working = false; speak(reply) }
        }
    }

    private fun speak(text: String) {
        if (!prefs.speakReplies || !eleven.hasKey() || text.isBlank()) { restartSoon(); return }
        working = true
        io.execute {
            val file = eleven.synthesize(text)
            main.post {
                if (file == null) { working = false; restartSoon(); return@post }
                stopPlayback()
                player = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    setOnCompletionListener {
                        it.release(); if (player === it) player = null
                        working = false; restartSoon(300)
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

    private fun afterWake(text: String): String {
        val tokens = text.split(Regex("\\s+"))
        val idx = tokens.indexOfFirst { norm(it).contains("مني") }
        if (idx == -1) return ""
        var rest = tokens.drop(idx + 1)
        while (rest.isNotEmpty() && norm(rest.first()) in listOf("الذكيه", "ذكيه", "الكندي", "كندي"))
            rest = rest.drop(1)
        return rest.joinToString(" ").trim()
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
        io.shutdownNow()
    }

    companion object {
        const val ACTION_STOP = "ai.muna.assistant.STOP_WAKE"
    }
}
