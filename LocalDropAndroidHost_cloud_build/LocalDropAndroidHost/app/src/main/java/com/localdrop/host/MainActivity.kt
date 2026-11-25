package com.localdrop.host

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {
    private lateinit var qrImageView: ImageView
    private lateinit var wifiNameText: TextView
    private lateinit var urlText: TextView
    private lateinit var pinText: TextView
    private lateinit var screenStatusText: TextView
    private lateinit var accessStatusText: TextView
    private lateinit var screenBtn: Button
    private lateinit var accessBtn: Button
    private val captureRequest = 4102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#090D16"))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 40, 32, 40)
        }

        val title = TextView(this).apply {
            text = "LocalDrop Host"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 4)
        }

        val subtitle = TextView(this).apply {
            text = "التحكم عن بُعد — شاشة كاملة وتحكم باللمس"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
        }

        // Wi-Fi Banner
        wifiNameText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
            setPadding(16, 12, 16, 12)
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        // QR Code Card
        val qrCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 20, 0, 20)
            }
        }

        qrImageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(420, 420)
        }
        qrCard.addView(qrImageView)

        val qrHint = TextView(this).apply {
            text = "امسح الرمز بكاميرا الهاتف الآخر للاتصال والتحكم المباشر"
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }

        // Direct URL Box
        urlText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
            setPadding(16, 16, 16, 8)
            setBackgroundColor(Color.parseColor("#1E293B"))
        }

        pinText = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.parseColor("#4ADE80"))
            gravity = Gravity.CENTER
            setPadding(16, 8, 16, 16)
            setBackgroundColor(Color.parseColor("#1E293B"))
        }

        screenStatusText = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 6)
        }

        accessStatusText = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 16)
        }

        screenBtn = Button(this).apply {
            text = "1. السماح ببث الشاشة"
            setBackgroundColor(Color.parseColor("#2563EB"))
            setTextColor(Color.WHITE)
            setPadding(20, 16, 20, 16)
        }

        accessBtn = Button(this).apply {
            text = "2. تفعيل خدمة التحكم (Accessibility)"
            setBackgroundColor(Color.parseColor("#7C3AED"))
            setTextColor(Color.WHITE)
            setPadding(20, 16, 20, 16)
        }

        val copyBtn = Button(this).apply {
            text = "نسخ رابط التحكم المباشر"
            setBackgroundColor(Color.parseColor("#334155"))
            setTextColor(Color.WHITE)
            setPadding(20, 14, 20, 14)
        }

        val space1 = LinearLayout(this).apply { layoutParams = LinearLayout.LayoutParams(1, 12) }
        val space2 = LinearLayout(this).apply { layoutParams = LinearLayout.LayoutParams(1, 12) }

        root.addView(title)
        root.addView(subtitle)
        root.addView(wifiNameText)
        root.addView(qrCard)
        root.addView(qrHint)
        root.addView(urlText)
        root.addView(pinText)
        root.addView(screenStatusText)
        root.addView(accessStatusText)
        root.addView(screenBtn)
        root.addView(space1)
        root.addView(accessBtn)
        root.addView(space2)
        root.addView(copyBtn)

        scroll.addView(root)
        setContentView(scroll)

        // Start background server automatically on launch
        startService(Intent(this, HostService::class.java).setAction(HostService.ACTION_START))

        screenBtn.setOnClickListener {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mgr.createScreenCaptureIntent(), captureRequest)
        }

        accessBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "ابحث عن LocalDrop في القائمة وفعّله", Toast.LENGTH_LONG).show()
        }

        copyBtn.setOnClickListener {
            val ip = localIp()
            val pin = HostService.getPin(this)
            val url = "http://$ip:8765/?pin=$pin"
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("LocalDrop URL", url))
            Toast.makeText(this, "تم نسخ الرابط المباشر!", Toast.LENGTH_SHORT).show()
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
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

    private fun refresh() {
        val ip = localIp()
        val pin = HostService.getPin(this)
        val wifi = getWifiSsid()
        val directUrl = "http://$ip:8765/?pin=$pin"

        wifiNameText.text = "📶 شبكة الواي فاي: $wifi\n(تأكد من اتصال الكمبيوتر/الهاتف الآخر بنفس الشبكة)"
        urlText.text = "أو اكتب في المتصفح:\nhttp://$ip:8765"
        pinText.text = "رمز الدخول: $pin"

        // Generate and update QR Code
        val qrBitmap = generateQrCode(directUrl, 420)
        if (qrBitmap != null) {
            qrImageView.setImageBitmap(qrBitmap)
        }

        if (HostService.isCapturing) {
            screenStatusText.text = "🟢 بث الشاشة: نـشـط ومستعد"
            screenStatusText.setTextColor(Color.parseColor("#4ADE80"))
            screenBtn.text = "✔ بث الشاشة يعمل"
        } else {
            screenStatusText.text = "🔴 بث الشاشة: غير نشط (اضغط الزر للموافقة)"
            screenStatusText.setTextColor(Color.parseColor("#F87171"))
            screenBtn.text = "1. اضغط هنا للسماح ببث الشاشة"
        }

        if (RemoteAccessibilityService.isAvailable) {
            accessStatusText.text = "🟢 خدمة التحكم: مـفـعـلـة وتستجيب للمس والأزرار"
            accessStatusText.setTextColor(Color.parseColor("#4ADE80"))
            accessBtn.text = "✔ خدمة التحكم مفعلة وجاهزة"
        } else {
            accessStatusText.text = "🔴 خدمة التحكم: غير مفعلة (مطلوبة للتحكم باللمس)"
            accessStatusText.setTextColor(Color.parseColor("#F87171"))
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
}
