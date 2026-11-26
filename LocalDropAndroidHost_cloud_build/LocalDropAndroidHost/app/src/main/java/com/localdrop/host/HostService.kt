package com.localdrop.host

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioManager
import android.media.ImageReader
import android.media.MediaPlayer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class HostService : Service() {
    private var server: HostHttpServer? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val latestJpeg = AtomicReference<ByteArray?>(null)
    private val captureExecutor = Executors.newSingleThreadExecutor()

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var audioManager: AudioManager? = null
    private var nsdManager: NsdManager? = null
    private var nsdRegistration: NsdManager.RegistrationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startHost()
            ACTION_STOP -> {
                isRunning = false
                stopCapture()
                unregisterDiscovery()
                server?.stop()
                server = null
                releaseLocks()
                stopForeground(true)
                stopSelf()
            }
            ACTION_CAPTURE -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (data != null && code == Activity.RESULT_OK) startCapture(code, data)
            }
        }
        return START_STICKY
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalDrop::TVHostWakeLock").apply {
                setReferenceCounted(false)
                acquire()
            }
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LocalDrop::TVHostWifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
    }

    private fun startHost() {
        createChannel()
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("LocalDrop Smart TV Hub")
            .setContentText("محرك التحكم والتلفاز يعمل بنشاط في الخلفية")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .build()
        startForeground(7, notification)
        isRunning = true

        if (server == null) {
            try {
                server = HostHttpServer(this, latestJpeg).also { it.start() }
                registerDiscovery()
            } catch (e: Exception) {
                // Retry in 2 seconds if port is temporarily occupied
                captureExecutor.execute {
                    Thread.sleep(2000)
                    try {
                        server = HostHttpServer(this, latestJpeg).also { it.start() }
                        registerDiscovery()
                    } catch (_: Exception) {}
                }
            }
        }
    }


    private fun registerDiscovery() {
        if (nsdRegistration != null) return
        try {
            val info = NsdServiceInfo().apply {
                serviceName = "LocalDrop-${Build.MODEL.replace(Regex("[^A-Za-z0-9_-]"), "")}"
                serviceType = "_http._tcp."
                port = 8765
                setAttribute("protocol", "localdrop-v1")
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { nsdRegistration = null }
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { nsdRegistration = null }
            }
            nsdRegistration = listener
            nsdManager?.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) { nsdRegistration = null }
    }

    private fun unregisterDiscovery() {
        try { nsdRegistration?.let { nsdManager?.unregisterService(it) } } catch (_: Exception) {}
        nsdRegistration = null
    }

    private fun startCapture(code: Int, data: Intent) {
        stopCapture()
        try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mgr.getMediaProjection(code, data)
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
            val realW = metrics.widthPixels
            val realH = metrics.heightPixels
            val density = metrics.densityDpi

            // Scale to max 720p width for fast, fluid TV mirroring
            val targetW = if (realW > 720) 720 else realW
            val targetH = (realH * (targetW.toFloat() / realW)).toInt()

            reader = ImageReader.newInstance(targetW, targetH, PixelFormat.RGBA_8888, 2)
            reader?.setOnImageAvailableListener({ r ->
                captureExecutor.execute {
                    var image: android.media.Image? = null
                    try {
                        image = r.acquireLatestImage() ?: return@execute
                        val plane = image.planes[0]
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        val rowPadding = rowStride - pixelStride * targetW
                        val padded = Bitmap.createBitmap(targetW + rowPadding / pixelStride, targetH, Bitmap.Config.ARGB_8888)
                        padded.copyPixelsFromBuffer(plane.buffer)
                        val bitmap = Bitmap.createBitmap(padded, 0, 0, targetW, targetH)
                        val out = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 60, out)
                        latestJpeg.set(out.toByteArray())
                        padded.recycle(); bitmap.recycle()
                    } catch (_: Exception) {
                    } finally { image?.close() }
                }
            }, null)

            display = projection?.createVirtualDisplay(
                "LocalDropScreen", targetW, targetH, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader?.surface, null, null
            )
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopCapture() }
            }, null)
            isCapturing = true
        } catch (_: Exception) { stopCapture() }
    }

    private fun stopCapture() {
        isCapturing = false
        try { display?.release() } catch (_: Exception) {}
        display = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        latestJpeg.set(null)
    }

    // Audio & TV Volume Control
    fun adjustVolume(direction: String) {
        val am = audioManager ?: return
        when (direction) {
            "vol_up" -> am.adjustSuggestedStreamVolume(AudioManager.ADJUST_RAISE, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
            "vol_down" -> am.adjustSuggestedStreamVolume(AudioManager.ADJUST_LOWER, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
            "vol_mute" -> am.adjustSuggestedStreamVolume(AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
            "play_pause" -> {
                val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                am.dispatchMediaKeyEvent(down)
                am.dispatchMediaKeyEvent(up)
            }
        }
    }

    // Play Voice Note directly through TV/HDMI Speakers
    fun playVoiceAudio(file: File) {
        try {
            val mp = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
                setOnCompletionListener { it.release() }
            }
        } catch (_: Exception) {}
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "LocalDrop TV Hub", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onDestroy() {
        isRunning = false
        stopCapture()
        unregisterDiscovery()
        server?.stop()
        releaseLocks()
        captureExecutor.shutdownNow()
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: HostService? = null
        @Volatile var isRunning = false
        @Volatile var isCapturing = false
        const val ACTION_START = "host.start"
        const val ACTION_STOP = "host.stop"
        const val ACTION_CAPTURE = "host.capture"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val CHANNEL = "localdrop_tv_hub"

        fun getPin(ctx: Context): String {
            val p = ctx.getSharedPreferences("host", Context.MODE_PRIVATE)
            var pin = p.getString("pin", null)
            if (pin == null) {
                pin = (100000..999999).random().toString()
                p.edit().putString("pin", pin).apply()
            }
            return pin
        }
    }
}
