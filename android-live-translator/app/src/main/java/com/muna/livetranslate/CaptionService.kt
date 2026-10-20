package com.muna.livetranslate

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Captures the audio other apps are playing, cuts it into spoken phrases,
 * recognises each phrase offline with Whisper (which also detects the language)
 * and shows an Arabic translation (ML Kit, offline) in a floating overlay.
 */
class CaptionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var translator: ArabicTranslator? = null
    private var overlay: SubtitleOverlay? = null
    private var captureThread: Thread? = null
    private var speechThread: Thread? = null
    @Volatile private var listening = false

    /** A spoken phrase (16 kHz mono floats) and the "scene" (video) it belongs to. */
    private class Phrase(val audio: FloatArray, val scene: Int, val endedAt: Long)
    private class Caption(val language: String, val text: String, val scene: Int, val endedAt: Long)

    private val phrases = LinkedBlockingDeque<Phrase>()
    private val recognised = Channel<Caption>(Channel.UNLIMITED)

    /** Bumped when a new video seems to start, so nothing from the previous one is shown. */
    @Volatile private var scene = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (isRunning) return START_NOT_STICKY
        val useMic = intent?.action == ACTION_START_MIC
        startInForeground(useMic)
        if (!useMic && !obtainProjection(intent)) {
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning = true
        val prefs = Prefs(this)
        overlay = SubtitleOverlay(this, prefs.textSize).also {
            it.show()
            it.showStatus("جارٍ التحضير…")
        }
        translator = ArabicTranslator(this, scope) { message ->
            mainHandler.post { overlay?.showStatus(message) }
        }
        startTranslationPipeline()
        startListening(prefs)
        return START_NOT_STICKY
    }

    private fun obtainProjection(intent: Intent?): Boolean {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_RESULT_DATA, Intent::class.java) }
        if (data == null) return false
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(resultCode, data)?.also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() = stopSelf()
            }, mainHandler)
        }
        return projection != null
    }

    private fun startInForeground(useMic: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, CaptionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openIntent = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_subtitles)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notif_stop), stopIntent)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (useMic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
    }

    private fun startListening(prefs: Prefs) {
        val lang = prefs.language
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = try {
            val builder = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuffer, CHUNK_SAMPLES * 2 * 10))
            val mediaProjection = projection
            if (mediaProjection != null) {
                // Internal audio of other apps (needs the screen-sharing permission; no video is captured).
                builder.setAudioPlaybackCaptureConfig(
                    AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build()
                )
            } else {
                // Microphone mode: hears the video through the phone's speaker.
                builder.setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            builder.build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord failed", e)
            overlay?.showStatus("تعذّر التقاط الصوت")
            return
        }
        recorder = rec
        listening = true

        speechThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val engine = WhisperEngine.load(ModelManager.speechModelFile(this, prefs.accurate))
            if (engine == null) {
                mainHandler.post { overlay?.showStatus("تعذّر تحميل نموذج التعرّف على الكلام") }
                return@Thread
            }
            try {
                rec.startRecording()
                mainHandler.post { overlay?.showStatus(getString(R.string.listening)) }
                val vad = VoiceDetector.load(ModelManager.vadModelFile(this))
                    ?: throw IllegalStateException("VAD model failed to load")
                captureThread = Thread({
                    try {
                        captureLoop(rec, vad)
                    } finally {
                        vad.close()
                    }
                }, "audio-capture").apply { start() }
                recognitionLoop(engine, lang)
            } catch (e: Exception) {
                Log.e(TAG, "Recognition failed", e)
                mainHandler.post { overlay?.showStatus("حدث خطأ في التعرّف على الكلام") }
            } finally {
                engine.close()
            }
        }, "speech-recognition").apply { start() }
    }

    /** Splits the captured audio into spoken phrases using Silero voice detection. */
    private fun captureLoop(rec: AudioRecord, vad: VoiceDetector) {
        val chunk = ShortArray(CHUNK_SAMPLES)
        val preRoll = ArrayDeque<FloatArray>()
        val phrase = ArrayList<FloatArray>()
        var phraseSamples = 0
        var speaking = false
        var silentChunks = 0
        var speechChunks = 0
        var quietChunks = 0      // consecutive chunks with no sound at all
        var noSpeechChunks = 0   // consecutive chunks without speech
        var sceneCleared = true

        fun flush() {
            if (speechChunks >= MIN_SPEECH_CHUNKS) {
                val out = FloatArray(maxOf(phraseSamples, MIN_PHRASE_SAMPLES))
                var pos = 0
                for (part in phrase) {
                    part.copyInto(out, pos)
                    pos += part.size
                }
                // Keep latency low: if recognition falls behind, drop the oldest phrase.
                while (phrases.size >= MAX_QUEUED_PHRASES) phrases.pollFirst()
                phrases.offerLast(Phrase(out, scene, SystemClock.elapsedRealtime()))
            }
            phrase.clear()
            phraseSamples = 0
            speechChunks = 0
            silentChunks = 0
        }

        while (listening) {
            val n = rec.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
            if (n <= 0) {
                if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_DEAD_OBJECT) break
                continue
            }
            val samples = FloatArray(n) { chunk[it] / 32768f }
            var peak = 0f
            for (v in samples) if (abs(v) > peak) peak = abs(v)
            // Nothing playing (digital silence): skip the neural voice detector to save battery.
            val loud = peak > SILENCE_PEAK && vad.speechProbability(samples) > SPEECH_PROBABILITY

            quietChunks = if (peak <= SILENCE_PEAK) quietChunks + 1 else 0
            noSpeechChunks = if (loud) 0 else noSpeechChunks + 1
            if (loud) sceneCleared = false
            // A moment of total silence (switching videos) or a long stretch without speech:
            // treat what follows as a new video.
            if (!sceneCleared && !speaking &&
                (quietChunks >= SCENE_SILENCE_CHUNKS || noSpeechChunks >= SCENE_NO_SPEECH_CHUNKS)
            ) {
                sceneCleared = true
                startNewScene(vad)
            }

            if (!speaking) {
                preRoll.addLast(samples)
                if (preRoll.size > PRE_ROLL_CHUNKS) preRoll.removeFirst()
                if (!loud) continue
                speaking = true
                for (p in preRoll) {
                    phrase.add(p)
                    phraseSamples += p.size
                }
                preRoll.clear()
                speechChunks = 1
                continue
            }

            phrase.add(samples)
            phraseSamples += samples.size
            if (loud) {
                speechChunks++
                silentChunks = 0
            } else {
                silentChunks++
            }
            if (silentChunks >= END_SILENCE_CHUNKS) {
                flush()
                speaking = false
            } else if (phraseSamples >= MAX_PHRASE_SAMPLES) {
                // Continuous speech or music: cut here and keep listening.
                flush()
            }
        }
    }

    private fun startNewScene(vad: VoiceDetector) {
        scene++
        phrases.clear()
        vad.reset()
        mainHandler.post { overlay?.clear() }
    }

    /**
     * Language detection costs a second full encoder pass per phrase, so once the same
     * language was detected twice in a row it is locked in and only re-checked now and then.
     */
    private fun recognitionLoop(engine: WhisperEngine, lang: SourceLanguage) {
        var locked: String? = null
        var lastDetected = ""
        var streak = 0
        var sinceCheck = 0
        var currentScene = scene
        while (listening) {
            val phrase = phrases.pollFirst(200, TimeUnit.MILLISECONDS) ?: continue
            if (phrase.scene != scene || isStale(phrase.endedAt)) continue
            if (phrase.scene != currentScene) {
                // New video: its language may differ, detect it again.
                currentScene = phrase.scene
                locked = null
                streak = 0
            }
            val audio = phrase.audio
            val request = when {
                lang != SourceLanguage.AUTO -> lang.code
                locked != null && sinceCheck < RECHECK_LANGUAGE_EVERY -> locked
                else -> "auto"
            }
            val result = engine.transcribe(audio, request) ?: continue
            val detected = if (request == "auto") result.language else request
            if (request == "auto") {
                sinceCheck = 0
                streak = if (detected == lastDetected) streak + 1 else 1
                lastDetected = detected
                locked = if (detected.isNotEmpty() && (detected == locked || streak >= 2)) detected else null
            } else {
                sinceCheck++
            }
            val text = cleanUp(result.text) ?: continue
            recognised.trySend(Caption(detected, text, phrase.scene, phrase.endedAt))
        }
    }

    /** Removes sound tags such as "[Music]" and Whisper's typical silence hallucinations. */
    private fun cleanUp(raw: String): String? {
        val text = raw.replace(SOUND_TAGS, " ").replace(Regex("\\s+"), " ").trim()
        if (text.length < 2 || text.none { it.isLetter() }) return null
        val lower = text.lowercase(Locale.ROOT)
        if (HALLUCINATIONS.any { it in lower }) return null
        return text
    }

    private fun startTranslationPipeline() {
        scope.launch {
            for (c in recognised) {
                if (c.scene != scene || isStale(c.endedAt)) continue
                val arabic = translator?.translate(c.text, c.language) ?: c.text
                if (c.scene != scene || isStale(c.endedAt)) continue
                withContext(Dispatchers.Main) { overlay?.showFinal(arabic) }
            }
        }
    }

    /** Subtitles arriving this long after the words were spoken are out of sync: drop them. */
    private fun isStale(endedAt: Long) = SystemClock.elapsedRealtime() - endedAt > MAX_CAPTION_AGE_MS

    override fun onDestroy() {
        isRunning = false
        listening = false
        scope.cancel()
        recognised.close()
        runCatching { recorder?.stop() }
        captureThread?.join(1_000)
        speechThread?.join(3_000)
        runCatching { recorder?.release() }
        translator?.close()
        overlay?.remove()
        projection?.stop()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_STOP = "com.muna.livetranslate.STOP"
        const val ACTION_START_MIC = "com.muna.livetranslate.START_MIC"
        @Volatile var isRunning = false

        private const val TAG = "CaptionService"
        private const val CHANNEL_ID = "captions"
        private const val NOTIFICATION_ID = 1

        private const val SAMPLE_RATE = 16_000
        private const val CHUNK_SAMPLES = VoiceDetector.WINDOW * 3 // 96 ms
        private const val SPEECH_PROBABILITY = 0.5f
        private const val SILENCE_PEAK = 0.003f
        private const val PRE_ROLL_CHUNKS = 3              // keep ~300 ms before speech starts
        private const val END_SILENCE_CHUNKS = 4           // ~400 ms pause ends a phrase
        private const val MIN_SPEECH_CHUNKS = 3            // ignore blips under ~300 ms
        private const val MAX_PHRASE_SAMPLES = SAMPLE_RATE * 4
        private const val RECHECK_LANGUAGE_EVERY = 5
        private const val SCENE_SILENCE_CHUNKS = 10        // ~1 s of total silence
        private const val SCENE_NO_SPEECH_CHUNKS = 52      // ~5 s without speech
        private const val MAX_CAPTION_AGE_MS = 6_000L
        private const val MIN_PHRASE_SAMPLES = SAMPLE_RATE * 11 / 10 // Whisper needs >= 1 s
        private const val MAX_QUEUED_PHRASES = 2

        private val SOUND_TAGS = Regex("\\[[^\\]]*\\]|\\([^)]*\\)|\\*[^*]*\\*|[♪♫]")
        private val HALLUCINATIONS = listOf(
            "thank you for watching", "thanks for watching", "please subscribe",
            "subtitles by", "amara.org", "sous-titres", "untertitel",
            "اشتركوا في القناة", "ترجمة نانسي", "شكرا للمشاهدة", "شكراً للمشاهدة",
        )
    }
}
