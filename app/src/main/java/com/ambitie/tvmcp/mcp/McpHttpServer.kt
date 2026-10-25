package com.ambitie.tvmcp.mcp

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest

class McpHttpServer(
    private val protocol: McpProtocol,
    private val token: String,
    private val preferredPort: Int = DEFAULT_PORT,
    private val host: String = "0.0.0.0",
    private val setupHandler: (suspend (String, JsonObject?) -> JsonObject)? = null,
    private val log: (String) -> Unit = {}
) {
    private var engine: EmbeddedServer<*, *>? = null

    @Volatile
    var port: Int = preferredPort
        private set

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    fun start(): Boolean {
        if (running) return true
        lastError = null
        for (candidate in preferredPort until preferredPort + 5) {
            try {
                engine = embeddedServer(CIO, port = candidate, host = host) {
                    module()
                }.start(wait = false)
                port = candidate
                running = true
                log("MCP server listening on $host:$port")
                return true
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                log("cannot bind $host:$candidate: $lastError")
                runCatching { engine?.stop(0, 0) }
                engine = null
            }
        }
        running = false
        return false
    }

    fun stop() {
        runCatching { engine?.stop(500, 1500) }
        engine = null
        running = false
    }

    private fun Application.module() {
        install(CORS) {
            anyHost()
            allowHeader(HttpHeaders.Authorization)
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Accept)
            allowHeader("Mcp-Session-Id")
            allowHeader("Mcp-Protocol-Version")
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Delete)
            allowMethod(HttpMethod.Options)
            allowNonSimpleContentTypes = true
        }

        routing {
            get("/") {
                call.respondText(
                    "TV MCP server is running. Endpoint: POST /mcp (Streamable HTTP).",
                    ContentType.Text.Plain
                )
            }
            get("/health") {
                call.respondText(
                    buildJsonObject {
                        put("ok", true)
                        put("endpoint", "/mcp")
                    }.toString(),
                    ContentType.Application.Json
                )
            }
            get("/setup") {
                if (!authorized(call)) return@get
                call.respondText(SETUP_HTML, ContentType.Text.Html)
            }
            get("/api/setup/state") {
                if (!authorized(call)) return@get
                val handler = setupHandler
                if (handler == null) {
                    call.respondText("""{"ok":false,"error":"setup unavailable"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
                    return@get
                }
                val response = handler("state", null)
                call.respondText(response.toString(), ContentType.Application.Json)
            }
            post("/api/setup") {
                if (!authorized(call)) return@post
                val handler = setupHandler
                if (handler == null) {
                    call.respondText("""{"ok":false,"error":"setup unavailable"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
                    return@post
                }
                val body = call.receiveText()
                val payload = try {
                    Json.parseToJsonElement(body).jsonObject
                } catch (e: Exception) {
                    call.respondText("""{"ok":false,"error":"invalid json"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
                val action = payload.stringValue("action")
                if (action.isNullOrBlank()) {
                    call.respondText("""{"ok":false,"error":"missing action"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
                call.respondText(handler(action, payload).toString(), ContentType.Application.Json)
            }
            route("/mcp") {
                post { handlePost(call) }
                get {
                    call.respondText(
                        """{"error":"SSE streaming is not offered; use POST /mcp"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.MethodNotAllowed
                    )
                }
                delete {
                    call.respondText(
                        """{"error":"no session to delete"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.MethodNotAllowed
                    )
                }
            }
        }
    }

    private suspend fun handlePost(call: ApplicationCall) {
        if (!authorized(call)) return

        val body = call.receiveText()
        if (body.length > MAX_BODY_CHARS) {
            call.respondText(
                """{"jsonrpc":"2.0","id":null,"error":{"code":-32600,"message":"Request too large"}}""",
                ContentType.Application.Json,
                HttpStatusCode.PayloadTooLarge
            )
            return
        }

        val element = try {
            Json.parseToJsonElement(body)
        } catch (e: Exception) {
            call.respondText(
                """{"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"Parse error"}}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
            return
        }

        val message = element as? JsonObject
        if (message == null) {
            call.respondText(
                """{"jsonrpc":"2.0","id":null,"error":{"code":-32600,"message":"Expected a single JSON-RPC object"}}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
            return
        }

        val response = protocol.handle(message)
        if (response == null) {
            call.respond(HttpStatusCode.Accepted)
            return
        }

        val text = response.toString()
        val accept = call.request.headers[HttpHeaders.Accept].orEmpty()
        if (accept.contains("text/event-stream") && !accept.contains("application/json")) {
            call.respondText(
                "event: message\ndata: $text\n\n",
                ContentType.Text.EventStream,
                HttpStatusCode.OK
            )
        } else {
            call.respondText(
                text,
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        }
    }

    private suspend fun authorized(call: ApplicationCall): Boolean {
        val header = call.request.headers[HttpHeaders.Authorization]
        val bearer = header?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(' ')?.trim()
        val provided = bearer ?: call.request.queryParameters["token"]
        val ok = provided != null &&
            MessageDigest.isEqual(
                provided.toByteArray(Charsets.UTF_8),
                token.toByteArray(Charsets.UTF_8)
            )
        if (!ok) {
            call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
            call.respondText(
                """{"error":"unauthorized"}""",
                ContentType.Application.Json,
                HttpStatusCode.Unauthorized
            )
        }
        return ok
    }

    companion object {
        const val DEFAULT_PORT = 8765
        private const val MAX_BODY_CHARS = 2_000_000

        private val SETUP_HTML = """
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>TV MCP 配置</title>
<style>
body{background:#0f1115;color:#e6e8ee;font-family:system-ui,-apple-system,sans-serif;margin:0;padding:20px}
h1{font-size:22px;margin:0 0 4px}
h2{font-size:17px;margin:26px 0 10px;border-bottom:1px solid #3a4150;padding-bottom:8px}
p{color:#9aa3b2;font-size:13px;line-height:1.5}
.card{background:#1a1d24;border:1px solid #3a4150;border-radius:12px;padding:14px;margin:10px 0}
label{display:block;color:#9aa3b2;font-size:12px;margin:10px 0 4px}
input{width:100%;box-sizing:border-box;background:#232833;color:#fff;border:1px solid #3a4150;border-radius:8px;padding:11px;font-size:15px}
button{background:#2d5fa8;color:#fff;border:1px solid #4f8cff;border-radius:8px;padding:11px 14px;font-size:14px;margin:6px 6px 0 0}
button.danger{background:#3a1d1d;border-color:#e5534b;color:#ffb4ae}
.row{display:flex;align-items:center;justify-content:space-between;gap:8px;padding:8px 0;border-bottom:1px solid #2a3040}
.row:last-child{border-bottom:0}
.title{font-size:15px}
.sub{font-size:12px;color:#9aa3b2;word-break:break-all}
.ok{color:#8db4ff}.bad{color:#e5534b}
#status{min-height:20px;font-size:13px;color:#8db4ff;margin:8px 0}
</style>
</head>
<body>
<h1>TV MCP 配置</h1>
<p>在手机上添加电视源和 NAS 片源。保存后电视会自动刷新，不用在电视上输入。</p>
<div id="status"></div>
<h2>电视源（M3U / M3U8）</h2>
<div id="iptvList"></div>
<div class="card">
  <label>名称</label><input id="iptvName" placeholder="例如 我的直播源">
  <label>M3U / M3U8 地址</label><input id="iptvUrl" placeholder="http://或者https://">
  <button onclick="saveIptv()">保存电视源</button>
  <button class="danger" onclick="resetIptv()">清空输入</button>
</div>
<h2>NAS 片源（SMB）</h2>
<div id="smbList"></div>
<div class="card">
  <label>名称</label><input id="smbName" placeholder="例如 客厅 NAS">
  <label>主机 / IP</label><input id="smbHost" placeholder="192.168.1.10">
  <label>端口</label><input id="smbPort" value="445" inputmode="numeric">
  <label>共享名</label><input id="smbShare" placeholder="Media">
  <label>域（可空）</label><input id="smbDomain">
  <label>用户名（留空按 Guest 访客）</label><input id="smbUser">
  <label>密码（编辑时留空表示不改）</label><input id="smbPass" type="password">
  <button onclick="saveSmb()">保存 NAS 片源</button>
  <button class="danger" onclick="resetSmb()">清空输入</button>
</div>
<script>
var params = new URLSearchParams(location.search);
var token = params.get('token') || '';
var iptvEditing = '';
var smbEditing = '';
function q(id){return document.getElementById(id)}
function setStatus(t,bad){var s=q('status');s.textContent=t;s.className=bad?'bad':'ok'}
function api(body){return fetch('/api/setup?token='+encodeURIComponent(token),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}).then(function(r){return r.json()})}
function load(){
  fetch('/api/setup/state?token='+encodeURIComponent(token)).then(function(r){return r.json()}).then(function(s){
    if(!s.ok){setStatus(s.error||'读取失败',true);return}
    renderIptv(s.iptv||[]);renderSmb(s.smb||[]);
  }).catch(function(e){setStatus('读取失败：'+e,true)})
}
function renderIptv(list){
  var box=q('iptvList');box.innerHTML='';
  if(!list.length){box.innerHTML='<p>还没有电视源</p>';return}
  list.forEach(function(x){
    var d=document.createElement('div');d.className='card';
    var title=document.createElement('div');title.className='title';title.textContent=x.name;
    var sub=document.createElement('div');sub.className='sub';sub.textContent=x.url+(x.invalid?' · 失效：'+(x.lastError||''):'');
    var e=document.createElement('button');e.textContent='编辑';e.onclick=function(){iptvEditing=x.id;q('iptvName').value=x.name;q('iptvUrl').value=x.url;window.scrollTo(0,document.body.scrollHeight)};
    var dlt=document.createElement('button');dlt.className='danger';dlt.textContent='删除';dlt.onclick=function(){if(confirm('删除 '+x.name+' ?')){api({action:'iptv_delete',id:x.id}).then(function(r){setStatus(r.ok?'已删除':(r.error||'删除失败'),!r.ok);load()})}};
    d.appendChild(title);d.appendChild(sub);d.appendChild(e);d.appendChild(dlt);box.appendChild(d);
  })
}
function renderSmb(list){
  var box=q('smbList');box.innerHTML='';
  if(!list.length){box.innerHTML='<p>还没有 NAS 片源</p>';return}
  list.forEach(function(x){
    var d=document.createElement('div');d.className='card';
    var title=document.createElement('div');title.className='title';title.textContent=x.name;
    var sub=document.createElement('div');sub.className='sub';sub.textContent=x.host+':'+x.port+'/'+x.share+(x.invalid?' · 失效：'+(x.lastError||''):'');
    var e=document.createElement('button');e.textContent='编辑';e.onclick=function(){smbEditing=x.id;q('smbName').value=x.name;q('smbHost').value=x.host;q('smbPort').value=x.port;q('smbShare').value=x.share;q('smbDomain').value=x.domain||'';q('smbUser').value=x.username||'';q('smbPass').value='';window.scrollTo(0,document.body.scrollHeight)};
    var dlt=document.createElement('button');dlt.className='danger';dlt.textContent='删除';dlt.onclick=function(){if(confirm('删除 '+x.name+' ?')){api({action:'smb_delete',id:x.id}).then(function(r){setStatus(r.ok?'已删除':(r.error||'删除失败'),!r.ok);load()})}};
    d.appendChild(title);d.appendChild(sub);d.appendChild(e);d.appendChild(dlt);box.appendChild(d);
  })
}
function saveIptv(){
  api({action:'iptv_save',id:iptvEditing||undefined,name:q('iptvName').value,url:q('iptvUrl').value}).then(function(r){setStatus(r.ok?'电视源已保存':(r.error||'保存失败'),!r.ok);if(r.ok){resetIptv();load()}})
}
function saveSmb(){
  api({action:'smb_save',id:smbEditing||undefined,name:q('smbName').value,host:q('smbHost').value,port:q('smbPort').value,share:q('smbShare').value,domain:q('smbDomain').value,username:q('smbUser').value,password:q('smbPass').value}).then(function(r){setStatus(r.ok?'NAS 片源已保存':(r.error||'保存失败'),!r.ok);if(r.ok){resetSmb();load()}})
}
function resetIptv(){iptvEditing='';q('iptvName').value='';q('iptvUrl').value=''}
function resetSmb(){smbEditing='';q('smbName').value='';q('smbHost').value='';q('smbPort').value='445';q('smbShare').value='';q('smbDomain').value='';q('smbUser').value='';q('smbPass').value=''}
load();
</script>
</body>
</html>
        """.trimIndent()
    }
}
