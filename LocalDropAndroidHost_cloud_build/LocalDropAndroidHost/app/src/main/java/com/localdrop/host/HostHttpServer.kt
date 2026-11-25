package com.localdrop.host

import android.content.Context
import android.os.Environment
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
    private val prefs = ctx.getSharedPreferences("host", Context.MODE_PRIVATE)
    private val chat = mutableListOf<JSONObject>()
    private var msgId = 0

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        if (uri == "/" || uri == "/index.html") return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", PAGE)
        if (uri.startsWith("/download/")) {
            val supplied = session.parms["pin"] ?: session.headers["x-host-pin"]
            if (supplied != HostService.getPin(ctx)) return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Unauthorized")
            val name = uri.removePrefix("/download/").substringBefore("?")
            val target = File(filesDir, name).canonicalFile
            if (!target.path.startsWith(filesDir.canonicalPath + File.separator) || !target.isFile)
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            return newFixedLengthResponse(Response.Status.OK, "application/octet-stream", target.inputStream(), target.length())
        }
        if (uri == "/api/info") {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            return json(JSONObject().put("name", "Samsung Galaxy S8+").put("screenWidth", dm.widthPixels)
                .put("screenHeight", dm.heightPixels).put("screenReady", frame.get() != null))
        }
        if (!authorized(session)) return json(JSONObject().put("error", "رمز الدخول غير صحيح"), Response.Status.UNAUTHORIZED)
        return when (uri) {
            "/api/screen" -> {
                val bytes = frame.get()
                if (bytes == null) json(JSONObject().put("error", "screen_not_started"), Response.Status.SERVICE_UNAVAILABLE)
                else newFixedLengthResponse(Response.Status.OK, "image/jpeg", bytes.inputStream(), bytes.size.toLong())
            }
            "/api/input" -> handleInput(session)
            "/api/chat" -> handleChat(session)
            "/api/files" -> handleFiles(session)
            "/api/devices" -> json(JSONObject().put("devices", org.json.JSONArray().put(
                JSONObject().put("name", "هذا الهاتف").put("type", "Android Host").put("online", true)
            )))
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
    }

    private fun authorized(s: IHTTPSession): Boolean =
        s.headers["x-host-pin"] == HostService.getPin(ctx)

    private fun handleInput(s: IHTTPSession): Response {
        return try {
            val body = body(s)
            val o = JSONObject(body)
            val service = RemoteAccessibilityService.instance
                ?: return json(JSONObject().put("error", "accessibility_disabled"), Response.Status.SERVICE_UNAVAILABLE)
            val action = o.optString("action")
            val info = JSONObject().put("ok", true)
            when (action) {
                "tap" -> service.tap(o.optDouble("x").toFloat(), o.optDouble("y").toFloat(),
                    o.optInt("screenWidth", 1080), o.optInt("screenHeight", 1920))
                "swipe", "scroll" -> service.swipe(o.optDouble("x1").toFloat(), o.optDouble("y1").toFloat(),
                    o.optDouble("x2").toFloat(), o.optDouble("y2").toFloat(),
                    o.optInt("screenWidth", 1080), o.optInt("screenHeight", 1920), o.optLong("duration", 350))
                else -> return json(JSONObject().put("error", "unsupported_action"), Response.Status.BAD_REQUEST)
            }
            json(info)
        } catch (e: Exception) { json(JSONObject().put("error", e.message ?: "bad_request"), Response.Status.BAD_REQUEST) }
    }

    private fun handleChat(s: IHTTPSession): Response {
        return try {
            if (s.method == Method.GET) {
                val arr = org.json.JSONArray()
                synchronized(chat) { chat.forEach { arr.put(it) } }
                json(JSONObject().put("messages", arr))
            } else {
                val o = JSONObject(body(s))
                val msg = JSONObject().put("id", ++msgId).put("from", o.optString("from", "جهاز"))
                    .put("text", o.optString("text").take(4000))
                    .put("time", SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()))
                synchronized(chat) { chat.add(msg); if (chat.size > 500) chat.removeAt(0) }
                json(JSONObject().put("ok", true).put("message", msg))
            }
        } catch (_: Exception) { json(JSONObject().put("error", "bad_request"), Response.Status.BAD_REQUEST) }
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
        } catch (e: Exception) { json(JSONObject().put("error", e.message ?: "upload_failed"), Response.Status.BAD_REQUEST) }
    }

    private fun body(s: IHTTPSession): String {
        val map = HashMap<String, String>()
        s.parseBody(map)
        return map["postData"] ?: "{}"
    }

    private fun json(o: JSONObject, status: Response.Status = Response.Status.OK) =
        newFixedLengthResponse(status, "application/json; charset=utf-8", o.toString())

    companion object {
        private val PAGE = """<!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no"><title>LocalDrop Android Host</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#0b1220;color:#e8eef8;font:15px system-ui}header{padding:15px;background:#121d30;position:sticky;top:0}h2{margin:0;font-size:19px}.wrap{padding:12px;max-width:1100px;margin:auto}.card{background:#142238;border:1px solid #263a55;border-radius:14px;padding:12px;margin:10px 0}.row{display:flex;gap:8px;flex-wrap:wrap}button,input{border:0;border-radius:9px;padding:11px;background:#243a56;color:white}button{cursor:pointer}input{width:100%;background:#0e1929}#screen{width:100%;max-height:72vh;object-fit:contain;background:#05090f;touch-action:none;border-radius:9px}#status{color:#9ec5ff;font-size:13px}.tabs{display:flex;gap:7px;flex-wrap:wrap}.hidden{display:none}li{padding:7px;border-bottom:1px solid #2a3d56;word-break:break-word}
</style></head><body><header><h2>LocalDrop — Android Host</h2><div id="status">أدخل رمز المضيف للاتصال</div></header><main class="wrap">
<section class="card"><label>رمز الدخول</label><input id="pin" inputmode="numeric" placeholder="الرمز الظاهر على الهاتف"><div class="row" style="margin-top:8px"><button onclick="connect()">اتصال</button><button onclick="refreshAll()">تحديث</button></div></section>
<div class="tabs"><button onclick="tab('remote')">التحكم بالشاشة</button><button onclick="tab('chat')">المحادثة</button><button onclick="tab('files')">الملفات</button><button onclick="tab('devices')">الأجهزة</button></div>
<section id="remote" class="card"><div class="row"><button onclick="toggleStream()">تشغيل/إيقاف التحديث</button><button onclick="setMode('tap')">وضع اللمس</button><button onclick="setMode('scroll')">وضع التمرير</button></div><p>المس الشاشة أو اسحب عليها. التمرير مستقل عن موضع النقر.</p><img id="screen" alt="شاشة الهاتف"><div class="row"><button onclick="zoom(1.25)">تكبير</button><button onclick="zoom(.8)">تصغير</button><button onclick="zoom(1)">إعادة</button></div></section>
<section id="chat" class="card hidden"><div id="messages"></div><input id="message" placeholder="اكتب رسالة"><div class="row"><button onclick="sendChat()">إرسال</button><button onclick="loadChat()">تحديث</button></div></section>
<section id="files" class="card hidden"><input id="upload" type="file"><div class="row"><button onclick="uploadFile()">رفع الملف</button><button onclick="loadFiles()">تحديث الملفات</button></div><ul id="filelist"></ul></section>
<section id="devices" class="card hidden"><button onclick="loadDevices()">تحديث الأجهزة</button><ul id="devlist"></ul></section>
</main><script>
let pin='',stream=false,timer=0,mode='tap',scale=1,info={};
function headers(){return {'X-Host-Pin':pin,'Content-Type':'application/json'}}
async function api(path,opt={}){opt.headers={...(opt.headers||{}),'X-Host-Pin':pin};let r=await fetch(path,opt);if(r.status===401)throw Error('رمز الدخول غير صحيح');return r}
async function connect(){pin=document.getElementById('pin').value.trim();try{info=await (await fetch('/api/info')).json();await api('/api/devices');document.getElementById('status').textContent='متصل — '+info.name;localStorage.ldpin=pin;refreshAll();}catch(e){document.getElementById('status').textContent=e.message}}
function tab(id){['remote','chat','files','devices'].forEach(x=>document.getElementById(x).classList.toggle('hidden',x!==id));if(id==='chat')loadChat();if(id==='files')loadFiles();if(id==='devices')loadDevices()}
function setMode(m){mode=m}
function toggleStream(){stream=!stream;if(stream)frame();else clearTimeout(timer)}
async function frame(){if(!stream)return;try{let r=await api('/api/screen');if(r.ok){let u=URL.createObjectURL(await r.blob()),img=document.getElementById('screen'),old=img.dataset.url;img.src=u;img.dataset.url=u;if(old)URL.revokeObjectURL(old)}}catch(e){document.getElementById('status').textContent=e.message;stream=false;return}timer=setTimeout(frame,160)}
function zoom(v){scale=v===1?1:Math.max(.6,Math.min(3,scale*v));document.getElementById('screen').style.transform='scale('+scale+')'}
async function sendInput(o){o.screenWidth=info.screenWidth||1080;o.screenHeight=info.screenHeight||1920;await api('/api/input',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(o)})}
let down=null;const img=document.getElementById('screen');
img.addEventListener('pointerdown',e=>{if(!stream)return;down={x:e.offsetX/img.clientWidth,y:e.offsetY/img.clientHeight};img.setPointerCapture(e.pointerId)});
img.addEventListener('pointerup',async e=>{if(!down)return;let x=e.offsetX/img.clientWidth,y=e.offsetY/img.clientHeight;if(mode==='tap'&&Math.hypot(x-down.x,y-down.y)<.025)await sendInput({action:'tap',x,y});else await sendInput({action:'swipe',x1:down.x,y1:down.y,x2:x,y2:y,duration:360});down=null});
async function sendChat(){let text=document.getElementById('message').value.trim();if(!text)return;await api('/api/chat',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({text,from:'متصفح'})});document.getElementById('message').value='';loadChat()}
async function loadChat(){let d=await (await api('/api/chat')).json();document.getElementById('messages').innerHTML=d.messages.map(m=>'<p><b>'+esc(m.from)+'</b> <small>'+m.time+'</small><br>'+esc(m.text)+'</p>').join('')}
function esc(s){return String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}
async function loadFiles(){let d=await (await api('/api/files')).json();document.getElementById('filelist').innerHTML=d.files.map(f=>'<li><a style="color:#9ec5ff" href="/download/'+encodeURIComponent(f.name)+'?pin='+pin+'">'+esc(f.name)+'</a> — '+f.size+' bytes</li>').join('')}
async function uploadFile(){let f=document.getElementById('upload').files[0];if(!f)return;let fd=new FormData();fd.append('file',f);let r=await fetch('/api/files?name='+encodeURIComponent(f.name),{method:'POST',headers:{'X-Host-Pin':pin},body:fd});if(!r.ok)alert('فشل رفع الملف');loadFiles()}
async function loadDevices(){let d=await (await api('/api/devices')).json();document.getElementById('devlist').innerHTML=d.devices.map(x=>'<li>'+esc(x.name)+' — '+esc(x.type)+' — متصل</li>').join('')}
function refreshAll(){loadDevices();loadChat();loadFiles()}
document.getElementById('pin').value=localStorage.ldpin||'';
</script></body></html>"""
    }
}
