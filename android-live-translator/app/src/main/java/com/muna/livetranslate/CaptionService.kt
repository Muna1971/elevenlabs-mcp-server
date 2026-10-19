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
import com.google.mlkit.nl.translate.Translator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Captures the audio other apps are playing, recognises speech offline (Vosk)
 * and shows an Arabic translation (ML Kit, offline) in a floating overlay.
 */
class CaptionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var translator: Translator? = null
    private var overlay: SubtitleOverlay? = null
    private var worker: Thread? = null
    @Volatile private var listening = false

    private val finals = Channel<String>(Channel.UNLIMITED)
    private val partial = MutableStateFlow("")
    @Volatile private var segment = 0

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
        val lang = prefs.language
        overlay = SubtitleOverlay(this, prefs.textSize).also {
            it.show()
            it.showStatus("جارٍ التحضير…")
        }
        translator = ModelManager.newArabicTranslator(lang)
        startTranslationPipeline()
        startRecognition(lang)
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

    private fun startRecognition(lang: SourceLanguage) {
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
                .setBufferSizeInBytes(maxOf(minBuffer, CHUNK_BYTES * 8))
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord failed", e)
            overlay?.showStatus("تعذّر التقاط الصوت")
            return
        }
        recorder = rec
        listening = true

        worker = Thread({
            try {
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                val m = Model(ModelManager.speechModelDir(this, lang).absolutePath)
                val r = Recognizer(m, SAMPLE_RATE.toFloat())
                model = m
                recognizer = r
                rec.startRecording()
                mainHandler.post { overlay?.showStatus(getString(R.string.listening)) }
                recognitionLoop(rec, r)
            } catch (e: Exception) {
                Log.e(TAG, "Recognition failed", e)
                mainHandler.post { overlay?.showStatus("حدث خطأ في التعرّف على الكلام") }
            }
        }, "speech-recognition").apply { start() }
    }

    private fun recognitionLoop(rec: AudioRecord, r: Recognizer) {
        val buf = ByteArray(CHUNK_BYTES)
        var lastPartial = ""
        while (listening) {
            val n = rec.read(buf, 0, buf.size)
            if (n <= 0) {
                if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_DEAD_OBJECT) break
                continue
            }
            if (r.acceptWaveForm(buf, n)) {
                lastPartial = ""
                onFinal(JSONObject(r.result).optString("text"))
            } else {
                val p = JSONObject(r.partialResult).optString("partial").trim()
                if (p.length > MAX_SEGMENT_CHARS) {
                    // Long monologue without a pause: cut it so the subtitle stays readable.
                    lastPartial = ""
                    onFinal(JSONObject(r.finalResult).optString("text"))
                } else if (p != lastPartial) {
                    lastPartial = p
                    partial.value = p
                }
            }
        }
    }

    private fun onFinal(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        segment++
        partial.value = ""
        finals.trySend(t)
    }

    private fun startTranslationPipeline() {
        scope.launch {
            for (text in finals) {
                val arabic = translate(text)
                withContext(Dispatchers.Main) { overlay?.showFinal(arabic) }
            }
        }
        scope.launch {
            partial.collect { text ->
                if (text.isBlank()) return@collect
                val seg = segment
                val arabic = translate(text)
                if (seg == segment && arabic.isNotBlank()) {
                    withContext(Dispatchers.Main) { overlay?.showLive(arabic) }
                }
                delay(PARTIAL_THROTTLE_MS)
            }
        }
    }

    private suspend fun translate(text: String): String = try {
        translator?.translate(text)?.await().orEmpty()
    } catch (e: Exception) {
        Log.w(TAG, "Translation failed", e)
        ""
    }

    override fun onDestroy() {
        isRunning = false
        listening = false
        scope.cancel()
        finals.close()
        runCatching { recorder?.stop() }
        worker?.join(1_000)
        runCatching { recorder?.release() }
        runCatching { recognizer?.close() }
        runCatching { model?.close() }
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
        private const val CHUNK_BYTES = 3_200 // 100 ms of 16-bit mono audio
        private const val MAX_SEGMENT_CHARS = 160
        private const val PARTIAL_THROTTLE_MS = 250L
    }
}
