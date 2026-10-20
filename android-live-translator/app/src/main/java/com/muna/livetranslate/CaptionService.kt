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
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import kotlin.math.sqrt

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

    /** Spoken phrases waiting for recognition (16 kHz mono floats). */
    private val phrases = LinkedBlockingDeque<FloatArray>()
    private val recognised = Channel<WhisperEngine.Result>(Channel.UNLIMITED)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (projection != null) return START_NOT_STICKY

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_RESULT_DATA, Intent::class.java) }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(resultCode, data)?.also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() = stopSelf()
            }, mainHandler)
        }
        if (projection == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning = true
        val prefs = Prefs(this)
        overlay = SubtitleOverlay(this, prefs.textSize).also {
            it.show()
            it.showStatus("جارٍ التحضير…")
        }
        translator = ArabicTranslator { lang ->
            mainHandler.post { overlay?.showStatus("جارٍ تنزيل ترجمة ${languageName(lang)} (مرة واحدة)…") }
        }
        startTranslationPipeline()
        startListening(prefs.language)
        return START_NOT_STICKY
    }

    private fun startInForeground() {
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
            this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    private fun startListening(lang: SourceLanguage) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuffer, CHUNK_SAMPLES * 2 * 10))
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord failed", e)
            overlay?.showStatus("تعذّر التقاط الصوت")
            return
        }
        recorder = rec
        listening = true

        speechThread = Thread({
            val engine = WhisperEngine.load(ModelManager.speechModelFile(this))
            if (engine == null) {
                mainHandler.post { overlay?.showStatus("تعذّر تحميل نموذج التعرّف على الكلام") }
                return@Thread
            }
            try {
                rec.startRecording()
                mainHandler.post { overlay?.showStatus(getString(R.string.listening)) }
                captureThread = Thread({ captureLoop(rec) }, "audio-capture").apply { start() }
                recognitionLoop(engine, lang)
            } catch (e: Exception) {
                Log.e(TAG, "Recognition failed", e)
                mainHandler.post { overlay?.showStatus("حدث خطأ في التعرّف على الكلام") }
            } finally {
                engine.close()
            }
        }, "speech-recognition").apply { start() }
    }

    /** Splits the captured audio into phrases at pauses (simple energy-based voice detection). */
    private fun captureLoop(rec: AudioRecord) {
        val chunk = ShortArray(CHUNK_SAMPLES)
        val preRoll = ArrayDeque<FloatArray>()
        val phrase = ArrayList<FloatArray>()
        var phraseSamples = 0
        var speaking = false
        var silentChunks = 0
        var speechChunks = 0

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
                phrases.offerLast(out)
            }
            phrase.clear()
            phraseSamples = 0
            speechChunks = 0
            silentChunks = 0
        }

        while (listening) {
            val n = rec.read(chunk, 0, chunk.size)
            if (n <= 0) {
                if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_DEAD_OBJECT) break
                continue
            }
            val samples = FloatArray(n) { chunk[it] / 32768f }
            var energy = 0.0
            for (s in samples) energy += s * s
            val loud = sqrt(energy / n) > SPEECH_RMS

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

    private fun recognitionLoop(engine: WhisperEngine, lang: SourceLanguage) {
        while (listening) {
            val audio = phrases.pollFirst(200, TimeUnit.MILLISECONDS) ?: continue
            val result = engine.transcribe(audio, lang.code) ?: continue
            val text = cleanUp(result.text) ?: continue
            val detected = if (lang == SourceLanguage.AUTO) result.language else lang.code
            recognised.trySend(WhisperEngine.Result(detected, text))
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
            for (r in recognised) {
                val arabic = try {
                    translator?.translate(r.text, r.language) ?: r.text
                } catch (e: Exception) {
                    Log.w(TAG, "Translation failed", e)
                    r.text
                }
                withContext(Dispatchers.Main) { overlay?.showFinal(arabic) }
            }
        }
    }

    private fun languageName(mlKitLanguage: String) =
        Locale.forLanguageTag(mlKitLanguage).getDisplayLanguage(Locale.forLanguageTag("ar"))

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
        @Volatile var isRunning = false

        private const val TAG = "CaptionService"
        private const val CHANNEL_ID = "captions"
        private const val NOTIFICATION_ID = 1

        private const val SAMPLE_RATE = 16_000
        private const val CHUNK_SAMPLES = 1_600            // 100 ms
        private const val SPEECH_RMS = 0.012
        private const val PRE_ROLL_CHUNKS = 3              // keep 300 ms before speech starts
        private const val END_SILENCE_CHUNKS = 5           // 500 ms pause ends a phrase
        private const val MIN_SPEECH_CHUNKS = 4            // ignore blips under 400 ms
        private const val MAX_PHRASE_SAMPLES = SAMPLE_RATE * 6
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
