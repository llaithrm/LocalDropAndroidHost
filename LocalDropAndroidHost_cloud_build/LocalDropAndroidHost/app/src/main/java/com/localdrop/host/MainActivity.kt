package com.localdrop.host

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var pinView: TextView
    private val captureRequest = 4102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 32, 28, 32)
        }
        val title = TextView(this).apply {
            text = "LocalDrop — Android Host"
            textSize = 24f
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply { textSize = 16f; gravity = Gravity.CENTER; setPadding(0,24,0,8) }
        pinView = TextView(this).apply { textSize = 20f; gravity = Gravity.CENTER; setPadding(0,8,0,20) }
        val start = Button(this).apply { text = "تشغيل السيرفر" }
        val stop = Button(this).apply { text = "إيقاف السيرفر" }
        val screen = Button(this).apply { text = "السماح ببث الشاشة" }
        val access = Button(this).apply { text = "تفعيل خدمة التحكم (Accessibility)" }
        box.addView(title); box.addView(status); box.addView(pinView)
        box.addView(start); box.addView(stop); box.addView(screen); box.addView(access)
        setContentView(box)
        refresh()
        start.setOnClickListener {
            startService(Intent(this, HostService::class.java).setAction(HostService.ACTION_START))
            Toast.makeText(this, "بدأ تشغيل السيرفر", Toast.LENGTH_SHORT).show()
            window.decorView.postDelayed({ refresh() }, 600)
        }
        stop.setOnClickListener {
            startService(Intent(this, HostService::class.java).setAction(HostService.ACTION_STOP))
            status.text = "السيرفر متوقف"
        }
        screen.setOnClickListener {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mgr.createScreenCaptureIntent(), captureRequest)
        }
        access.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    @Deprecated("Legacy result callback is required for Android 9 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest && resultCode == Activity.RESULT_OK && data != null) {
            val i = Intent(this, HostService::class.java).setAction(HostService.ACTION_CAPTURE)
            i.putExtra(HostService.EXTRA_RESULT_CODE, resultCode)
            i.putExtra(HostService.EXTRA_RESULT_DATA, data)
            startService(i)
            Toast.makeText(this, "تم منح إذن مشاركة الشاشة", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refresh() {
        val ip = localIp()
        status.text = "العنوان: http://$ip:8765\nتأكد أن الأجهزة على شبكة Wi‑Fi نفسها"
        pinView.text = "رمز الدخول: ${HostService.getPin(this)}"
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
