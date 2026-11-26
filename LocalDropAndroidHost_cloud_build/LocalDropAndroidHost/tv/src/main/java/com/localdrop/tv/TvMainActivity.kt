package com.localdrop.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class TvMainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private var poller: ScheduledExecutorService? = null
    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val found = linkedMapOf<String, String>()
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var hostInput: EditText
    private lateinit var pinInput: EditText
    private lateinit var mediaList: LinearLayout
    private lateinit var chatList: LinearLayout
    private lateinit var chatInput: EditText
    private lateinit var connectButton: Button
    private var host = ""
    private var pin = ""
    private var connected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nsd = getSystemService(NSD_SERVICE) as NsdManager
        buildUi()
        discoverHosts()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 24, 36, 24)
            setBackgroundColor(0xFF080E1A.toInt())
            isFocusableInTouchMode = true
        }
        val header = TextView(this).apply {
            text = "LOCALDROP  TV"
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        root.addView(header)
        status = TextView(this).apply {
            text = "ابحث عن مضيف LocalDrop على شبكة الراوتر..."
            textSize = 16f
            setTextColor(0xFF9DB2D1.toInt())
            setPadding(0, 8, 0, 18)
        }
        root.addView(status)

        val connectRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        hostInput = EditText(this).apply {
            hint = "عنوان المضيف مثال 192.168.1.5"
            setSingleLine(true)
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF8796AD.toInt())
            setBackgroundColor(0xFF172338.toInt())
            setPadding(14, 8, 14, 8)
        }
        connectRow.addView(hostInput, LinearParams(0, 54, 2f))
        pinInput = EditText(this).apply {
            hint = "PIN"
            setSingleLine(true)
            inputType = 2
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF8796AD.toInt())
            setBackgroundColor(0xFF172338.toInt())
            setPadding(14, 8, 14, 8)
        }
        connectRow.addView(pinInput, LinearParams(0, 54, 1f))
        connectButton = Button(this).apply {
            text = "اتصال"
            textSize = 16f
            setOnClickListener { connect(hostInput.text.toString(), pinInput.text.toString()) }
        }
        connectRow.addView(connectButton, LinearParams(-2, 54))
        root.addView(connectRow)

        val discovered = Button(this).apply {
            text = "إعادة البحث عن الأجهزة"
            setOnClickListener { discoverHosts() }
        }
        root.addView(discovered, LinearParams(-2, 52).apply { topMargin = 8 })

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        content.addView(sectionTitle("الأجهزة المكتشفة"))
        mediaList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(mediaList)
        content.addView(sectionTitle("مكتبة الملفات المشتركة"))
        val upload = Button(this).apply {
            text = "إرسال ملف من التلفاز إلى المضيف"
            setOnClickListener {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                startActivityForResult(i, PICK_FILE)
            }
        }
        content.addView(upload, LinearParams(-2, 52))
        content.addView(sectionTitle("المحادثة"))
        chatList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, 12) }
        content.addView(chatList)
        val chatRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chatInput = EditText(this).apply {
            hint = "اكتب رسالة..."
            setSingleLine(true)
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF8796AD.toInt())
            setBackgroundColor(0xFF172338.toInt())
        }
        chatRow.addView(chatInput, LinearParams(0, 54, 1f))
        chatRow.addView(Button(this).apply {
            text = "إرسال"
            setOnClickListener { sendChat() }
        }, LinearParams(-2, 54))
        content.addView(chatRow)
        root.addView(scroll, LinearParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 20f
        setTextColor(0xFF77A9FF.toInt())
        setPadding(0, 22, 0, 10)
    }

    private fun LinearParams(w: Int, h: Int, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight)

    private fun discoverHosts() {
        stopDiscovery()
        found.clear()
        status.text = "جارٍ البحث عن مضيف LocalDrop..."
        val manager = nsd ?: return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceName.startsWith("LocalDrop")) return
                manager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(service: NsdServiceInfo) {
                        val address = service.host?.hostAddress ?: return
                        runOnUiThread {
                            found[service.serviceName] = address
                            renderFoundHosts()
                            hostInput.setText(address)
                            status.text = "تم العثور على مضيف: ${service.serviceName}"
                        }
                    }
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                runOnUiThread { found.remove(info.serviceName); renderFoundHosts() }
            }
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, errorCode: Int) { stopDiscovery() }
            override fun onStopDiscoveryFailed(type: String, errorCode: Int) { stopDiscovery() }
        }
        discovery = listener
        try { manager.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }
        catch (_: Exception) { status.text = "تعذر البحث التلقائي؛ أدخل عنوان IP يدوياً" }
    }

    private fun stopDiscovery() {
        try { discovery?.let { nsd?.stopServiceDiscovery(it) } } catch (_: Exception) {}
        discovery = null
    }

    private fun renderFoundHosts() {
        mediaList.removeAllViews()
        if (found.isEmpty()) {
            mediaList.addView(label("لم يتم العثور على أجهزة بعد. يمكنك إدخال عنوان IP يدوياً."))
        } else found.forEach { (name, address) ->
            mediaList.addView(Button(this).apply {
                text = "$name  •  $address"
                setOnClickListener { hostInput.setText(address) }
            })
        }
    }

    private fun connect(address: String, accessPin: String) {
        val clean = address.trim().removePrefix("http://").removeSuffix("/")
        if (clean.isBlank() || accessPin.length != 6) {
            status.text = "أدخل عنوان IP ورمز PIN المكوّن من 6 أرقام"
            return
        }
        host = clean
        pin = accessPin
        status.text = "جارٍ التحقق من الاتصال..."
        io.execute {
            try {
                val response = request("GET", "/api/media", null)
                if (response.first !in 200..299) throw IOException("PIN أو عنوان المضيف غير صحيح")
                connected = true
                runOnUiThread {
                    status.text = "متصل بالمضيف $host"
                    connectButton.text = "متصل"
                    loadMedia(response.second)
                    startChatPolling()
                }
            } catch (e: Exception) {
                connected = false
                runOnUiThread { status.text = "فشل الاتصال: ${e.message}" }
            }
        }
    }

    private fun request(method: String, path: String, body: ByteArray?): Pair<Int, String> {
        val conn = URL("http://$host:8765$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 10000
        conn.requestMethod = method
        conn.setRequestProperty("X-Host-Pin", pin)
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        return code to text
    }

    private fun loadMedia(json: String) {
        mediaList.removeAllViews()
        try {
            val files = JSONObject(json).optJSONArray("media") ?: JSONArray()
            if (files.length() == 0) mediaList.addView(label("لا توجد ملفات مشتركة حالياً"))
            for (i in 0 until files.length()) {
                val item = files.getJSONObject(i)
                val name = item.optString("name")
                val size = item.optLong("size")
                val video = item.optBoolean("isVideo")
                mediaList.addView(Button(this).apply {
                    text = (if (video) "▶  " else "▧  ") + name + "  •  " + formatSize(size)
                    textSize = 16f
                    setOnClickListener { downloadAndOpen(name, video) }
                }, LinearParams(-1, 58).apply { bottomMargin = 6 })
            }
        } catch (_: Exception) { mediaList.addView(label("تعذر قراءة قائمة الملفات")) }
    }

    private fun downloadAndOpen(name: String, video: Boolean) {
        status.text = "جارٍ تنزيل $name..."
        io.execute {
            try {
                val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
                val conn = URL("http://$host:8765/download/$encoded?pin=$pin").openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 60000
                if (conn.responseCode !in 200..299) throw IOException("تعذر تنزيل الملف")
                val dir = File(cacheDir, "received").apply { mkdirs() }
                val safe = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
                val file = File(dir, safe)
                conn.inputStream.use { input -> FileOutputStream(file).use { output -> input.copyTo(output) } }
                conn.disconnect()
                val mime = if (video) "video/*" else contentResolver.getType(Uri.fromFile(file)) ?: "image/*"
                val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
                runOnUiThread {
                    status.text = "تم تنزيل $name"
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    } catch (_: Exception) {
                        AlertDialog.Builder(this).setMessage("تم تنزيل الملف إلى مساحة التطبيق، لكن لا يوجد مشغل مناسب لفتحه.").setPositiveButton("حسناً", null).show()
                    }
                }
            } catch (e: Exception) { runOnUiThread { status.text = "فشل التنزيل: ${e.message}" } }
        }
    }

    private fun startChatPolling() {
        poller?.shutdownNow()
        poller = Executors.newSingleThreadScheduledExecutor()
        poller?.scheduleWithFixedDelay({
            if (!connected) return@scheduleWithFixedDelay
            try {
                val result = request("GET", "/api/chat", null)
                if (result.first == 200) runOnUiThread { renderChat(result.second) }
            } catch (_: Exception) { runOnUiThread { status.text = "انقطع الاتصال؛ تتم محاولة الاستعادة..." } }
        }, 0, 3, TimeUnit.SECONDS)
    }

    private fun renderChat(json: String) {
        try {
            val messages = JSONObject(json).optJSONArray("messages") ?: JSONArray()
            chatList.removeAllViews()
            val start = (messages.length() - 40).coerceAtLeast(0)
            for (i in start until messages.length()) {
                val m = messages.getJSONObject(i)
                chatList.addView(label("${m.optString("from")}: ${m.optString("text")}   ${m.optString("time")}"))
            }
        } catch (_: Exception) {}
    }

    private fun sendChat() {
        val text = chatInput.text.toString().trim()
        if (text.isEmpty() || !connected) return
        val payload = JSONObject().put("from", "Android TV").put("text", text).toString().toByteArray(Charsets.UTF_8)
        io.execute {
            try {
                val result = request("POST", "/api/chat", payload)
                runOnUiThread { if (result.first == 200) chatInput.setText("") else status.text = "تعذر إرسال الرسالة" }
            } catch (e: Exception) { runOnUiThread { status.text = "فشل الإرسال: ${e.message}" } }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_FILE && resultCode == Activity.RESULT_OK && data?.data != null) uploadToHost(data.data!!)
    }

    private fun uploadToHost(uri: Uri) {
        if (!connected) { status.text = "اتصل بالمضيف أولاً"; return }
        status.text = "جارٍ تجهيز الملف للإرسال..."
        io.execute {
            try {
                val name = queryName(uri)
                val temp = File(cacheDir, "upload_${System.currentTimeMillis()}")
                contentResolver.openInputStream(uri).use { input ->
                    if (input == null) throw IOException("تعذر قراءة الملف")
                    FileOutputStream(temp).use { output -> input.copyTo(output) }
                }
                val boundary = "LocalDropBoundary${System.currentTimeMillis()}"
                val encodedName = URLEncoder.encode(name, "UTF-8")
                val url = URL("http://$host:8765/api/media?name=$encodedName")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true
                    connectTimeout = 8000; readTimeout = 60000
                    setRequestProperty("X-Host-Pin", pin)
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                }
                conn.outputStream.use { out ->
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${name.replace("\"", "_")}\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
                    temp.inputStream().use { it.copyTo(out) }
                    out.write("\r\n--$boundary--\r\n".toByteArray())
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() } ?: ""
                conn.disconnect(); temp.delete()
                if (code !in 200..299) throw IOException(response)
                runOnUiThread { status.text = "تم إرسال $name إلى المضيف"; refreshMedia() }
            } catch (e: Exception) { runOnUiThread { status.text = "فشل رفع الملف: ${e.message}" } }
        }
    }

    private fun queryName(uri: Uri): String {
        var name = "file_${System.currentTimeMillis()}"
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val index = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0) name = c.getString(index) ?: name
            }
        }
        return name
    }

    private fun refreshMedia() {
        io.execute {
            try {
                val r = request("GET", "/api/media", null)
                runOnUiThread { if (r.first == 200) loadMedia(r.second) }
            } catch (_: Exception) {}
        }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(0xFFE6EDF8.toInt())
        setPadding(10, 10, 10, 10)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    override fun onDestroy() {
        connected = false
        poller?.shutdownNow()
        stopDiscovery()
        io.shutdownNow()
        super.onDestroy()
    }

    companion object { private const val PICK_FILE = 4102 }
}
