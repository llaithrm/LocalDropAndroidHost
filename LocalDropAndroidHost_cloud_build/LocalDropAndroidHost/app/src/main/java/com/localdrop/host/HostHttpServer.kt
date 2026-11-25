package com.localdrop.host

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

class HostHttpServer(
    private val ctx: Context,
    private val frame: AtomicReference<ByteArray?>
) : NanoHTTPD("0.0.0.0", 8765) {

    private val filesDir = File(ctx.getExternalFilesDir(null), "Shared").apply { mkdirs() }
    private val chat = mutableListOf<JSONObject>()
    private var msgId = 0

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
            val target = File(filesDir, name).canonicalFile
            if (!target.path.startsWith(filesDir.canonicalPath + File.separator) || !target.isFile) {
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
            val model = "${Build.MANUFACTURER.capitalize(Locale.getDefault())} ${Build.MODEL}"
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
            "/api/chat" -> handleChat(session)
            "/api/files" -> handleFiles(session)
            "/api/devices" -> json(JSONObject().put("devices", org.json.JSONArray().put(
                JSONObject().put("name", Build.MODEL ?: "الهاتف").put("type", "Android Host").put("online", true)
            )))
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
            val service = RemoteAccessibilityService.instance
                ?: return json(JSONObject()
                    .put("ok", false)
                    .put("error", "accessibility_disabled")
                    .put("message", "خدمة التحكم (Accessibility) غير مفعلة على الهاتف! يرجى تفعيلها من إعدادات الهاتف."),
                    Response.Status.OK)

            val action = o.optString("action")
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels

            val ok = when (action) {
                "tap" -> service.tap(
                    o.optDouble("x").toFloat(), o.optDouble("y").toFloat(),
                    screenW, screenH
                )
                "swipe", "scroll" -> service.swipe(
                    o.optDouble("x1").toFloat(), o.optDouble("y1").toFloat(),
                    o.optDouble("x2").toFloat(), o.optDouble("y2").toFloat(),
                    screenW, screenH, o.optLong("duration", 300)
                )
                "back", "home", "recents", "notifications", "quick_settings", "power", "lock" ->
                    service.performGlobal(action)
                "type" -> service.typeText(o.optString("text"))
                else -> false
            }
            json(JSONObject().put("ok", ok).put("action", action))
        } catch (e: Exception) {
            json(JSONObject().put("ok", false).put("error", e.message ?: "bad_request"), Response.Status.BAD_REQUEST)
        }
    }

    private fun handleChat(s: IHTTPSession): Response {
        return try {
            if (s.method == Method.GET) {
                val arr = org.json.JSONArray()
                synchronized(chat) { chat.forEach { arr.put(it) } }
                json(JSONObject().put("messages", arr))
            } else {
                val o = JSONObject(body(s))
                val msg = JSONObject().put("id", ++msgId).put("from", o.optString("from", "متصفح"))
                    .put("text", o.optString("text").take(4000))
                    .put("time", SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()))
                synchronized(chat) { chat.add(msg); if (chat.size > 500) chat.removeAt(0) }
                json(JSONObject().put("ok", true).put("message", msg))
            }
        } catch (_: Exception) {
            json(JSONObject().put("error", "bad_request"), Response.Status.BAD_REQUEST)
        }
    }

    private fun handleFiles(s: IHTTPSession): Response {
        return try {
            if (s.method == Method.GET) {
                val arr = org.json.JSONArray()
                filesDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.forEach {
                    arr.put(JSONObject().put("name", it.name).put("size", it.length()).put("url", "/download/${it.name}"))
                }
                return json(JSONObject().put("files", arr))
            }
            val tempFiles = HashMap<String, String>()
            s.parseBody(tempFiles)
            val tempPath = tempFiles["file"]
                ?: return json(JSONObject().put("error", "file_missing"), Response.Status.BAD_REQUEST)
            val name = s.parms["name"]?.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")?.take(120)
                ?: return json(JSONObject().put("error", "name_required"), Response.Status.BAD_REQUEST)
            val temp = File(tempPath)
            if (!temp.isFile) return json(JSONObject().put("error", "file_missing"), Response.Status.BAD_REQUEST)
            temp.copyTo(File(filesDir, name), overwrite = true)
            json(JSONObject().put("ok", true).put("name", name))
        } catch (e: Exception) {
            json(JSONObject().put("error", e.message ?: "upload_failed"), Response.Status.BAD_REQUEST)
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
<title>LocalDrop Remote Control</title>
<style>
:root{--bg:#090d16;--card:#111927;--card-border:#1f293d;--primary:#3b82f6;--primary-hover:#2563eb;--success:#22c55e;--danger:#ef4444;--warning:#f59e0b;--text:#f8fafc;--text-muted:#94a3b8}
*{box-sizing:border-box;margin:0;padding:0;user-select:none;-webkit-user-select:none}
body{background:var(--bg);color:var(--text);font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;min-height:100vh;display:flex;flex-direction:column}
header{background:var(--card);border-bottom:1px solid var(--card-border);padding:10px 16px;display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:10px}
.brand{display:flex;align-items:center;gap:10px}
.brand h1{font-size:17px;font-weight:700;color:#fff}
.status-pill{font-size:12px;padding:3px 10px;border-radius:20px;display:inline-flex;align-items:center;gap:6px}
.pill-green{background:#064e3b;color:#6ee7b7;border:1px solid #059669}
.pill-red{background:#7f1d1d;color:#fca5a5;border:1px solid #dc2626}
.pill-amber{background:#78350f;color:#fde68a;border:1px solid #d97706}
.dot{width:7px;height:7px;border-radius:50%;background:currentColor;display:inline-block}

.top-auth{display:flex;align-items:center;gap:8px}
.top-auth input{background:#1e293b;border:1px solid var(--card-border);color:#fff;padding:6px 12px;border-radius:8px;width:120px;text-align:center;font-size:14px;font-weight:600;letter-spacing:1px}
.top-auth button{background:var(--primary);color:#fff;border:0;padding:7px 14px;border-radius:8px;font-weight:600;cursor:pointer;font-size:13px;transition:0.15s}
.top-auth button:hover{background:var(--primary-hover)}

.tabs{display:flex;justify-content:center;gap:6px;padding:8px 12px;background:#0d1424;border-bottom:1px solid var(--card-border)}
.tab-btn{background:transparent;border:0;color:var(--text-muted);padding:8px 16px;border-radius:8px;font-size:13px;font-weight:600;cursor:pointer;transition:0.15s}
.tab-btn.active{background:#1e293b;color:#fff}

main{flex:1;display:flex;justify-content:center;align-items:flex-start;padding:12px}
.view-panel{width:100%;max-width:960px;display:none}
.view-panel.active{display:block}

/* Phone Container */
.phone-wrapper{display:flex;flex-direction:column;align-items:center;margin:auto}
.phone-case{position:relative;background:#000;border:3px solid #334155;border-radius:24px;box-shadow:0 20px 40px rgba(0,0,0,0.7);overflow:hidden;touch-action:none;display:inline-block}
#screen{display:block;max-height:70vh;max-width:90vw;width:auto;height:auto;object-fit:contain;cursor:crosshair}
.touch-ripple{position:absolute;width:28px;height:28px;border-radius:50%;background:rgba(59,130,246,0.6);border:2px solid #fff;transform:translate(-50%,-50%) scale(0.3);pointer-events:none;animation:rippleAnim 0.35s ease-out forwards}
@keyframes rippleAnim{to{transform:translate(-50%,-50%) scale(1.6);opacity:0}}

/* Virtual Navigation Bar */
.nav-bar{background:#0f172a;border-top:1px solid var(--card-border);border-radius:0 0 20px 20px;display:flex;justify-content:space-around;padding:10px 4px;width:100%}
.nav-btn{background:transparent;border:0;color:#cbd5e1;font-size:17px;padding:8px 14px;border-radius:8px;cursor:pointer;transition:0.15s;display:flex;flex-direction:column;align-items:center;gap:2px}
.nav-btn span{font-size:10px;color:var(--text-muted)}
.nav-btn:hover{background:#1e293b;color:#fff}
.nav-btn:active{transform:scale(0.92)}

/* Quick Actions Strip */
.quick-bar{display:flex;flex-wrap:wrap;gap:8px;margin-top:12px;justify-content:center;max-width:540px}
.action-btn{background:#1e293b;border:1px solid var(--card-border);color:#fff;padding:8px 14px;border-radius:10px;font-size:12px;font-weight:600;cursor:pointer;display:inline-flex;align-items:center;gap:6px;transition:0.15s}
.action-btn:hover{background:#334155;border-color:#475569}
.action-btn:active{transform:scale(0.96)}

/* Type Bar */
.type-box{display:flex;gap:6px;width:100%;max-width:540px;margin-top:10px}
.type-box input{flex:1;background:#1e293b;border:1px solid var(--card-border);color:#fff;padding:10px 14px;border-radius:10px;font-size:14px;outline:none}
.type-box input:focus{border-color:var(--primary)}
.type-box button{background:var(--primary);color:#fff;border:0;padding:10px 16px;border-radius:10px;font-weight:600;cursor:pointer}

/* Chat & Files */
.card{background:var(--card);border:1px solid var(--card-border);border-radius:14px;padding:16px;margin:auto;max-width:700px}
#messages{height:50vh;overflow-y:auto;display:flex;flex-direction:column;gap:8px;padding-bottom:10px}
.msg{background:#1e293b;padding:8px 12px;border-radius:10px;max-width:85%}
.msg.me{background:#1d4ed8;align-self:flex-start}
.file-item{display:flex;align-items:center;justify-content:space-between;padding:10px;border-bottom:1px solid var(--card-border)}
.file-item a{color:#38bdf8;text-decoration:none;font-weight:600}
.notice-banner{background:#451a03;border:1px solid #b45309;color:#fde68a;padding:10px 16px;border-radius:10px;margin-bottom:12px;font-size:13px;display:none;text-align:center}
</style>
</head>
<body>

<header>
  <div class="brand">
    <h1 id="device-title">LocalDrop Remote</h1>
    <div id="screen-badge" class="status-pill pill-amber"><span class="dot"></span> جاري التحقق...</div>
    <div id="access-badge" class="status-pill pill-amber"><span class="dot"></span> التحكم...</div>
  </div>
  <div class="top-auth">
    <input id="pin-input" type="password" placeholder="رمز الدخول" inputmode="numeric">
    <button onclick="saveAndConnect()">اتصال</button>
  </div>
</header>

<div class="tabs">
  <button class="tab-btn active" onclick="switchTab('screen-tab')">📱 التحكم بالشاشة</button>
  <button class="tab-btn" onclick="switchTab('chat-tab')">💬 المحادثة</button>
  <button class="tab-btn" onclick="switchTab('files-tab')">📁 الملفات</button>
</div>

<main>
  <div id="screen-tab" class="view-panel active">
    <div id="warning-banner" class="notice-banner"></div>
    
    <div class="phone-wrapper">
      <div class="phone-case" id="phone-case">
        <img id="screen" alt="جارٍ تحميل الشاشة...">
        <div id="ripple-layer"></div>
        <div class="nav-bar">
          <button class="nav-btn" onclick="sendAction('recents')" title="التطبيقات المفتوحة">▢<span>المهام</span></button>
          <button class="nav-btn" onclick="sendAction('home')" title="الشاشة الرئيسية">⭕<span>الرئيسية</span></button>
          <button class="nav-btn" onclick="sendAction('back')" title="رجوع">◀<span>رجوع</span></button>
        </div>
      </div>

      <div class="type-box">
        <input id="text-to-type" placeholder="اكتب نصاً لإرساله للهاتف مباشرة...">
        <button onclick="sendTypedText()">إرسال</button>
      </div>

      <div class="quick-bar">
        <button class="action-btn" onclick="sendAction('notifications')">🔔 سحب الإشعارات</button>
        <button class="action-btn" onclick="sendAction('quick_settings')">⚙️ الإعدادات السريعة</button>
        <button class="action-btn" onclick="sendAction('lock')">🔒 قفل الهاتف</button>
        <button class="action-btn" onclick="restartStream()">🔄 إعادة تشغيل البث</button>
      </div>
    </div>
  </div>

  <div id="chat-tab" class="view-panel">
    <div class="card">
      <div id="messages"></div>
      <div style="display:flex;gap:8px;margin-top:12px">
        <input id="chat-input" style="flex:1;background:#1e293b;border:1px solid var(--card-border);color:#fff;padding:10px;border-radius:8px" placeholder="اكتب رسالة...">
        <button onclick="sendChatMessage()" style="background:var(--primary);color:#fff;border:0;padding:10px 18px;border-radius:8px;cursor:pointer">إرسال</button>
      </div>
    </div>
  </div>

  <div id="files-tab" class="view-panel">
    <div class="card">
      <div style="display:flex;gap:8px;margin-bottom:16px">
        <input id="file-uploader" type="file" style="flex:1;background:#1e293b;padding:8px;border-radius:8px;color:#fff">
        <button onclick="uploadSelectedFile()" style="background:var(--primary);color:#fff;border:0;padding:8px 16px;border-radius:8px;cursor:pointer">رفع الملف</button>
      </div>
      <div id="files-list"></div>
    </div>
  </div>
</main>

<script>
let pin = '';
let streamActive = false;
let screenImg = document.getElementById('screen');
let phoneCase = document.getElementById('phone-case');
let screenW = 1080, screenH = 1920;

// Initialize
window.addEventListener('DOMContentLoaded', () => {
  const urlParams = new URLSearchParams(window.location.search);
  const qPin = urlParams.get('pin');
  if (qPin) {
    pin = qPin;
    localStorage.setItem('ld_pin', pin);
    document.getElementById('pin-input').value = pin;
  } else {
    pin = localStorage.getItem('ld_pin') || '';
    document.getElementById('pin-input').value = pin;
  }
  checkStatus();
  setInterval(checkStatus, 4000);
  startStream();
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
    alert('رمز الدخول غير صحيح! يرجى إدخال الرمز المعروض على شاشة الهاتف.');
    throw Error('unauthorized');
  }
  return r;
}

async function checkStatus() {
  try {
    let r = await fetch('/api/info');
    if (!r.ok) return;
    let d = await r.json();
    document.getElementById('device-title').textContent = d.name || 'LocalDrop Remote';
    screenW = d.screenWidth || 1080;
    screenH = d.screenHeight || 1920;

    let sb = document.getElementById('screen-badge');
    if (d.screenReady) {
      sb.className = 'status-pill pill-green';
      sb.innerHTML = '<span class="dot"></span> بث الشاشة: نشط';
    } else {
      sb.className = 'status-pill pill-amber';
      sb.innerHTML = '<span class="dot"></span> بث الشاشة: بانتظار الموافقة في الهاتف';
    }

    let ab = document.getElementById('access-badge');
    let warn = document.getElementById('warning-banner');
    if (d.accessibilityReady) {
      ab.className = 'status-pill pill-green';
      ab.innerHTML = '<span class="dot"></span> التحكم: متصل';
      warn.style.display = 'none';
    } else {
      ab.className = 'status-pill pill-red';
      ab.innerHTML = '<span class="dot"></span> التحكم غير مفعل';
      warn.style.display = 'block';
      warn.innerHTML = '⚠️ خدمة التحكم (Accessibility) غير مفعلة على الهاتف! افتح التطبيق في الهاتف واضغط على زر «تفعيل خدمة التحكم» لتتمكن من اللمس واستخدام الأزرار.';
    }
  } catch(e) {}
}

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
  if (streamActive) {
    setTimeout(pollFrame, 65); // ~15 FPS responsive stream
  }
}

// Touch & Gesture Handling
let pointerDown = null;

function getNormalizedCoords(e) {
  const rect = screenImg.getBoundingClientRect();
  const naturalRatio = (screenImg.naturalWidth || screenW) / (screenImg.naturalHeight || screenH);
  const elementRatio = rect.width / rect.height;

  let actualW, actualH, offsetX, offsetY;
  if (elementRatio > naturalRatio) {
    actualH = rect.height;
    actualW = rect.height * naturalRatio;
    offsetX = (rect.width - actualW) / 2;
    offsetY = 0;
  } else {
    actualW = rect.width;
    actualH = rect.width / naturalRatio;
    offsetX = 0;
    offsetY = (rect.height - actualH) / 2;
  }

  const clientX = e.clientX - rect.left - offsetX;
  const clientY = e.clientY - rect.top - offsetY;

  const nx = clientX / actualW;
  const ny = clientY / actualH;

  if (nx < 0 || nx > 1 || ny < 0 || ny > 1) return null;
  return { x: nx, y: ny, px: e.clientX, py: e.clientY };
}

screenImg.addEventListener('pointerdown', e => {
  e.preventDefault();
  const c = getNormalizedCoords(e);
  if (!c) return;
  pointerDown = { ...c, time: Date.now() };
  screenImg.setPointerCapture(e.pointerId);
});

screenImg.addEventListener('pointerup', async e => {
  e.preventDefault();
  if (!pointerDown) return;
  const start = pointerDown;
  pointerDown = null;

  const end = getNormalizedCoords(e);
  if (!end) return;

  const dist = Math.hypot(end.x - start.x, end.y - start.y);
  const duration = Date.now() - start.time;

  showRipple(end.px, end.py);

  if (dist < 0.03) {
    // Tap
    await sendInput({ action: 'tap', x: end.x, y: end.y });
  } else {
    // Swipe
    await sendInput({
      action: 'swipe',
      x1: start.x, y1: start.y,
      x2: end.x, y2: end.y,
      duration: Math.max(120, Math.min(duration, 900))
    });
  }
});

function showRipple(cx, cy) {
  const rect = phoneCase.getBoundingClientRect();
  const dot = document.createElement('div');
  dot.className = 'touch-ripple';
  dot.style.left = (cx - rect.left) + 'px';
  dot.style.top = (cy - rect.top) + 'px';
  document.getElementById('ripple-layer').appendChild(dot);
  setTimeout(() => dot.remove(), 400);
}

async function sendInput(data) {
  try {
    let r = await api('/api/input', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data)
    });
    let d = await r.json();
    if (d && !d.ok && d.message) {
      alert(d.message);
    }
  } catch(e) {}
}

async function sendAction(act) {
  await sendInput({ action: act });
}

async function sendTypedText() {
  const inp = document.getElementById('text-to-type');
  const txt = inp.value;
  if (!txt) return;
  await sendInput({ action: 'type', text: txt });
  inp.value = '';
}

function switchTab(id) {
  document.querySelectorAll('.view-panel').forEach(p => p.classList.remove('active'));
  document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
  document.getElementById(id).classList.add('active');
  event.target.classList.add('active');
  if (id === 'chat-tab') loadChat();
  if (id === 'files-tab') loadFiles();
}

async function loadChat() {
  try {
    let r = await api('/api/chat');
    let d = await r.json();
    let box = document.getElementById('messages');
    box.innerHTML = (d.messages || []).map(m => 
      '<div class="msg ' + (m.from === 'متصفح' ? 'me' : '') + '"><b>' + esc(m.from) + '</b> <small>' + m.time + '</small><br>' + esc(m.text) + '</div>'
    ).join('');
    box.scrollTop = box.scrollHeight;
  } catch(e) {}
}

async function sendChatMessage() {
  let inp = document.getElementById('chat-input');
  let text = inp.value.trim();
  if (!text) return;
  await api('/api/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text, from: 'متصفح' })
  });
  inp.value = '';
  loadChat();
}

async function loadFiles() {
  try {
    let r = await api('/api/files');
    let d = await r.json();
    let list = document.getElementById('files-list');
    list.innerHTML = (d.files || []).map(f =>
      '<div class="file-item"><a href="' + f.url + '?pin=' + encodeURIComponent(pin) + '" target="_blank">' + esc(f.name) + '</a><span>' + (f.size / 1024).toFixed(1) + ' KB</span></div>'
    ).join('') || '<p style="color:var(--text-muted);text-align:center;padding:20px">لا توجد ملفات</p>';
  } catch(e) {}
}

async function uploadSelectedFile() {
  let f = document.getElementById('file-uploader').files[0];
  if (!f) return alert('اختر ملفاً أولاً');
  let fd = new FormData();
  fd.append('file', f);
  let r = await fetch('/api/files?name=' + encodeURIComponent(f.name), {
    method: 'POST',
    headers: { 'X-Host-Pin': pin },
    body: fd
  });
  if (r.ok) {
    alert('تم رفع الملف بنجاح');
    loadFiles();
  } else {
    alert('فشل رفع الملف');
  }
}

function esc(s) {
  return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
</script>
</body>
</html>"""
    }
}
