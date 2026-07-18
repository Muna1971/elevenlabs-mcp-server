package ai.muna.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Holds a screen-capture grant so Matrash can read ANY app — photos, videos,
 * social media — which the assist API can't. The screen is captured ONLY in a
 * brief on-demand burst (VirtualDisplay is created then released each time), so
 * there is no continuous mirroring, no battery drain, and no audio conflict.
 */
class ScreenProjectionService : Service() {

    private var projection: MediaProjection? = null
    private var w = 0
    private var h = 0
    private var dpi = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            cleanup(); stopSelf(); return START_NOT_STICKY
        }
        startAsForeground()
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        if (code != 0 && data != null) runCatching { setup(code, data) }
        return START_STICKY
    }

    private fun setup(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val metrics = resources.displayMetrics
        w = metrics.widthPixels
        h = metrics.heightPixels
        dpi = metrics.densityDpi
        projection = mpm.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { cleanup() }
        }, Handler(Looper.getMainLooper()))
        instance = this
    }

    /**
     * Capture a short burst of frames (so a moving video is understood), then
     * release the virtual display right away. Call OFF the main thread.
     */
    fun captureFrames(count: Int, gapMs: Long): List<String> {
        val proj = projection ?: return emptyList()
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        val display: VirtualDisplay? = runCatching {
            proj.createVirtualDisplay(
                "matrash-shot", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )
        }.getOrNull()
        if (display == null) { runCatching { reader.close() }; return emptyList() }
        return try {
            val out = ArrayList<String>()
            for (i in 0 until count) {
                grabOne(reader)?.let { if (out.lastOrNull() != it) out.add(it) }
                if (i < count - 1) try { Thread.sleep(gapMs) } catch (e: InterruptedException) { break }
            }
            out
        } finally {
            runCatching { display.release() }
            runCatching { reader.close() }
        }
    }

    private fun grabOne(reader: ImageReader): String? {
        var image = reader.acquireLatestImage()
        var tries = 0
        while (image == null && tries < 12) {
            try { Thread.sleep(60) } catch (e: InterruptedException) { return null }
            image = reader.acquireLatestImage()
            tries++
        }
        val img = image ?: return null
        return try {
            val plane = img.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * w
            val bmp = Bitmap.createBitmap(
                w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(buffer)
            val cropped = if (rowPadding == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, w, h)
            ScreenContext.set(cropped)
            ScreenContext.recent()
        } catch (e: Exception) {
            null
        } finally {
            runCatching { img.close() }
        }
    }

    private fun cleanup() {
        runCatching { projection?.stop() }; projection = null
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    private fun startAsForeground() {
        val channelId = "matrash_screen"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "قراءة الشاشة", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val notif: Notification = Notification.Builder(this, channelId)
            .setContentTitle("مطراش يقدر يقرأ شاشتك عند الطلب")
            .setContentText("لا يلتقط إلا لحظة ما تطلبين — لا يسجّل")
            .setSmallIcon(R.drawable.ic_mic_dark)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(7, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(7, notif)
        }
    }

    companion object {
        const val ACTION_STOP = "ai.muna.assistant.STOP_PROJECTION"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        @Volatile
        var instance: ScreenProjectionService? = null
    }
}
