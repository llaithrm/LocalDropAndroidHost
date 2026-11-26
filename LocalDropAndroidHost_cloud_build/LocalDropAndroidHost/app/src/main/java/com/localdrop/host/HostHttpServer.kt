package com.localdrop.host

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicReference

class HostHttpServer(
    private val ctx: Context,
    private val frame: AtomicReference<ByteArray?>
) : NanoHTTPD("0.0.0.0", 8765) {

    private val mediaDir = File(ctx.getExternalFilesDir(null), "TV_Media").apply { mkdirs() }
    private val voiceDir = File(ctx.getExternalFilesDir(null), "TV_Voice").apply { mkdirs() }
    private val database = HostDatabase(ctx)

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        if (uri == "/" || uri == "/index.html") {
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", PAGE)
        }

        if (uri.startsWith("/download/")) {
            val supplied = session.parms["pin"] ?: session.headers["x-host-pin"]
            if (supplied != HostService.getPin(ctx)) {
                return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Unauthorized")
            }
            val name = uri.removePrefix("/download/").substringBefore("?")
            val target = File(mediaDir, name).canonicalFile
            if (!target.path.startsWith(mediaDir.canonicalPath + File.separator) || !target.isFile) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
            return newFixedLengthResponse(Response.Status.OK, "application/octet-stream", target.inputStream(), target.length())
        }

        if (uri == "/api/info") {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            val hasFrame = frame.get() != null
            val hasAccess = RemoteAccessibilityService.isAvailable
            val model = "${Build.MANUFACTURER.capitalize(Locale.getDefault())} ${Build.MODEL} (DeX TV)"
            return json(JSONObject()
                .put("ok", true)
                .put("name", model)
                .put("screenWidth", dm.widthPixels)
                .put("screenHeight", dm.heightPixels)
                .put("screenReady", hasFrame)
                .put("accessibilityReady", hasAccess)
            )
        }

        if (!authorized(session)) {
            return json(JSONObject().put("ok", false).put("error", "رمز الدخول غير صحيح"), Response.Status.UNAUTHORIZED)
        }

        return when (uri) {
            "/api/screen" -> {
                val bytes = frame.get()
                if (bytes == null) {
                    json(JSONObject().put("ok", false).put("error", "screen_not_started"), Response.Status.SERVICE_UNAVAILABLE)
                } else {
                    newFixedLengthResponse(Response.Status.OK, "image/jpeg", bytes.inputStream(), bytes.size.toLong())
                }
            }
            "/api/input" -> handleInput(session)
            "/api/media" -> handleMedia(session)
            "/api/voice" -> handleVoice(session)
            "/api/chat" -> handleChat(session)
            "/api/device/register" -> handleDeviceRegister(session)
            "/api/devices" -> json(JSONObject().put("devices", database.getDevices()))
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
    }

    private fun authorized(s: IHTTPSession): Boolean {
        val expected = HostService.getPin(ctx)
        val headerPin = s.headers["x-host-pin"]
        val queryPin = s.parms["pin"]
        return (headerPin == expected || queryPin == expected)
    }

    private fun handleInput(s: IHTTPSession): Response {
        return try {
            val body = body(s)
            val o = JSONObject(body)
            val action = o.optString("action")

            // TV Volume & Media Playback Control (Works immediately via AudioManager)
            if (action.startsWith("vol_") || action == "play_pause") {
                HostService.instance?.adjustVolume(action)
                return json(JSONObject().put("ok", true).put("action", action))
            }

            val service = RemoteAccessibilityService.instance
                ?: return json(JSONObject()
                    .put("ok", false)
                    .put("error", "accessibility_disabled")
                    .put("message", "خدمة التحكم (Accessibility) غير مفعلة على الهاتف! يرجى تفعيلها من إعدادات الهاتف."),
                    Response.Status.OK)

            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels

            val ok = when {
                action == "tap" -> service.tap(
                    o.optDouble("x").toFloat(), o.optDouble("y").toFloat(),
                    screenW, screenH
                )
                action == "swipe" || action == "scroll" -> service.swipe(
                    o.optDouble("x1").toFloat(), o.optDouble("y1").toFloat(),
                    o.optDouble("x2").toFloat(), o.optDouble("y2").toFloat(),
                    screenW, screenH, o.optLong("duration", 300)
                )
                action.startsWith("dpad_") -> {
                    val dir = action.removePrefix("dpad_")
                    if (dir == "center" || dir == "ok") service.dpadCenter(screenW, screenH)
                    else service.dpad(dir, screenW, screenH)
                }
                action in listOf("back", "home", "recents", "notifications", "quick_settings", "power", "lock") ->
                    service.performGlobal(action)
                action == "type" -> service.typeText(o.optString("text"))
                else -> false
            }
            json(JSONObject().put("ok", ok).put("action", action))
        } catch (e: Exception) {
            json(JSONObject().put("ok", false).put("error", e.message ?: "bad_request"), Response.Status.BAD_REQUEST)
        }
    }

    // Media & Movie Hub on TV
    private fun handleMedia(s: IHTTPSession): Response {
        return try {
            if (s.method == Method.GET) {
                val arr = JSONArray()
                mediaDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.forEach {
                    val isVideo = it.name.endsWith(".mp4", true) || it.name.endsWith(".mkv", true) || it.name.endsWith(".webm", true)
                    arr.put(JSONObject()
                        .put("name", it.name)
                        .put("size", it.length())
                        .put("isVideo", isVideo)
                        .put("url", "/download/${it.name}"))
                }
                return json(JSONObject().put("media", arr))
            }

            val body = body(s)
            val o = JSONObject(body)
            val action = o.optString("action")

            if (action == "play_tv") {
                val fileName = o.optString("file")
                val target = File(mediaDir, fileName)
                if (target.exists()) {
                    MainActivity.instance?.playMovieOnTv(target)
                    return json(JSONObject().put("ok", true).put("playing", fileName))
                }
                return json(JSONObject().put("ok", false).put("error", "file_not_found"))
            }

            if (action == "stop_tv") {
                MainActivity.instance?.stopTvVideo()
                return json(JSONObject().put("ok", true))
            }

            // Upload movie/media file
            val tempFiles = HashMap<String, String>()
            s.parseBody(tempFiles)
            val tempPath = tempFiles["file"] ?: return json(JSONObject().put("error", "file_missing"), Response.Status.BAD_REQUEST)
            val name = s.parms["name"]?.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")?.take(120) ?: "media_${System.currentTimeMillis()}.mp4"
            val temp = File(tempPath)
            if (temp.isFile) {
                temp.copyTo(File(mediaDir, name), overwrite = true)
                MainActivity.instance?.showTvNotification("📁 تم حفظ وسائط جديدة: $name")
                return json(JSONObject().put("ok", true).put("name", name))
            }
            json(JSONObject().put("error", "upload_failed"), Response.Status.BAD_REQUEST)
        } catch (e: Exception) {
            json(JSONObject().put("error", e.message ?: "media_error"), Response.Status.BAD_REQUEST)
        }
    }

    // Voice Notes directly to TV Speakers
    private fun handleVoice(s: IHTTPSession): Response {
        return try {
            val tempFiles = HashMap<String, String>()
            s.parseBody(tempFiles)
            val tempPath = tempFiles["file"] ?: tempFiles["content"]
            val audioFile = if (tempPath != null) {
                val f = File(tempPath)
                val dest = File(voiceDir, "voice_${System.currentTimeMillis()}.mp3")
                f.copyTo(dest, overwrite = true)
                dest
            } else {
                val dest = File(voiceDir, "voice_${System.currentTimeMillis()}.mp3")
                FileOutputStream(dest).use { it.write(body(s).toByteArray()) }
                dest
            }

            // Play voice note out loud on TV speakers
            HostService.instance?.playVoiceAudio(audioFile)
            MainActivity.instance?.showTvNotification("🎙️ رسالة صوتية واردة من جهاز التحكم")

            json(JSONObject().put("ok", true).put("message", "played_on_tv"))
        } catch (e: Exception) {
            json(JSONObject().put("ok", false).put("error", e.message ?: "voice_failed"), Response.Status.BAD_REQUEST)
        }
    }

    private fun handleChat(s: IHTTPSession): Response {
        return try {
            if (s.method == Method.GET) {
                json(JSONObject().put("messages", database.getMessages()))
            } else {
                val o = JSONObject(body(s))
                val from = o.optString("from", "جهاز محلي")
                val text = o.optString("text").take(4000)
                if (text.isBlank()) return json(JSONObject().put("error", "empty_message"), Response.Status.BAD_REQUEST)
                val msg = database.addMessage(from, text)
                MainActivity.instance?.showTvNotification("💬 $from: $text")
                json(JSONObject().put("ok", true).put("message", msg))
            }
        } catch (e: Exception) {
            json(JSONObject().put("error", e.message ?: "bad_request"), Response.Status.BAD_REQUEST)
        }
    }

    private fun handleDeviceRegister(s: IHTTPSession): Response {
        return try {
            val o = JSONObject(body(s))
            val id = o.optString("id").trim()
            val name = o.optString("name", "Android TV").trim()
            if (id.isBlank()) return json(JSONObject().put("error", "device_id_required"), Response.Status.BAD_REQUEST)
            val ip = s.remoteIpAddress ?: ""
            val device = database.registerDevice(id, name, ip)
            json(JSONObject().put("ok", true).put("device", device))
        } catch (e: Exception) {
            json(JSONObject().put("ok", false).put("error", e.message ?: "bad_request"), Response.Status.BAD_REQUEST)
        }
    }

    private fun body(s: IHTTPSession): String {
        val map = HashMap<String, String>()
        s.parseBody(map)
        return map["postData"] ?: "{}"
    }

    private fun json(o: JSONObject, status: Response.Status = Response.Status.OK) =
        newFixedLengthResponse(status, "application/json; charset=utf-8", o.toString())

    companion object {
        private val PAGE = """<!doctype html>
<html lang="ar" dir="rtl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<title>LocalDrop DeX TV Remote</title>
<style>
:root{--bg:#070b14;--card:#101726;--border:#1e293b;--primary:#3b82f6;--primary-glow:rgba(59,130,246,0.35);--accent:#6366f1;--success:#10b981;--danger:#ef4444;--text:#f8fafc;--muted:#94a3b8}
*{box-sizing:border-box;margin:0;padding:0;user-select:none;-webkit-user-select:none;-webkit-tap-highlight-color:transparent}
body{background:var(--bg);color:var(--text);font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;min-height:100vh;display:flex;flex-direction:column}

/* Top App Header */
header{background:var(--card);border-bottom:1px solid var(--border);padding:10px 16px;display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:8px}
.brand{display:flex;align-items:center;gap:8px}
.brand h1{font-size:16px;font-weight:700;color:#fff}
.status-pill{font-size:11px;padding:3px 10px;border-radius:20px;display:inline-flex;align-items:center;gap:5px;font-weight:600}
.pill-green{background:#064e3b;color:#6ee7b7;border:1px solid #059669}
.pill-amber{background:#78350f;color:#fde68a;border:1px solid #d97706}
.pill-red{background:#7f1d1d;color:#fca5a5;border:1px solid #dc2626}
.dot{width:6px;height:6px;border-radius:50%;background:currentColor;display:inline-block}

/* Top PIN Auth */
.auth-box{display:flex;align-items:center;gap:6px}
.auth-box input{background:#1e293b;border:1px solid var(--border);color:#fff;padding:6px 10px;border-radius:8px;width:100px;text-align:center;font-size:14px;font-weight:700}
.auth-box button{background:var(--primary);color:#fff;border:0;padding:6px 12px;border-radius:8px;font-weight:700;cursor:pointer;font-size:12px}

/* Main Navigation Tabs */
.tabs{display:flex;justify-content:center;gap:4px;padding:8px 12px;background:#0b1120;border-bottom:1px solid var(--border);overflow-x:auto}
.tab-btn{background:transparent;border:0;color:var(--muted);padding:8px 14px;border-radius:8px;font-size:13px;font-weight:600;cursor:pointer;white-space:nowrap;transition:0.15s}
.tab-btn.active{background:#1e293b;color:#fff;box-shadow:0 2px 8px var(--primary-glow)}

/* Views */
main{flex:1;display:flex;justify-content:center;padding:12px}
.view-panel{width:100%;max-width:540px;display:none}
.view-panel.active{display:block}

/* ================= TV REMOTE CONTROLLER ================= */
.remote-wrapper{display:flex;flex-direction:column;align-items:center;gap:14px;margin:auto}

/* D-Pad Circle */
.dpad-container{position:relative;width:240px;height:240px;background:#0d1527;border:3px solid var(--border);border-radius:50%;box-shadow:0 12px 30px rgba(0,0,0,0.6);margin:8px 0}
.dpad-btn{position:absolute;background:transparent;border:0;color:#cbd5e1;cursor:pointer;font-size:22px;display:flex;align-items:center;justify-content:center;transition:0.12s}
.dpad-btn:active{color:var(--primary);transform:scale(0.9)}
.dpad-up{top:8px;left:50%;transform:translateX(-50%);width:70px;height:60px}
.dpad-down{bottom:8px;left:50%;transform:translateX(-50%);width:70px;height:60px}
.dpad-left{top:50%;left:8px;transform:translateY(-50%);width:60px;height:70px}
.dpad-right{top:50%;right:8px;transform:translateY(-50%);width:60px;height:70px}
.dpad-center{top:50%;left:50%;transform:translate(-50%,-50%);width:84px;height:84px;background:#1e293b;border-radius:50%;color:#fff;font-weight:700;font-size:15px;box-shadow:0 4px 12px rgba(0,0,0,0.5);border:2px solid #334155}
.dpad-center:active{background:var(--primary);border-color:#fff}

/* Trackpad (For Samsung DeX Mouse Navigation) */
.trackpad{width:100%;height:150px;background:#0b1324;border:2px solid var(--border);border-radius:16px;display:flex;flex-direction:column;align-items:center;justify-content:center;color:var(--muted);font-size:13px;touch-action:none;cursor:grab;box-shadow:inset 0 4px 12px rgba(0,0,0,0.4)}
.trackpad:active{border-color:var(--primary);cursor:grabbing}

/* Volume & Playback Deck */
.deck-row{display:flex;justify-content:space-between;width:100%;gap:8px}
.deck-btn{flex:1;background:#162032;border:1px solid var(--border);color:#fff;padding:12px;border-radius:12px;font-size:15px;font-weight:600;cursor:pointer;display:flex;flex-direction:column;align-items:center;gap:3px;transition:0.12s}
.deck-btn span{font-size:10px;color:var(--muted)}
.deck-btn:active{background:var(--primary);transform:scale(0.95)}

/* System Buttons */
.nav-row{display:flex;justify-content:space-around;width:100%;background:#101726;border:1px solid var(--border);border-radius:14px;padding:8px}
.nav-btn{background:transparent;border:0;color:#cbd5e1;font-size:18px;padding:10px 16px;border-radius:10px;cursor:pointer;display:flex;flex-direction:column;align-items:center;gap:2px}
.nav-btn span{font-size:10px;color:var(--muted)}
.nav-btn:active{color:var(--primary);transform:scale(0.92)}

/* Text Type Input */
.type-strip{display:flex;gap:6px;width:100%}
.type-strip input{flex:1;background:#162032;border:1px solid var(--border);color:#fff;padding:10px 14px;border-radius:10px;font-size:14px}
.type-strip button{background:var(--primary);color:#fff;border:0;padding:10px 16px;border-radius:10px;font-weight:700;cursor:pointer}

/* ================= SCREEN MIRROR ================= */
.phone-case{position:relative;background:#000;border:3px solid #334155;border-radius:20px;box-shadow:0 16px 36px rgba(0,0,0,0.8);overflow:hidden;touch-action:none;margin:auto}
#screen{display:block;max-height:68vh;max-width:92vw;width:auto;height:auto;object-fit:contain;cursor:crosshair}
.touch-ripple{position:absolute;width:28px;height:28px;border-radius:50%;background:rgba(59,130,246,0.6);border:2px solid #fff;transform:translate(-50%,-50%) scale(0.3);pointer-events:none;animation:rippleAnim 0.35s ease-out forwards}
@keyframes rippleAnim{to{transform:translate(-50%,-50%) scale(1.6);opacity:0}}

/* ================= MEDIA & MOVIES HUB ================= */
.media-card{background:var(--card);border:1px solid var(--border);border-radius:14px;padding:16px;width:100%}
.media-item{display:flex;align-items:center;justify-content:space-between;padding:12px;background:#162032;border-radius:10px;margin-bottom:8px}
.media-title{font-weight:600;font-size:14px;color:#fff;max-width:65%;word-break:break-all}
.play-tv-btn{background:#10b981;color:#fff;border:0;padding:8px 12px;border-radius:8px;font-size:12px;font-weight:700;cursor:pointer}
.stop-tv-btn{background:#ef4444;color:#fff;border:0;padding:10px;border-radius:10px;font-size:13px;font-weight:700;cursor:pointer;width:100%;margin-bottom:12px}

/* ================= VOICE & CHAT ================= */
.voice-deck{display:flex;flex-direction:column;align-items:center;padding:24px;background:#101726;border:1px solid var(--border);border-radius:16px;margin-bottom:16px}
.mic-btn{width:80px;height:80px;border-radius:50%;background:#ef4444;color:#fff;font-size:32px;border:4px solid #f87171;box-shadow:0 6px 20px rgba(239,68,68,0.4);cursor:pointer;display:flex;align-items:center;justify-content:center;transition:0.15s}
.mic-btn.recording{background:#dc2626;animation:pulseMic 1s infinite alternate}
@keyframes pulseMic{to{transform:scale(1.12);box-shadow:0 0 30px rgba(239,68,68,0.8)}}
#chat-msgs{height:35vh;overflow-y:auto;display:flex;flex-direction:column;gap:8px;padding-bottom:10px}
.chat-bubble{background:#162032;padding:8px 12px;border-radius:10px;font-size:13px;max-width:85%}
.chat-bubble.me{background:#2563eb;align-self:flex-start}
</style>
</head>
<body>

<header>
  <div class="brand">
    <h1 id="device-title">📺 LocalDrop DeX</h1>
    <div id="screen-badge" class="status-pill pill-amber"><span class="dot"></span> الشاشة...</div>
    <div id="access-badge" class="status-pill pill-amber"><span class="dot"></span> التحكم...</div>
  </div>
  <div class="auth-box">
    <input id="pin-input" type="password" placeholder="PIN" inputmode="numeric">
    <button onclick="saveAndConnect()">اتصال</button>
  </div>
</header>

<div class="tabs">
  <button class="tab-btn active" onclick="switchTab('remote-tab')">🎮 ريموت التلفاز</button>
  <button class="tab-btn" onclick="switchTab('screen-tab')">📱 بث الشاشة</button>
  <button class="tab-btn" onclick="switchTab('media-tab')">🎬 مكتبة الأفلام</button>
  <button class="tab-btn" onclick="switchTab('chat-tab')">🎙️ الصوت والمحادثة</button>
</div>

<main>
  <!-- TAB 1: TV REMOTE CONTROLLER -->
  <div id="remote-tab" class="view-panel active">
    <div class="remote-wrapper">
      <!-- Apple TV / Fire TV Style D-Pad -->
      <div class="dpad-container">
        <button class="dpad-btn dpad-up" onclick="sendAction('dpad_up')">▲</button>
        <button class="dpad-btn dpad-down" onclick="sendAction('dpad_down')">▼</button>
        <button class="dpad-btn dpad-left" onclick="sendAction('dpad_left')">◀</button>
        <button class="dpad-btn dpad-right" onclick="sendAction('dpad_right')">▶</button>
        <button class="dpad-btn dpad-center" onclick="sendAction('dpad_ok')">OK</button>
      </div>

      <!-- Samsung DeX Mouse Trackpad -->
      <div class="trackpad" id="trackpad">
        <span>🖱️ لوحة لمس الماوس (اسحب للتحريك | انقر للضغط)</span>
      </div>

      <!-- Volume & Media Deck -->
      <div class="deck-row">
        <button class="deck-btn" onclick="sendAction('vol_up')">🔊<span>رفع الصوت</span></button>
        <button class="deck-btn" onclick="sendAction('vol_down')">🔉<span>خفض الصوت</span></button>
        <button class="deck-btn" onclick="sendAction('vol_mute')">🔇<span>كتم الصوت</span></button>
        <button class="deck-btn" onclick="sendAction('play_pause')">⏯️<span>تشغيل/إيقاف</span></button>
      </div>

      <!-- Navigation System Keys -->
      <div class="nav-row">
        <button class="nav-btn" onclick="sendAction('recents')">▢<span>المهام</span></button>
        <button class="nav-btn" onclick="sendAction('home')">⭕<span>الرئيسية</span></button>
        <button class="nav-btn" onclick="sendAction('back')">◀<span>رجوع</span></button>
        <button class="nav-btn" onclick="sendAction('notifications')">🔔<span>إشعارات</span></button>
        <button class="nav-btn" onclick="sendAction('lock')">🔒<span>قفل</span></button>
      </div>

      <!-- Quick Text Typing -->
      <div class="type-strip">
        <input id="quick-text" placeholder="اكتب نصاً لإرساله للتلفاز مباشرة...">
        <button onclick="sendQuickText()">إرسال</button>
      </div>
    </div>
  </div>

  <!-- TAB 2: LIVE SCREEN MIRROR -->
  <div id="screen-tab" class="view-panel">
    <div style="display:flex;flex-direction:column;align-items:center">
      <div class="phone-case" id="phone-case">
        <img id="screen" alt="جاري عرض الشاشة...">
        <div id="ripple-layer"></div>
      </div>
    </div>
  </div>

  <!-- TAB 3: MEDIA & MOVIES HUB -->
  <div id="media-tab" class="view-panel">
    <div class="media-card">
      <button class="stop-tv-btn" onclick="stopTvMovie()">⏹️ إيقاف الفيديو على التلفاز والعودة للواجهة</button>
      
      <div style="display:flex;gap:8px;margin-bottom:16px">
        <input id="movie-uploader" type="file" style="flex:1;background:#162032;padding:8px;border-radius:8px;color:#fff">
        <button onclick="uploadMovieFile()" style="background:var(--primary);color:#fff;border:0;padding:8px 16px;border-radius:8px;font-weight:700;cursor:pointer">رفع الفيلم/الفيديو</button>
      </div>
      
      <h3 style="font-size:14px;color:var(--muted);margin-bottom:8px">قائمة الأفلام والوسائط المحفوظة على التلفاز:</h3>
      <div id="movies-list"></div>
    </div>
  </div>

  <!-- TAB 4: VOICE NOTES & CHAT -->
  <div id="chat-tab" class="view-panel">
    <div class="media-card">
      <!-- Voice Note Recorder Button -->
      <div class="voice-deck">
        <button class="mic-btn" id="mic-btn" onclick="toggleVoiceRecording()">🎙️</button>
        <p id="mic-status" style="margin-top:10px;font-size:13px;color:var(--muted)">اضغط للتحدث بصوتك مباشرة عبر سماعات التلفاز</p>
      </div>

      <div id="chat-msgs"></div>
      <div style="display:flex;gap:6px;margin-top:12px">
        <input id="chat-text" style="flex:1;background:#162032;border:1px solid var(--border);color:#fff;padding:10px;border-radius:8px" placeholder="اكتب رسالة للتلفاز...">
        <button onclick="sendChatMsg()" style="background:var(--primary);color:#fff;border:0;padding:10px 16px;border-radius:8px;font-weight:700;cursor:pointer">إرسال</button>
      </div>
    </div>
  </div>
</main>

<script>
let pin = '';
let streamActive = false;
let screenImg = document.getElementById('screen');
let phoneCase = document.getElementById('phone-case');
let screenW = 1920, screenH = 1080;

window.addEventListener('DOMContentLoaded', () => {
  const urlParams = new URLSearchParams(window.location.search);
  const qPin = urlParams.get('pin');
  if (qPin) {
    pin = qPin;
    localStorage.setItem('ld_pin', pin);
  } else {
    pin = localStorage.getItem('ld_pin') || '';
  }
  document.getElementById('pin-input').value = pin;
  checkStatus();
  setInterval(checkStatus, 4000);
  startStream();
  setupTrackpad();
});

function saveAndConnect() {
  pin = document.getElementById('pin-input').value.trim();
  localStorage.setItem('ld_pin', pin);
  checkStatus();
  restartStream();
}

async function api(path, opt = {}) {
  opt.headers = { ...(opt.headers || {}), 'X-Host-Pin': pin };
  let r = await fetch(path, opt);
  if (r.status === 401) {
    alert('رمز الدخول غير صحيح! يرجى إدخال الرمز المعروض على شاشة التلفاز.');
    throw Error('unauthorized');
  }
  return r;
}

async function checkStatus() {
  try {
    let r = await fetch('/api/info');
    if (!r.ok) return;
    let d = await r.json();
    document.getElementById('device-title').textContent = d.name || 'LocalDrop DeX';
    screenW = d.screenWidth || 1920;
    screenH = d.screenHeight || 1080;

    let sb = document.getElementById('screen-badge');
    if (d.screenReady) {
      sb.className = 'status-pill pill-green';
      sb.innerHTML = '<span class="dot"></span> الشاشة: نشطة';
    } else {
      sb.className = 'status-pill pill-amber';
      sb.innerHTML = '<span class="dot"></span> الشاشة: بانتظار الموافقة';
    }

    let ab = document.getElementById('access-badge');
    if (d.accessibilityReady) {
      ab.className = 'status-pill pill-green';
      ab.innerHTML = '<span class="dot"></span> التحكم: متصل';
    } else {
      ab.className = 'status-pill pill-red';
      ab.innerHTML = '<span class="dot"></span> التحكم غير مفعل';
    }
  } catch(e) {}
}

async function sendAction(act) {
  try {
    await api('/api/input', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ action: act })
    });
  } catch(e) {}
}

async function sendQuickText() {
  const inp = document.getElementById('quick-text');
  const txt = inp.value;
  if (!txt) return;
  await api('/api/input', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ action: 'type', text: txt })
  });
  inp.value = '';
}

// Trackpad Mouse Emulation for Samsung DeX
function setupTrackpad() {
  const pad = document.getElementById('trackpad');
  let startX = 0, startY = 0, lastX = 0, lastY = 0, startTime = 0;
  let isMoving = false;

  pad.addEventListener('pointerdown', e => {
    e.preventDefault();
    startX = e.clientX;
    startY = e.clientY;
    lastX = e.clientX;
    lastY = e.clientY;
    startTime = Date.now();
    isMoving = false;
    pad.setPointerCapture(e.pointerId);
  });

  pad.addEventListener('pointermove', e => {
    if (!pad.hasPointerCapture(e.pointerId)) return;
    let dx = e.clientX - lastX;
    let dy = e.clientY - lastY;
    if (Math.hypot(e.clientX - startX, e.clientY - startY) > 6) isMoving = true;
    lastX = e.clientX;
    lastY = e.clientY;

    if (Math.abs(dx) > 0 || Math.abs(dy) > 0) {
      // Send smooth gesture
      let stepX = dx * 0.005;
      let stepY = dy * 0.005;
      api('/api/input', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action: 'swipe', x1: 0.5, y1: 0.5, x2: 0.5 + stepX, y2: 0.5 + stepY, duration: 60 })
      });
    }
  });

  pad.addEventListener('pointerup', e => {
    if (!isMoving && (Date.now() - startTime) < 300) {
      // Tap Click
      sendAction('dpad_ok');
    }
  });
}

// Live Screen Stream
function startStream() {
  streamActive = true;
  pollFrame();
}
function restartStream() {
  streamActive = false;
  setTimeout(() => { streamActive = true; pollFrame(); }, 200);
}
async function pollFrame() {
  if (!streamActive) return;
  try {
    let r = await fetch('/api/screen?pin=' + encodeURIComponent(pin) + '&t=' + Date.now());
    if (r.ok) {
      let blob = await r.blob();
      let url = URL.createObjectURL(blob);
      let old = screenImg.dataset.url;
      screenImg.src = url;
      screenImg.dataset.url = url;
      if (old) URL.revokeObjectURL(old);
    }
  } catch(e) {}
  if (streamActive) setTimeout(pollFrame, 65);
}

// Media / Movies Hub
async function loadMovies() {
  try {
    let r = await api('/api/media');
    let d = await r.json();
    let box = document.getElementById('movies-list');
    box.innerHTML = (d.media || []).map(m =>
      '<div class="media-item"><div class="media-title">🎬 ' + esc(m.name) + '<br><small style="color:var(--muted)">' + (m.size / (1024*1024)).toFixed(1) + ' MB</small></div>' +
      '<button class="play-tv-btn" onclick="playTvMovie(\'' + esc(m.name) + '\')">▶ تشغيل على التلفاز</button></div>'
    ).join('') || '<p style="color:var(--muted);text-align:center;padding:16px">لا توجد وسائط محفوظة حالياً</p>';
  } catch(e) {}
}

async function playTvMovie(filename) {
  await api('/api/media', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ action: 'play_tv', file: filename })
  });
  alert('بدأ تشغيل: ' + filename + ' على شاشة التلفاز!');
}

async function stopTvMovie() {
  await api('/api/media', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ action: 'stop_tv' })
  });
}

async function uploadMovieFile() {
  let f = document.getElementById('movie-uploader').files[0];
  if (!f) return alert('اختر ملف فيديو أولاً');
  let fd = new FormData();
  fd.append('file', f);
  let r = await fetch('/api/media?name=' + encodeURIComponent(f.name), {
    method: 'POST',
    headers: { 'X-Host-Pin': pin },
    body: fd
  });
  if (r.ok) {
    alert('تم حفظ الفيديو على التلفاز بنجاح');
    loadMovies();
  } else {
    alert('فشل حفظ الفيديو');
  }
}

// Voice Note Recording to TV Speakers
let mediaRecorder = null;
let audioChunks = [];
let isRecording = false;

async function toggleVoiceRecording() {
  const btn = document.getElementById('mic-btn');
  const status = document.getElementById('mic-status');

  if (!isRecording) {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      mediaRecorder = new MediaRecorder(stream);
      audioChunks = [];
      mediaRecorder.ondataavailable = e => audioChunks.push(e.data);
      mediaRecorder.onstop = async () => {
        const audioBlob = new Blob(audioChunks, { type: 'audio/mp3' });
        let fd = new FormData();
        fd.append('file', audioBlob);
        status.textContent = 'جاري إرسال وبث الصوت على سماعات التلفاز...';
        await fetch('/api/voice', {
          method: 'POST',
          headers: { 'X-Host-Pin': pin },
          body: fd
        });
        status.textContent = 'تم تشغيل الصوت على سماعات التلفاز بنجاح!';
        setTimeout(() => { status.textContent = 'اضغط للتحدث بصوتك مباشرة عبر سماعات التلفاز'; }, 3000);
      };
      mediaRecorder.start();
      isRecording = true;
      btn.classList.add('recording');
      status.textContent = 'جاري التسجيل الآن... اضغط مرة أخرى للإرسال والتحدث في التلفاز';
    } catch(e) {
      alert('تعذر فتح الميكروفون: ' + e.message);
    }
  } else {
    if (mediaRecorder && mediaRecorder.state !== 'inactive') {
      mediaRecorder.stop();
      mediaRecorder.stream.getTracks().forEach(t => t.stop());
    }
    isRecording = false;
    btn.classList.remove('recording');
  }
}

// Chat
async function loadChat() {
  try {
    let r = await api('/api/chat');
    let d = await r.json();
    let box = document.getElementById('chat-msgs');
    box.innerHTML = (d.messages || []).map(m =>
      '<div class="chat-bubble ' + (m.from === 'ريموت' ? 'me' : '') + '"><b>' + esc(m.from) + '</b> <small>' + m.time + '</small><br>' + esc(m.text) + '</div>'
    ).join('');
    box.scrollTop = box.scrollHeight;
  } catch(e) {}
}

async function sendChatMsg() {
  const inp = document.getElementById('chat-text');
  const txt = inp.value.trim();
  if (!txt) return;
  await api('/api/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ from: 'ريموت', text: txt })
  });
  inp.value = '';
  loadChat();
}

function switchTab(id) {
  document.querySelectorAll('.view-panel').forEach(p => p.classList.remove('active'));
  document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
  document.getElementById(id).classList.add('active');
  event.target.classList.add('active');
  if (id === 'media-tab') loadMovies();
  if (id === 'chat-tab') loadChat();
}

function esc(s) {
  return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
</script>
</body>
</html>"""
    }
}
