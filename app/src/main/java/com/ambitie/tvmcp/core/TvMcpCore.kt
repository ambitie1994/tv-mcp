package com.ambitie.tvmcp.core

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.annotation.MainThread
import com.ambitie.tvmcp.data.AiListStore
import com.ambitie.tvmcp.data.IptvChannelStore
import com.ambitie.tvmcp.data.IptvSource
import com.ambitie.tvmcp.data.IptvStore
import com.ambitie.tvmcp.data.SettingsStore
import com.ambitie.tvmcp.data.SmbSource
import com.ambitie.tvmcp.data.SourceStore
import com.ambitie.tvmcp.mcp.McpHttpServer
import com.ambitie.tvmcp.mcp.longValue
import com.ambitie.tvmcp.mcp.stringValue
import com.ambitie.tvmcp.mcp.McpProtocol
import com.ambitie.tvmcp.playback.PlaybackHistory
import com.ambitie.tvmcp.playback.PlaybackStatus
import com.ambitie.tvmcp.playback.PlayerManager
import com.ambitie.tvmcp.playback.SubtitleFile
import com.ambitie.tvmcp.playback.SubtitleFiles
import com.ambitie.tvmcp.smb.SmbPool
import com.ambitie.tvmcp.tools.TvTools
import com.ambitie.tvmcp.ui.MainActivity
import com.ambitie.tvmcp.util.LocalIpv4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ServerState(
    val running: Boolean = false,
    val port: Int = 0,
    val error: String? = null
)

class TvMcpCore(private val context: Context) {
    val sources = SourceStore(context)
    val iptv = IptvStore(context)
    val iptvChannels = IptvChannelStore(context)
    val aiList = AiListStore(context)
    val history = PlaybackHistory(context)
    val smbPool = SmbPool()
    val settings = SettingsStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var playerManager: PlayerManager? = null
    private var statusJob: Job? = null
    private var cueJob: Job? = null
    private var httpServer: McpHttpServer? = null

    private val _serverState = MutableStateFlow(ServerState())
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _playbackState = MutableStateFlow<PlaybackStatus?>(null)
    val playbackState: StateFlow<PlaybackStatus?> = _playbackState.asStateFlow()

    private val _cueText = MutableStateFlow<String?>(null)
    val cueText: StateFlow<String?> = _cueText.asStateFlow()

    private val _sourceVersion = MutableStateFlow(0)
    val sourceVersion: StateFlow<Int> = _sourceVersion.asStateFlow()

    fun bumpSourceVersion() {
        _sourceVersion.value += 1
    }

    fun markIptvInvalid(id: String, error: String) {
        val source = iptv.get(id) ?: return
        iptv.upsert(source.copy(invalid = true, lastError = error.take(300), failedAt = System.currentTimeMillis()))
        bumpSourceVersion()
    }

    fun markIptvValid(id: String) {
        val source = iptv.get(id) ?: return
        if (!source.invalid && source.lastError == null) return
        iptv.upsert(source.copy(invalid = false, lastError = null, failedAt = 0L))
        bumpSourceVersion()
    }

    fun markSmbInvalid(id: String, error: String) {
        val source = sources.get(id) ?: return
        sources.upsert(source.copy(invalid = true, lastError = error.take(300), failedAt = System.currentTimeMillis()))
        bumpSourceVersion()
    }

    fun markSmbValid(id: String) {
        val source = sources.get(id) ?: return
        if (!source.invalid && source.lastError == null) return
        sources.upsert(source.copy(invalid = false, lastError = null, failedAt = 0L))
        bumpSourceVersion()
    }

    fun markChannelInvalid(sourceId: String, url: String, error: String) {
        if (iptvChannels.markInvalid(sourceId, url, error.take(300))) bumpSourceVersion()
    }

    fun markAiItemInvalid(key: String, error: String) {
        if (aiList.markInvalidByKey(key, error.take(300))) bumpSourceVersion()
    }

    fun token(): String = settings.token()

    @MainThread
    fun player(): PlayerManager {
        val existing = playerManager
        if (existing != null) return existing
        val created = PlayerManager(context, sources, history, smbPool)
        playerManager = created
        statusJob = scope.launch {
            created.status.collect { _playbackState.value = it }
        }
        cueJob = scope.launch {
            created.cueText.collect { _cueText.value = it }
        }
        return created
    }

    fun playerOrNull(): PlayerManager? = playerManager

    @Synchronized
    fun startServer(): Boolean {
        val current = httpServer
        if (current != null && current.running) {
            _serverState.value = ServerState(true, current.port, null)
            return true
        }
        current?.stop()
        httpServer = null

        val tools = TvTools(
            sources = sources,
            iptv = iptv,
            iptvChannels = iptvChannels,
            history = history,
            pool = smbPool,
            aiList = aiList,
            settings = settings,
            playerProvider = ::player,
            onPlaybackStarted = ::bringPlayerToFront,
            onUiChanged = ::bumpSourceVersion
        ).all()
        val protocol = McpProtocol(
            serverName = SERVER_NAME,
            serverVersion = SERVER_VERSION,
            instructions = INSTRUCTIONS,
            tools = tools,
            log = ::log
        )
        val server = McpHttpServer(
            protocol = protocol,
            token = settings.token(),
            setupHandler = { action, body -> handleSetup(action, body) },
            log = ::log
        )
        val started = server.start()
        return if (started) {
            httpServer = server
            _serverState.value = ServerState(true, server.port, null)
            log("MCP server started on port ${server.port}")
            true
        } else {
            server.stop()
            _serverState.value = ServerState(false, 0, server.lastError ?: "端口绑定失败")
            log("MCP server failed to start: ${server.lastError}")
            false
        }
    }

    @Synchronized
    fun stopServer() {
        httpServer?.stop()
        httpServer = null
        _serverState.value = ServerState(false, 0, null)
    }

    @Synchronized
    fun rotateToken(): Boolean {
        settings.rotateToken()
        if (httpServer != null) {
            stopServer()
            return startServer()
        }
        return true
    }

    fun urls(): List<String> = endpointUrls("mcp")

    fun setupUrls(): List<String> = endpointUrls("setup")

    private fun endpointUrls(path: String): List<String> {
        val port = httpServer?.port?.takeIf { it > 0 } ?: McpHttpServer.DEFAULT_PORT
        val token = settings.token()
        val addresses = LocalIpv4.addresses()
        return if (addresses.isEmpty()) {
            listOf("http://<电视IP>:$port/$path?token=$token")
        } else {
            addresses.map { "http://$it:$port/$path?token=$token" }
        }
    }

    suspend fun handleSetup(action: String, body: JsonObject?): JsonObject {
        return try {
            when (action) {
                "state" -> setupState()
                "iptv_save" -> saveIptvFromSetup(body)
                "iptv_delete" -> {
                    val id = body?.stringValue("id") ?: return setupError("缺少 id")
                    iptv.delete(id)
                    iptvChannels.removeSource(id)
                    bumpSourceVersion()
                    setupOk()
                }
                "smb_save" -> saveSmbFromSetup(body)
                "smb_delete" -> {
                    val id = body?.stringValue("id") ?: return setupError("缺少 id")
                    sources.delete(id)
                    smbPool.invalidate(id)
                    bumpSourceVersion()
                    setupOk()
                }
                "cleanup" -> {
                    val smb = sources.all().filter { it.invalid }
                    smb.forEach { sources.delete(it.id); smbPool.invalidate(it.id) }
                    val tv = iptv.all().filter { it.invalid }
                    tv.forEach { iptv.delete(it.id); iptvChannels.removeSource(it.id) }
                    val channels = iptvChannels.removeInvalidAll()
                    val ai = aiList.removeInvalid()
                    bumpSourceVersion()
                    buildJsonObject {
                        put("ok", true)
                        put("sources", smb.size + tv.size)
                        put("channels", channels)
                        put("ai_items", ai)
                    }
                }
                else -> setupError("未知操作")
            }
        } catch (e: Exception) {
            setupError(e.message ?: "操作失败")
        }
    }

    private fun setupState(): JsonObject = buildJsonObject {
        put("ok", true)
        put("iptv", buildJsonArray { iptv.all().forEach { add(it.publicJson()) } })
        put("smb", buildJsonArray {
            sources.all().forEach { source ->
                add(
                    buildJsonObject {
                        put("id", source.id)
                        put("name", source.name)
                        put("host", source.host)
                        put("port", source.port)
                        put("share", source.share)
                        put("domain", source.domain)
                        put("username", source.username)
                        put("invalid", source.invalid)
                        source.lastError?.let { put("lastError", it) }
                    }
                )
            }
        })
    }

    private fun saveIptvFromSetup(body: JsonObject?): JsonObject {
        val name = body?.stringValue("name")?.trim().orEmpty()
        val url = body?.stringValue("url")?.trim().orEmpty()
        val id = body?.stringValue("id")
        if (name.isEmpty() || url.isEmpty()) return setupError("名称和地址不能为空")
        if (!url.startsWith("http://") && !url.startsWith("https://")) return setupError("地址必须是 http/https")
        val existing = id?.let { iptv.get(it) }
        iptv.upsert(
            IptvSource(
                id = existing?.id ?: iptv.newId(),
                name = name,
                url = url,
                invalid = false,
                lastError = null,
                failedAt = 0L
            )
        )
        bumpSourceVersion()
        return setupOk()
    }

    private fun saveSmbFromSetup(body: JsonObject?): JsonObject {
        val name = body?.stringValue("name")?.trim().orEmpty()
        val host = body?.stringValue("host")?.trim().orEmpty()
        val share = body?.stringValue("share")?.trim().orEmpty()
        val port = (body?.longValue("port") ?: 445L).toInt().coerceIn(1, 65535)
        val id = body?.stringValue("id")
        val existing = id?.let { sources.get(it) }
        if (name.isEmpty() || host.isEmpty() || share.isEmpty()) return setupError("名称、主机、共享名不能为空")
        val password = body?.stringValue("password").orEmpty()
        val actualPassword = if (password.isBlank() && existing != null) existing.password else password
        val source = SmbSource(
            id = existing?.id ?: sources.newId(),
            name = name,
            host = host.removePrefix("smb://").trimEnd('/'),
            port = port,
            share = share.trim('/'),
            domain = body?.stringValue("domain").orEmpty(),
            username = body?.stringValue("username").orEmpty(),
            password = actualPassword
        )
        sources.upsert(source)
        smbPool.invalidate(source.id)
        bumpSourceVersion()
        return setupOk()
    }

    private fun setupOk(): JsonObject = buildJsonObject { put("ok", true) }

    private fun setupError(message: String): JsonObject = buildJsonObject {
        put("ok", false)
        put("error", message)
    }

    fun bringPlayerToFront() {
        if (MainActivity.isVisible) return
        if (!Settings.canDrawOverlays(context)) {
            log("background video needs overlay permission; use the notification or grant it in the app")
            return
        }
        runCatching {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
                putExtra(MainActivity.EXTRA_SHOW_PLAYER, true)
            }
            context.startActivity(intent)
        }.onFailure {
            log("cannot bring player activity to front: ${it.message}")
        }
    }

    suspend fun testSource(source: SmbSource): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { smbPool.test(source) }
    }

    suspend fun siblingSubtitles(): List<SubtitleFile> {
        val info = withContext(Dispatchers.Main.immediate) {
            playerOrNull()?.currentMediaInfo()
        } ?: return emptyList()
        val sourceId = info.sourceId ?: return emptyList()
        val path = info.path ?: return emptyList()
        val source = sources.get(sourceId) ?: return emptyList()
        val attached = withContext(Dispatchers.Main.immediate) {
            playerOrNull()?.attachedSubtitleUris().orEmpty()
        }
        return withContext(Dispatchers.IO) {
            SubtitleFiles.discover(smbPool, source, path)
        }.filterNot { it.uri() in attached }
    }

    fun attachSubtitle(file: SubtitleFile) {
        player().attachSubtitle(
            android.net.Uri.parse(file.uri()),
            file.mimeType,
            null,
            file.name
        )
    }

    @Synchronized
    fun shutdown() {
        stopServer()
        statusJob?.cancel()
        statusJob = null
        cueJob?.cancel()
        cueJob = null
        _playbackState.value = null
        _cueText.value = null
        playerManager?.release()
        playerManager = null
        smbPool.closeAll()
    }

    fun log(message: String) {
        Log.i(TAG, message)
    }

    companion object {
        private const val TAG = "TvMcp"
        private const val SERVER_NAME = "android-tv-mcp"
        private const val SERVER_VERSION = "1.0.0"

        private val INSTRUCTIONS = """
            这是运行在 Android TV 上的 MCP 媒体播放服务。
            典型调用链：
            1. list_sources 查看已保存的 SMB 片源；没有片源时用 save_smb_source 添加，test_smb_source 验证，delete_smb_source 删除。
            2. browse 浏览共享目录，拿到视频文件的 path 字段（相对共享根目录的完整路径）。
            3. play 播放：source_id + path（path 必须原样用 browse 返回的完整相对路径，不能只传文件名；path 也可以是目录，整目录按文件名排序连播），或 http/https URL；可带 start_ms、intro_end_ms、outro_start_ms。
            4. status 查看播放进度和错误；control 控制暂停/继续/跳转/音量/next 下一集/previous 上一集。SMB 播放默认把同目录的可播放媒体文件按文件名排序加入队列：播完自动连播，control next/previous 就是上一集/下一集；queue_folder=false 可关闭。
            5. play_queue 可一次提交多集并自动连播；get_history 可查询上次看到哪，播放记录保存在电视本地，play 默认从上次进度续播。
            6. list_subtitles 查看内嵌字幕轨和同目录外挂字幕；set_subtitle 选择/关闭/加载字幕；set_subtitle_style 调整字号、颜色、背景和位置。
            7. show_list 可把列表放到电视首页，用户用遥控器就能选。
            8. IPTV：list_iptv_sources 看 M3U 源；save_iptv_source 添加/修改；test_iptv_source 拉取频道；list_channels 按名称/分组查；play_channel 按名称或序号播放；可用 show_list 把选中的频道放到首页。
            9. 继续播放/返回续播用 resume_history（可带 title 关键字），它会按播放记录里的 source_id/path 原样播放；不要自己拼 SMB 路径。
            路径不允许包含 ..；播放失败的详细信息在 status 返回的 error 字段中。
        """.trimIndent()
    }
}
