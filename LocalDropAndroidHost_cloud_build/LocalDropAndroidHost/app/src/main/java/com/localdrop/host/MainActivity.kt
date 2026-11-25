package com.localdrop.host

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {
    private lateinit var clockText: TextView
    private lateinit var dateText: TextView
    private lateinit var wifiText: TextView
    private lateinit var qrImageView: ImageView
    private lateinit var urlText: TextView
    private lateinit var pinText: TextView
    private lateinit var screenStatusBadge: TextView
    private lateinit var accessStatusBadge: TextView
    private lateinit var screenBtn: Button
    private lateinit var accessBtn: Button
    private lateinit var videoContainer: FrameLayout
    private lateinit var videoPlayer: VideoView
    private lateinit var dashboardLayout: LinearLayout
    private lateinit var notificationBanner: TextView

    private val captureRequest = 4102
    private val handler = Handler(Looper.getMainLooper())
    private val clockUpdater = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        // Keep TV screen awake 24/7 in DeX / HDMI mode
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#080D1A"))
        }

        // --- DASHBOARD CONTAINER ---
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        dashboardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 40, 48, 60)
        }

        // Top Status & Wi-Fi Bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 16, 24, 16)
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val tvIcon = TextView(this).apply {
            text = "📺 LocalDrop DeX Hub"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        wifiText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(tvIcon)
        topBar.addView(wifiText)

        // Notification / Alert Banner
        notificationBanner = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1D4ED8"))
            setPadding(24, 14, 24, 14)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }

        // Clock & Date Section (Apple TV Style)
        val clockCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 24)
        }

        clockText = TextView(this).apply {
            textSize = 64f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            letterSpacing = 0.05f
        }

        dateText = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 0)
        }

        val weatherText = TextView(this).apply {
            text = "🌤️ 26°C — صافي ومستقر | وضع التلفاز نشط"
            textSize = 14f
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 0)
        }
        clockCard.addView(clockText)
        clockCard.addView(dateText)
        clockCard.addView(weatherText)

        // Middle Section: QR Code Card & Direct Info
        val mainCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 28, 32, 28)
            setBackgroundColor(Color.parseColor("#111927"))
        }

        val qrTitle = TextView(this).apply {
            text = "امسح الرمز بكاميرا الهاتف للتحكم بالريموت والماوس"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }

        val qrWrapper = LinearLayout(this).apply {
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        qrImageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(400, 400)
        }
        qrWrapper.addView(qrImageView)

        urlText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 6)
        }

        pinText = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.parseColor("#4ADE80"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }

        val copyBtn = Button(this).apply {
            text = "نسخ رابط التحكم المباشر"
            setBackgroundColor(Color.parseColor("#1E293B"))
            setTextColor(Color.WHITE)
            setPadding(24, 14, 24, 14)
            setOnClickListener {
                val ip = localIp()
                val pin = HostService.getPin(this@MainActivity)
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("LocalDrop URL", "http://$ip:8765/?pin=$pin"))
                Toast.makeText(this@MainActivity, "تم نسخ الرابط المباشر!", Toast.LENGTH_SHORT).show()
            }
        }

        mainCard.addView(qrTitle)
        mainCard.addView(qrWrapper)
        mainCard.addView(urlText)
        mainCard.addView(pinText)
        mainCard.addView(copyBtn)

        // Status Badges & First-Time Activation Buttons
        val statusSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 24, 0, 0)
        }

        screenStatusBadge = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 6, 0, 6)
        }

        accessStatusBadge = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 6, 0, 16)
        }

        screenBtn = Button(this).apply {
            text = "1. السماح بمشاركة الشاشة (مطلوب لعرض الشاشة على الهاتف)"
            setBackgroundColor(Color.parseColor("#2563EB"))
            setTextColor(Color.WHITE)
            setPadding(24, 16, 24, 16)
            setOnClickListener {
                val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mgr.createScreenCaptureIntent(), captureRequest)
            }
        }

        accessBtn = Button(this).apply {
            text = "2. تفعيل خدمة التحكم (مطلوب لأزرار الريموت والماوس)"
            setBackgroundColor(Color.parseColor("#7C3AED"))
            setTextColor(Color.WHITE)
            setPadding(24, 16, 24, 16)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this@MainActivity, "ابحث عن LocalDrop TV Host وفعّله", Toast.LENGTH_LONG).show()
            }
        }

        statusSection.addView(screenStatusBadge)
        statusSection.addView(accessStatusBadge)
        statusSection.addView(screenBtn)
        val space = LinearLayout(this).apply { layoutParams = LinearLayout.LayoutParams(1, 14) }
        statusSection.addView(space)
        statusSection.addView(accessBtn)

        dashboardLayout.addView(topBar)
        dashboardLayout.addView(notificationBanner)
        dashboardLayout.addView(clockCard)
        dashboardLayout.addView(mainCard)
        dashboardLayout.addView(statusSection)
        scroll.addView(dashboardLayout)
        rootFrame.addView(scroll)

        // --- FULLSCREEN VIDEO PLAYER CONTAINER (FOR MOVIES ON TV) ---
        videoContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }

        videoPlayer = VideoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }

        val closeVideoBtn = Button(this).apply {
            text = "✕ إغلاق الفيديو والعودة للواجهة"
            setBackgroundColor(Color.parseColor("#CC1E293B"))
            setTextColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply {
                setMargins(32, 32, 0, 0)
            }
            setOnClickListener { stopTvVideo() }
        }

        videoContainer.addView(videoPlayer)
        videoContainer.addView(closeVideoBtn)
        rootFrame.addView(videoContainer)

        setContentView(rootFrame)

        // Start background host service automatically
        startService(Intent(this, HostService::class.java).setAction(HostService.ACTION_START))

        handler.post(clockUpdater)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        handler.removeCallbacks(clockUpdater)
        instance = null
        super.onDestroy()
    }

    private fun updateClock() {
        val now = Date()
        val timeFormat = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())
        val dateFormat = SimpleDateFormat("EEEE ، d MMMM yyyy", Locale.getDefault())
        clockText.text = timeFormat.format(now)
        dateText.text = dateFormat.format(now)
    }

    fun playMovieOnTv(file: File) {
        runOnUiThread {
            try {
                videoContainer.visibility = View.VISIBLE
                videoPlayer.setVideoURI(Uri.fromFile(file))
                videoPlayer.setOnPreparedListener { mp ->
                    mp.isLooping = false
                    videoPlayer.start()
                }
                videoPlayer.setOnCompletionListener {
                    stopTvVideo()
                }
                showTvNotification("🎬 جاري تشغيل: ${file.name}")
            } catch (e: Exception) {
                Toast.makeText(this, "تعذر تشغيل الفيديو: ${e.message}", Toast.LENGTH_SHORT).show()
                stopTvVideo()
            }
        }
    }

    fun stopTvVideo() {
        runOnUiThread {
            if (videoPlayer.isPlaying) videoPlayer.stopPlayback()
            videoContainer.visibility = View.GONE
        }
    }

    fun showTvNotification(msg: String) {
        runOnUiThread {
            notificationBanner.text = msg
            notificationBanner.visibility = View.VISIBLE
            handler.removeCallbacksAndMessages("banner")
            handler.postDelayed({
                notificationBanner.visibility = View.GONE
            }, 5000)
        }
    }

    @Deprecated("Required for Android 9 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest && resultCode == Activity.RESULT_OK && data != null) {
            val i = Intent(this, HostService::class.java).setAction(HostService.ACTION_CAPTURE)
            i.putExtra(HostService.EXTRA_RESULT_CODE, resultCode)
            i.putExtra(HostService.EXTRA_RESULT_DATA, data)
            startService(i)
            Toast.makeText(this, "تم تفعيل بث الشاشة بنجاح!", Toast.LENGTH_SHORT).show()
            window.decorView.postDelayed({ refresh() }, 500)
        }
    }

    fun refresh() {
        val ip = localIp()
        val pin = HostService.getPin(this)
        val wifi = getWifiSsid()
        val directUrl = "http://$ip:8765/?pin=$pin"

        wifiText.text = "📶 $wifi | IP: $ip"
        urlText.text = "عنوان المتصفح: http://$ip:8765"
        pinText.text = "رمز الدخول: $pin"

        val qrBitmap = generateQrCode(directUrl, 400)
        if (qrBitmap != null) {
            qrImageView.setImageBitmap(qrBitmap)
        }

        if (HostService.isCapturing) {
            screenStatusBadge.text = "🟢 بث الشاشة: نـشـط وجاهز للعرض على الهاتف"
            screenStatusBadge.setTextColor(Color.parseColor("#4ADE80"))
            screenBtn.text = "✔ بث الشاشة يعمل"
        } else {
            screenStatusBadge.text = "🟡 بث الشاشة: بانتظار الموافقة"
            screenStatusBadge.setTextColor(Color.parseColor("#FDE047"))
            screenBtn.text = "1. اضغط هنا للسماح ببث الشاشة"
        }

        if (RemoteAccessibilityService.isAvailable) {
            accessStatusBadge.text = "🟢 خدمة التحكم: مـفـعـلـة وتستجيب للريموت والماوس"
            accessStatusBadge.setTextColor(Color.parseColor("#4ADE80"))
            accessBtn.text = "✔ خدمة التحكم مفعلة وجاهزة"
        } else {
            accessStatusBadge.text = "🔴 خدمة التحكم: غير مفعلة (مطلوبة لأزرار الريموت والماوس)"
            accessStatusBadge.setTextColor(Color.parseColor("#F87171"))
            accessBtn.text = "2. اضغط هنا لتفعيل خدمة التحكم في الإعدادات"
        }
    }

    private fun generateQrCode(text: String, size: Int = 400): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val matrix = writer.encode(text, BarcodeFormat.QR_CODE, size, size)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (_: Exception) {
            null
        }
    }

    private fun getWifiSsid(): String {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wm?.connectionInfo
            val ssid = info?.ssid?.replace("\"", "")
            if (!ssid.isNullOrBlank() && ssid != "<unknown ssid>") {
                return ssid
            }
        } catch (_: Exception) {}
        return "Wi-Fi محلي"
    }

    private fun localIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val addresses = interfaces.nextElement().inetAddresses
                while (addresses.hasMoreElements()) {
                    val a = addresses.nextElement()
                    if (!a.isLoopbackAddress && a is Inet4Address) return a.hostAddress ?: "غير معروف"
                }
            }
        } catch (_: Exception) {}
        return "غير معروف"
    }

    companion object {
        @Volatile var instance: MainActivity? = null
    }
}
