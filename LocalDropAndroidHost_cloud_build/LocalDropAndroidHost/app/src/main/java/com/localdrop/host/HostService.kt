package com.localdrop.host

import android.app.*
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class HostService : Service() {
    private var server: HostHttpServer? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val latestJpeg = AtomicReference<ByteArray?>(null)
    private val captureExecutor = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startHost()
            ACTION_STOP -> { stopCapture(); server?.stop(); server = null; stopForeground(true); stopSelf() }
            ACTION_CAPTURE -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (data != null && code == Activity.RESULT_OK) startCapture(code, data)
            }
        }
        return START_STICKY
    }

    private fun startHost() {
        createChannel()
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("LocalDrop Host يعمل")
            .setContentText("السيرفر المحلي يستقبل اتصالات الشبكة")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true).build()
        startForeground(7, notification)
        if (server == null) {
            try { server = HostHttpServer(this, latestJpeg).also { it.start() } }
            catch (e: Exception) { stopSelf() }
        }
    }

    private fun startCapture(code: Int, data: Intent) {
        stopCapture()
        try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mgr.getMediaProjection(code, data)
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
            val w = metrics.widthPixels
            val h = metrics.heightPixels
            val density = metrics.densityDpi
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            reader?.setOnImageAvailableListener({ r ->
                captureExecutor.execute {
                    var image: android.media.Image? = null
                    try {
                        image = r.acquireLatestImage() ?: return@execute
                        val plane = image.planes[0]
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        val rowPadding = rowStride - pixelStride * w
                        val padded = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                        padded.copyPixelsFromBuffer(plane.buffer)
                        val bitmap = Bitmap.createBitmap(padded, 0, 0, w, h)
                        val out = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 55, out)
                        latestJpeg.set(out.toByteArray())
                        padded.recycle(); bitmap.recycle()
                    } catch (_: Exception) {
                    } finally { image?.close() }
                }
            }, null)
            display = projection?.createVirtualDisplay(
                "LocalDropScreen", w, h, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader?.surface, null, null
            )
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopCapture() }
            }, null)
        } catch (_: Exception) { stopCapture() }
    }

    private fun stopCapture() {
        try { display?.release() } catch (_: Exception) {}
        display = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        latestJpeg.set(null)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "LocalDrop Host", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onDestroy() {
        stopCapture()
        server?.stop()
        captureExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "host.start"
        const val ACTION_STOP = "host.stop"
        const val ACTION_CAPTURE = "host.capture"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val CHANNEL = "localdrop_host"
        fun getPin(ctx: Context): String {
            val p = ctx.getSharedPreferences("host", Context.MODE_PRIVATE)
            var pin = p.getString("pin", null)
            if (pin == null) { pin = (100000..999999).random().toString(); p.edit().putString("pin", pin).apply() }
            return pin
        }
    }
}
