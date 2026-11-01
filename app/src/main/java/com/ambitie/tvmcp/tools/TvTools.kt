package com.ambitie.tvmcp.tools

import android.graphics.Color
import android.net.Uri
import com.ambitie.tvmcp.data.AiListItem
import com.ambitie.tvmcp.data.AiListStore
import com.ambitie.tvmcp.data.CachedChannel
import com.ambitie.tvmcp.data.IptvChannelStore
import com.ambitie.tvmcp.data.IptvSource
import com.ambitie.tvmcp.data.IptvStore
import com.ambitie.tvmcp.data.SettingsStore
import com.ambitie.tvmcp.data.SmbSource
import com.ambitie.tvmcp.data.SourceStore
import com.ambitie.tvmcp.data.SubtitleStyle
import com.ambitie.tvmcp.iptv.IptvChannel
import com.ambitie.tvmcp.iptv.M3uLoader
import com.ambitie.tvmcp.iptv.M3uParser
import com.ambitie.tvmcp.mcp.McpTool
import com.ambitie.tvmcp.mcp.ToolException
import com.ambitie.tvmcp.mcp.ToolResult
import com.ambitie.tvmcp.mcp.arrayProperty
import com.ambitie.tvmcp.mcp.arrayValue
import com.ambitie.tvmcp.mcp.booleanProperty
import com.ambitie.tvmcp.mcp.booleanValue
import com.ambitie.tvmcp.mcp.doubleValue
import com.ambitie.tvmcp.mcp.intValue
import com.ambitie.tvmcp.mcp.integerProperty
import com.ambitie.tvmcp.mcp.longValue
import com.ambitie.tvmcp.mcp.numberProperty
import com.ambitie.tvmcp.mcp.objectSchema
import com.ambitie.tvmcp.mcp.objectValue
import com.ambitie.tvmcp.mcp.stringProperty
import com.ambitie.tvmcp.mcp.stringValue
import com.ambitie.tvmcp.playback.MediaFiles
import com.ambitie.tvmcp.playback.MediaSpec
import com.ambitie.tvmcp.playback.PlaybackHistory
import com.ambitie.tvmcp.playback.PlaybackStatus
import com.ambitie.tvmcp.playback.PlayerManager
import com.ambitie.tvmcp.playback.SubtitleFiles
import com.ambitie.tvmcp.smb.SmbPath
import com.ambitie.tvmcp.smb.SmbPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TvTools(
    private val sources: SourceStore,
    private val iptv: IptvStore,
    private val iptvChannels: IptvChannelStore,
    private val history: PlaybackHistory,
    private val pool: SmbPool,
    private val aiList: AiListStore,
    private val settings: SettingsStore,
    private val playerProvider: () -> PlayerManager,
    private val onPlaybackStarted: () -> Unit,
    private val onUiChanged: () -> Unit
) {
    fun all(): List<McpTool> = listOf(
        listSourcesTool(),
        saveSmbSourceTool(),
        deleteSmbSourceTool(),
        testSmbSourceTool(),
        browseTool(),
        listIptvSourcesTool(),
        saveIptvSourceTool(),
        deleteIptvSourceTool(),
        testIptvSourceTool(),
        listChannelsTool(),
        playChannelTool(),
        showListTool(),
        playTool(),
        playQueueTool(),
        controlTool(),
        statusTool(),
        listSubtitlesTool(),
        setSubtitleTool(),
        setSubtitleStyleTool(),
        historyTool(),
        resumeHistoryTool()
    )

    private fun listSourcesTool() = McpTool(
        name = "list_sources",
        description = "列出电视端已配置的 SMB 片源（只返回 id、名称、主机和共享名，不返回凭据）。",
        inputSchema = objectSchema(emptyMap())
    ) {
        val list = sources.all()
        val text = if (list.isEmpty()) {
            "电视端还没有配置 SMB 片源。可调用 save_smb_source 添加。"
        } else {
            list.joinToString("\n") {
                val state = if (it.invalid) " | 失效：${it.lastError.orEmpty()}" else ""
                "${it.id} | ${it.name} | smb://${it.host}:${it.port}/${it.share}$state"
            }
        }
        ToolResult(
            text = text,
            structured = buildJsonObject {
                put("sources", buildJsonArray { list.forEach { add(it.publicJson()) } })
            }
        )
    }

    private fun saveSmbSourceTool() = McpTool(
        name = "save_smb_source",
        description = "新增或修改 SMB 片源，配置保存在电视本地。省略 source_id 为新增；修改时只传要改的字段，password 留空沿用原密码。不会自动测试连接。",
        inputSchema = objectSchema(
            mapOf(
                "source_id" to stringProperty("要修改的片源 id；省略则新增"),
                "name" to stringProperty("显示名称；新增时默认使用共享名"),
                "host" to stringProperty("SMB 主机或 IP，例如 192.168.1.10；修改时可省略"),
                "port" to integerProperty("SMB 端口，默认 445", minimum = 1, maximum = 65535, default = 445),
                "share" to stringProperty("共享名，例如 Movies；修改时可省略"),
                "domain" to stringProperty("域，可省略"),
                "username" to stringProperty("用户名；留空按 Guest 访客登录"),
                "password" to stringProperty("密码；修改时留空沿用原密码")
            )
        )
    ) { args ->
        val id = args.stringValue("source_id")
        val existing = id?.let { sources.get(it) ?: throw ToolException("未找到片源：$it") }
        val host = (args.stringValue("host")?.let { normalizeHost(it) } ?: existing?.host)
            ?.takeIf { it.isNotBlank() }
            ?: throw ToolException("需要 host")
        val share = (args.stringValue("share")?.trim('/') ?: existing?.share)
            ?.takeIf { it.isNotBlank() }
            ?: throw ToolException("需要 share")
        val name = args.stringValue("name")?.trim()?.takeIf { it.isNotBlank() }
            ?: existing?.name
            ?: share
        val password = args.stringValue("password")?.takeIf { it.isNotBlank() }
            ?: existing?.password.orEmpty()
        val source = SmbSource(
            id = existing?.id ?: sources.newId(),
            name = name,
            host = host,
            port = (args.intValue("port") ?: existing?.port ?: 445).coerceIn(1, 65535),
            share = share,
            domain = args.rawString("domain")?.trim() ?: existing?.domain.orEmpty(),
            username = args.rawString("username")?.trim() ?: existing?.username.orEmpty(),
            password = password
        )
        sources.upsert(source)
        pool.invalidate(source.id)
        onUiChanged()
        ToolResult(
            text = if (existing == null) {
                "已添加 SMB 片源：${source.name}（${source.id}）\n可调用 test_smb_source 验证连接。"
            } else {
                "已更新 SMB 片源：${source.name}（${source.id}）\n可调用 test_smb_source 验证连接。"
            },
            structured = buildJsonObject {
                put("created", existing == null)
                put("source", source.publicJson())
            }
        )
    }

    private fun deleteSmbSourceTool() = McpTool(
        name = "delete_smb_source",
        description = "删除电视本地保存的 SMB 片源并断开连接，不会删除 NAS 上的文件。",
        inputSchema = objectSchema(
            mapOf("source_id" to stringProperty("要删除的片源 id")),
            required = listOf("source_id")
        )
    ) { args ->
        val id = args.stringValue("source_id") ?: throw ToolException("需要 source_id")
        val source = sources.get(id) ?: throw ToolException("未找到片源：$id")
        sources.delete(id)
        pool.invalidate(id)
        onUiChanged()
        ToolResult(
            text = "已删除 SMB 片源：${source.name}（$id）",
            structured = buildJsonObject {
                put("deleted", true)
                put("source_id", id)
            }
        )
    }

    private fun testSmbSourceTool() = McpTool(
        name = "test_smb_source",
        description = "检测已保存的 SMB 片源能否连接并读取共享根目录，结果会更新电视端失效标记。",
        inputSchema = objectSchema(
            mapOf("source_id" to stringProperty("片源 id；只配置了一个片源时可省略"))
        )
    ) { args ->
        val source = resolveSource(args.stringValue("source_id"))
        val entries = try {
            withContext(Dispatchers.IO) { pool.list(source, "") }
        } catch (e: Exception) {
            val message = (e.message ?: "连接失败").take(300)
            sources.upsert(
                source.copy(
                    invalid = true,
                    lastError = message,
                    failedAt = System.currentTimeMillis()
                )
            )
            pool.invalidate(source.id)
            onUiChanged()
            throw ToolException("检测失败：$message")
        }
        sources.upsert(source.copy(invalid = false, lastError = null, failedAt = 0L))
        onUiChanged()
        val directories = entries.count { it.isDirectory }
        val files = entries.size - directories
        ToolResult(
            text = "连接成功：${source.host}:${source.port}/${source.share}（$directories 个目录，$files 个文件）",
            structured = buildJsonObject {
                put("ok", true)
                put("source_id", source.id)
                put("directories", directories)
                put("files", files)
            }
        )
    }

    private fun browseTool() = McpTool(
        name = "browse",
        description = "浏览 SMB 共享目录，返回子目录和文件。path 是相对共享根目录的路径，留空表示根目录。",
        inputSchema = objectSchema(
            mapOf(
                "source_id" to stringProperty("片源 id；只配置了一个片源时可省略"),
                "path" to stringProperty("相对共享根目录的路径，例如 Movies/剧集")
            )
        )
    ) { args ->
        val source = resolveSource(args.stringValue("source_id"))
        val path = args.stringValue("path").orEmpty()
        val normalized = try {
            SmbPath.normalize(path)
        } catch (e: IllegalArgumentException) {
            throw ToolException(e.message ?: "路径无效")
        }
        val entries = try {
            withContext(Dispatchers.IO) { pool.list(source, normalized) }
        } catch (e: Exception) {
            throw ToolException(e.message ?: "目录读取失败")
        }
        val shown = entries.take(MAX_BROWSE_ENTRIES)
        val dirs = entries.count { it.isDirectory }
        val files = entries.size - dirs
        val text = buildString {
            append("/")
            if (normalized.isNotEmpty()) append(normalized)
            append("（$dirs 个目录，$files 个文件）")
            if (entries.isEmpty()) {
                append("\n（空目录或无权限）")
            }
            shown.forEach { entry ->
                append("\n")
                if (entry.isDirectory) {
                    append("[目录] ")
                    append(entry.name)
                } else {
                    append("[文件] ")
                    append(entry.name)
                    append("  ")
                    append(formatSize(entry.size))
                }
                append("  path=")
                append(entry.path)
            }
            if (entries.size > shown.size) {
                append("\n（仅显示前 ${shown.size} 条）")
            }
        }
        ToolResult(
            text = text,
            structured = buildJsonObject {
                put("source_id", source.id)
                put("path", normalized)
                put("truncated", entries.size > shown.size)
                put(
                    "entries",
                    buildJsonArray {
                        shown.forEach { entry ->
                            add(
                                buildJsonObject {
                                    put("name", entry.name)
                                    put("path", entry.path)
                                    put("is_dir", entry.isDirectory)
                                    put("size_bytes", entry.size)
                                }
                            )
                        }
                    }
                )
            }
        )
    }

    private fun listIptvSourcesTool() = McpTool(
        name = "list_iptv_sources",
        description = "列出电视端已配置的 M3U/M3U8 电视源，含缓存频道数和失效状态。",
        inputSchema = objectSchema(emptyMap())
    ) {
        val list = iptv.all()
        val counts = list.associate { it.id to iptvChannels.get(it.id).size }
        val text = if (list.isEmpty()) {
            "电视端还没有配置 M3U 电视源。可调用 save_iptv_source 添加。"
        } else {
            list.joinToString("\n") { source ->
                val state = if (source.invalid) " | 失效：${source.lastError.orEmpty()}" else ""
                "${source.id} | ${source.name} | ${source.url} | ${counts[source.id]} 个频道$state"
            }
        }
        ToolResult(
            text = text,
            structured = buildJsonObject {
                put(
                    "sources",
                    buildJsonArray {
                        list.forEach { source ->
                            add(
                                buildJsonObject {
                                    put("id", source.id)
                                    put("name", source.name)
                                    put("url", source.url)
                                    put("channels", counts[source.id] ?: 0)
                                    put("invalid", source.invalid)
                                    source.lastError?.let { put("last_error", it) }
                                    put("failed_at", source.failedAt)
                                }
                            )
                        }
                    }
                )
            }
        )
    }

    private fun saveIptvSourceTool() = McpTool(
        name = "save_iptv_source",
        description = "新增或修改 M3U 电视源；省略 source_id 为新增；修改时只传要改的字段。不会自动拉取频道，可调用 test_iptv_source。",
        inputSchema = objectSchema(
            mapOf(
                "source_id" to stringProperty("要修改的电视源 id；省略则新增"),
                "name" to stringProperty("显示名称；新增时默认使用 URL 主机名"),
                "url" to stringProperty("M3U / M3U8 地址，http/https")
            )
        )
    ) { args ->
        val id = args.stringValue("source_id")
        val existing = id?.let { iptv.get(it) ?: throw ToolException("未找到电视源：$it") }
        val url = (args.stringValue("url")?.trim() ?: existing?.url)
            ?.takeIf { it.isNotBlank() }
            ?: throw ToolException("需要 url")
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") throw ToolException("url 仅支持 http/https")
        val name = args.stringValue("name")?.trim()?.takeIf { it.isNotBlank() }
            ?: existing?.name
            ?: uri?.host?.takeIf { it.isNotBlank() }
            ?: "M3U 电视源"
        val sameUrl = existing != null && existing.url == url
        val source = IptvSource(
            id = existing?.id ?: iptv.newId(),
            name = name,
            url = url,
            invalid = if (sameUrl) existing?.invalid ?: false else false,
            lastError = if (sameUrl) existing?.lastError else null,
            failedAt = if (sameUrl) existing?.failedAt ?: 0L else 0L
        )
        iptv.upsert(source)
        if (existing != null && !sameUrl) iptvChannels.removeSource(source.id)
        onUiChanged()
        ToolResult(
            text = if (existing == null) {
                "已添加电视源：${source.name}（${source.id}）\n可调用 test_iptv_source 拉取频道。"
            } else {
                "已更新电视源：${source.name}（${source.id}）\n可调用 test_iptv_source 刷新频道。"
            },
            structured = buildJsonObject {
                put("created", existing == null)
                put("source_id", source.id)
                put("name", source.name)
                put("url", source.url)
                put("channels", iptvChannels.get(source.id).size)
            }
        )
    }

    private fun deleteIptvSourceTool() = McpTool(
        name = "delete_iptv_source",
        description = "删除电视本地保存的 M3U 电视源和缓存频道，不会删除远程 M3U 文件。",
        inputSchema = objectSchema(
            mapOf("source_id" to stringProperty("要删除的电视源 id")),
            required = listOf("source_id")
        )
    ) { args ->
        val id = args.stringValue("source_id") ?: throw ToolException("需要 source_id")
        val source = iptv.get(id) ?: throw ToolException("未找到电视源：$id")
        iptv.delete(id)
        iptvChannels.removeSource(id)
        onUiChanged()
        ToolResult(
            text = "已删除电视源：${source.name}（$id）",
            structured = buildJsonObject {
                put("deleted", true)
                put("source_id", id)
            }
        )
    }

    private fun testIptvSourceTool() = McpTool(
        name = "test_iptv_source",
        description = "拉取并解析 M3U 电视源，更新频道缓存和失效标记，返回频道数、分组数和前几个频道。",
        inputSchema = objectSchema(
            mapOf("source_id" to stringProperty("电视源 id；只配置了一个电视源时可省略"))
        )
    ) { args ->
        val source = resolveIptvSource(args.stringValue("source_id"))
        val channels = refreshIptv(source)
        val groups = channels.mapNotNull { it.group?.takeIf { group -> group.isNotBlank() } }.distinct()
        val preview = channels.take(8)
        val text = buildString {
            append("连接成功：${source.name}（${channels.size} 个频道，${groups.size} 个分组）")
            preview.forEach { channel ->
                append("\n")
                append(channel.name)
                channel.group?.takeIf { it.isNotBlank() }?.let {
                    append(" | ")
                    append(it)
                }
            }
            if (channels.size > preview.size) append("\n（仅显示前 ${preview.size} 个）")
        }
        ToolResult(
            text = text,
            structured = buildJsonObject {
                put("ok", true)
                put("source_id", source.id)
                put("name", source.name)
                put("channels", channels.size)
                put("groups", groups.size)
                put(
                    "preview",
                    buildJsonArray {
                        preview.forEach { channel ->
                            add(
                                buildJsonObject {
                                    put("name", channel.name)
                                    put("group", channel.group ?: "")
                                }
                            )
                        }
                    }
                )
            }
        )
    }

    private fun listChannelsTool() = McpTool(
        name = "list_channels",
        description = "列出电视源的频道；可用 query 按名称/分组过滤，limit 默认 100 最大 500；缓存为空或 refresh=true 时会重新拉取 M3U。",
        inputSchema = objectSchema(
            mapOf(
                "source_id" to stringProperty("电视源 id；只配置了一个电视源时可省略"),
                "query" to stringProperty("按频道名或分组过滤，不区分大小写"),
                "group" to stringProperty("只返回该分组的频道"),
                "limit" to integerProperty("返回条数，默认 100，最大 500", minimum = 1, maximum = 500, default = 100),
                "refresh" to booleanProperty("是否先重新拉取 M3U，默认 false", default = false)
            )
        )
    ) { args ->
        val source = resolveIptvSource(args.stringValue("source_id"))
        var channels = iptvChannels.get(source.id)
        if (channels.isEmpty() || args.booleanValue("refresh") == true) {
            channels = refreshIptv(source)
        }
        val query = args.stringValue("query")?.trim()
        val group = args.stringValue("group")?.trim()
        val matched = channels.mapIndexed { index, channel -> index to channel }.filter { (_, channel) ->
            (group == null || channel.group?.equals(group, ignoreCase = true) == true) &&
                (query == null ||
                    channel.name.contains(query, ignoreCase = true) ||
                    channel.group?.contains(query, ignoreCase = true) == true)
        }
        val limit = (args.intValue("limit") ?: 100).coerceIn(1, 500)
        val shown = matched.take(limit)
        val text = if (matched.isEmpty()) {
            "没有匹配的频道（共 ${channels.size} 个）"
        } else {
            buildString {
                append("${source.name}：匹配 ${matched.size} / ${channels.size} 个频道")
                shown.forEach { (index, channel) ->
                    append("\n")
                    append(index)
                    append(" | ")
                    append(channel.name)
                    channel.group?.takeIf { it.isNotBlank() }?.let {
                        append(" | ")
                        append(it)
                    }
                    if (channel.invalid) {
                        append(" | 失效：")
                        append(channel.lastError ?: "播放失败")
                    }
                }
                if (matched.size > shown.size) append("\n（仅显示前 ${shown.size} 条）")
            }
        }
        ToolResult(
            text = text,
            structured = buildJsonObject {
                put("source_id", source.id)
                put("source_name", source.name)
                put("total", channels.size)
                put("matched", matched.size)
                put("truncated", matched.size > shown.size)
                put(
                    "channels",
                    buildJsonArray {
                        shown.forEach { (index, channel) ->
                            add(
                                buildJsonObject {
                                    put("index", index)
                                    put("name", channel.name)
                                    put("group", channel.group ?: "")
                                    put("url", channel.url)
                                    put("invalid", channel.invalid)
                                    channel.lastError?.let { put("last_error", it) }
                                }
                            )
                        }
                    }
                )
            }
        )
    }

    private fun playChannelTool() = McpTool(
        name = "play_channel",
        description = "播放 M3U 电视源中的频道；可用 index（list_channels 返回的序号）、name（精确或唯一模糊匹配）或 url。直播默认从头播，不续播。",
        inputSchema = objectSchema(
            mapOf(
                "source_id" to stringProperty("电视源 id；只配置了一个电视源时可省略"),
                "index" to integerProperty("list_channels 返回的 index", minimum = 0),
                "name" to stringProperty("频道名，精确匹配优先；多个模糊匹配时会报错"),
                "url" to stringProperty("直接播放的 http/https 频道地址，传了就忽略 source_id/index/name"),
                "title" to stringProperty("显示标题，默认取频道名")
            )
        )
    ) { args ->
        val directUrl = args.stringValue("url")
        if (directUrl == null && args.intValue("index") == null && args.stringValue("name") == null) {
            throw ToolException("需要 index、name 或 url")
        }
        val titleArg = args.stringValue("title")
        val source: IptvSource?
        val channel: CachedChannel?
        val mediaUrl: String
        val mediaTitle: String
        if (directUrl != null) {
            val uri = runCatching { Uri.parse(directUrl) }.getOrNull()
            val scheme = uri?.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") throw ToolException("url 仅支持 http/https")
            source = null
            channel = null
            mediaUrl = directUrl
            mediaTitle = titleArg
                ?: directUrl.substringAfterLast('/').substringBefore('?').ifBlank { directUrl }
        } else {
            val resolved = resolveIptvSource(args.stringValue("source_id"))
            var channels = iptvChannels.get(resolved.id)
            if (channels.isEmpty()) channels = refreshIptv(resolved)
            val index = args.intValue("index")
            val name = args.stringValue("name")
            val selected = when {
                index != null -> channels.getOrNull(index)
                    ?: throw ToolException("index 超出范围：$index（共 ${channels.size} 个频道）")
                name != null -> {
                    val indexed = channels.mapIndexed { i, item -> i to item }
                    val exact = indexed.filter { it.second.name == name }
                    val matches = if (exact.isNotEmpty()) {
                        exact
                    } else {
                        indexed.filter { it.second.name.contains(name, ignoreCase = true) }
                    }
                    if (matches.isEmpty()) throw ToolException("未找到频道：$name")
                    if (matches.size > 1) {
                        val list = matches.take(10).joinToString("；") { "${it.first}=${it.second.name}" }
                        throw ToolException("有 ${matches.size} 个频道匹配，请改用 index：$list")
                    }
                    matches.first().second
                }
                else -> throw ToolException("需要 index、name 或 url")
            }
            source = resolved
            channel = selected
            mediaUrl = selected.url
            mediaTitle = titleArg ?: selected.name
        }
        val playArgs = buildJsonObject {
            put("url", mediaUrl)
            put("title", mediaTitle)
        }
        val media = MediaSpec.single(sources, history, playArgs, allowResume = false)
        val status = onMain {
            val manager = playerProvider()
            manager.play(media)
            manager.snapshot()
        }
        onMain { onPlaybackStarted() }
        val sourceLine = source?.let { "\n来源：${it.name}" }.orEmpty()
        val channelLine = channel?.let { item ->
            val group = item.group?.takeIf { it.isNotBlank() }?.let { " | $it" }.orEmpty()
            val invalid = if (item.invalid) " | 上次失效：${item.lastError ?: "播放失败"}" else ""
            "\n频道：${item.name}$group$invalid"
        }.orEmpty()
        ToolResult(
            text = "已开始播放：$mediaTitle$sourceLine$channelLine\n${statusText(status)}",
            structured = statusJson(status).let { base ->
                JsonObject(
                    base.toMutableMap().apply {
                        put("title", JsonPrimitive(mediaTitle))
                        source?.let { put("source_id", JsonPrimitive(it.id)) }
                        channel?.let { item ->
                            put("channel_name", JsonPrimitive(item.name))
                            put("channel_group", JsonPrimitive(item.group ?: ""))
                            put("channel_url", JsonPrimitive(item.url))
                            put("channel_invalid", JsonPrimitive(item.invalid))
                        }
                    }
                )
            }
        )
    }

    private fun showListTool() = McpTool(
        name = "show_list",
        description = "把一组媒体/频道列表下发到电视首页（菜单 → AI 列表也可打开），用户可以直接用遥控器选择。会覆盖上一份 AI 列表，最多 200 项；items 为空表示清空。",
        inputSchema = objectSchema(
            mapOf(
                "name" to stringProperty("列表名称，默认 AI 列表"),
                "items" to arrayProperty(
                    "按顺序展示的条目",
                    objectSchema(
                        mapOf(
                            "title" to stringProperty("标题"),
                            "url" to stringProperty("http/https 媒体地址，与 source_id + path 二选一"),
                            "source_id" to stringProperty("SMB 片源 id"),
                            "path" to stringProperty("SMB 文件路径"),
                            "group" to stringProperty("可选的分类/副标题")
                        )
                    )
                )
            ),
            required = listOf("items")
        )
    ) { args ->
        val array = args.arrayValue("items") ?: throw ToolException("需要 items 数组")
        if (array.size > 200) throw ToolException("items 最多 200 项")
        val listName = args.stringValue("name") ?: "AI 列表"
        val parsed = if (array.isEmpty()) emptyList() else array.mapIndexed { index, element ->
            val obj = element as? JsonObject ?: throw ToolException("items[$index] 必须是对象")
            val url = obj.stringValue("url")
            val rawPath = obj.stringValue("path")
            val sourceId = obj.stringValue("source_id")
            if (url != null && rawPath != null) throw ToolException("items[$index] url 和 path 只能二选一")
            if (url == null && rawPath == null) throw ToolException("items[$index] 需要 url 或 source_id + path")
            if (url != null) {
                val uri = runCatching { android.net.Uri.parse(url) }.getOrNull()
                val scheme = uri?.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") throw ToolException("items[$index] url 仅支持 http/https")
                AiListItem(
                    title = obj.stringValue("title") ?: url.substringAfterLast('/').substringBefore('?').ifBlank { url },
                    url = url,
                    group = obj.stringValue("group")
                )
            } else {
                val source = resolveSource(sourceId)
                val path = try {
                    SmbPath.normalize(rawPath)
                } catch (e: IllegalArgumentException) {
                    throw ToolException("items[$index] 路径无效：${e.message}")
                }
                if (path.isEmpty()) throw ToolException("items[$index] path 不能为空")
                AiListItem(
                    title = obj.stringValue("title") ?: path.substringAfterLast('/').ifBlank { source.name },
                    sourceId = source.id,
                    path = path,
                    group = obj.stringValue("group")
                )
            }
        }
        if (parsed.isEmpty()) aiList.clear() else aiList.replace(listName, parsed)
        onUiChanged()
        ToolResult(
            text = if (parsed.isEmpty()) "已清空电视首页的 AI 列表"
            else "已更新电视首页的 AI 列表：$listName（${parsed.size} 项）",
            structured = buildJsonObject {
                put("name", listName)
                put("count", parsed.size)
            }
        )
    }

    private fun playTool() = McpTool(
        name = "play",
        description = "在电视上播放媒体。可以用 url（http/https）或 source_id + path（SMB）。path 必须是 browse 返回的相对共享根目录完整路径，不是文件名；path 也可以是文件夹，会把文件夹内媒体文件按文件名排序连播。默认 SMB 播放会自动把同目录媒体排成队列（上一集/下一集、播完自动连播），queue_folder=false 可关闭。默认会从上次进度续播。",
        inputSchema = objectSchema(
            mapOf(
                "url" to stringProperty("http/https 媒体地址，与 path 二选一"),
                "source_id" to stringProperty("SMB 片源 id；只配置了一个片源时可省略"),
                "path" to stringProperty("SMB 共享内的完整相对路径（browse 返回的 path 字段，例如 目录/文件名，也可以是目录路径）；不能只传文件名"),
                "queue_folder" to booleanProperty("SMB 播放时是否把同目录（或该目录本身）的可播放媒体文件按文件名排序加入队列，默认 true", default = true),
                "title" to stringProperty("显示标题，默认取文件名"),
                "start_ms" to integerProperty("起始播放位置（毫秒），传入后不再自动续播", minimum = 0),
                "resume" to booleanProperty("未传 start_ms 时是否从上次进度续播，默认 true", default = true),
                "intro_end_ms" to integerProperty("片头结束位置（毫秒），播放越过该点时自动跳到此处", minimum = 0),
                "outro_start_ms" to integerProperty("片尾开始位置（毫秒），播放越过该点时跳到结尾或下一集", minimum = 0),
                "auto_skip_intro" to booleanProperty("是否自动跳过片头，默认 true", default = true),
                "auto_skip_outro" to booleanProperty("是否自动跳过片尾，默认 true", default = true),
                "mime_type" to stringProperty("可选，显式指定 MIME 类型，例如 video/x-matroska"),
                "subtitle_path" to stringProperty("可选，SMB 同片源外挂字幕路径，支持 srt/vtt/ass/ssa"),
                "subtitle_url" to stringProperty("可选，http/https 外挂字幕地址"),
                "subtitle_language" to stringProperty("可选，外挂字幕语言，例如 zh、en")
            )
        )
    ) { args ->
        val media = MediaSpec.single(sources, history, args, allowResume = true)
        val plan = MediaFiles.buildPlayPlan(sources, pool, args, media, args.booleanValue("queue_folder") ?: true)
        val status = onMain {
            val pm = playerProvider()
            if (plan.items.size > 1) {
                pm.playQueue(plan.items, plan.startIndex, plan.startMs)
            } else {
                pm.play(plan.items[plan.startIndex])
            }
            pm.snapshot()
        }
        onMain { onPlaybackStarted() }
        val sourceLine = media.sourceLabel?.let { "\n来源：$it" }.orEmpty()
        val noteLine = plan.note?.let { "\n$it" }.orEmpty()
        ToolResult(
            text = "已开始播放：${media.title}$sourceLine$noteLine\n${statusText(status)}",
            structured = statusJson(status).let { base ->
                JsonObject(
                    base.toMutableMap().apply {
                        put("title", JsonPrimitive(media.title))
                        put("started_at_ms", JsonPrimitive(media.startMs))
                    }
                )
            }
        )
    }

    private fun playQueueTool() = McpTool(
        name = "play_queue",
        description = "把多个媒体加入播放队列并开始播放，自动连播。每项可用 url 或 source_id + path，可带片头片尾参数。",
        inputSchema = objectSchema(
            mapOf(
                "items" to arrayProperty(
                    "按顺序播放的媒体列表",
                    objectSchema(
                        mapOf(
                            "url" to stringProperty("http/https 地址，与 path 二选一"),
                            "source_id" to stringProperty("SMB 片源 id，可省略"),
                            "path" to stringProperty("SMB 文件路径"),
                            "title" to stringProperty("显示标题"),
                            "intro_end_ms" to integerProperty("片头结束位置（毫秒）", minimum = 0),
                            "outro_start_ms" to integerProperty("片尾开始位置（毫秒）", minimum = 0),
                            "auto_skip_intro" to booleanProperty("是否自动跳过片头", default = true),
                            "auto_skip_outro" to booleanProperty("是否自动跳过片尾", default = true),
                            "mime_type" to stringProperty("可选 MIME 类型"),
                            "subtitle_path" to stringProperty("可选，同片源外挂字幕路径"),
                            "subtitle_url" to stringProperty("可选，http/https 外挂字幕地址"),
                            "subtitle_language" to stringProperty("可选，外挂字幕语言")
                        )
                    )
                ),
                "source_id" to stringProperty("所有条目的默认片源 id"),
                "start_index" to integerProperty("从第几项开始（从 0 开始），默认 0", minimum = 0),
                "start_ms" to integerProperty("起始位置（毫秒），默认 0", minimum = 0),
                "auto_skip_intro" to booleanProperty("所有条目的默认片头自动跳过，默认 true", default = true),
                "auto_skip_outro" to booleanProperty("所有条目的默认片尾自动跳过，默认 true", default = true)
            ),
            required = listOf("items")
        )
    ) { args ->
        val array = args.arrayValue("items")
            ?: throw ToolException("需要 items 数组")
        if (array.isEmpty()) throw ToolException("items 不能为空")
        if (array.size > MAX_QUEUE_ITEMS) throw ToolException("队列最多 $MAX_QUEUE_ITEMS 项")
        val defaultSourceId = args.stringValue("source_id")
        val defaultAutoIntro = args.booleanValue("auto_skip_intro")
        val defaultAutoOutro = args.booleanValue("auto_skip_outro")
        val resolved = array.mapIndexed { index, element ->
            val obj = element as? JsonObject
                ?: throw ToolException("items[$index] 必须是对象")
            val merged = obj.toMutableMap()
            if (defaultSourceId != null && "source_id" !in merged) {
                merged["source_id"] = JsonPrimitive(defaultSourceId)
            }
            if (defaultAutoIntro != null && "auto_skip_intro" !in merged) {
                merged["auto_skip_intro"] = JsonPrimitive(defaultAutoIntro)
            }
            if (defaultAutoOutro != null && "auto_skip_outro" !in merged) {
                merged["auto_skip_outro"] = JsonPrimitive(defaultAutoOutro)
            }
            try {
                MediaSpec.queueItem(sources, JsonObject(merged))
            } catch (e: ToolException) {
                throw ToolException("items[$index]：${e.message}")
            }
        }
        val startIndex = (args.intValue("start_index") ?: 0).coerceIn(0, resolved.lastIndex)
        val startMs = (args.longValue("start_ms") ?: 0L).coerceAtLeast(0L)
        verifySmbFile(resolved[startIndex].item)
        val status = onMain {
            val pm = playerProvider()
            pm.playQueue(resolved, startIndex, startMs)
            pm.snapshot()
        }
        onMain { onPlaybackStarted() }
        ToolResult(
            text = "队列已开始：第 ${startIndex + 1}/${resolved.size} 项，${resolved[startIndex].title}\n${statusText(status)}",
            structured = statusJson(status)
        )
    }

    private fun controlTool() = McpTool(
        name = "control",
        description = "控制当前播放：play/resume、pause、stop、seek、seek_by、next、previous、set_volume。",
        inputSchema = objectSchema(
            mapOf(
                "action" to stringProperty(
                    "控制动作",
                    enumValues = listOf(
                        "play", "pause", "stop", "seek", "seek_by", "next", "previous", "set_volume"
                    )
                ),
                "position_ms" to integerProperty("seek 的绝对位置（毫秒）", minimum = 0),
                "offset_ms" to integerProperty("seek_by 的相对偏移（毫秒，可为负）"),
                "volume" to numberProperty("set_volume 的音量，0.0 到 1.0", minimum = 0.0, maximum = 1.0)
            ),
            required = listOf("action")
        )
    ) { args ->
        val action = args.stringValue("action") ?: throw ToolException("缺少 action")
        val status = onMain {
            val pm = playerProvider()
            when (action) {
                "play", "resume" -> pm.resume()
                "pause" -> pm.pause()
                "stop" -> pm.stop()
                "seek" -> pm.seekTo(
                    args.longValue("position_ms")
                        ?: throw ToolException("seek 需要 position_ms")
                )
                "seek_by" -> pm.seekBy(
                    args.longValue("offset_ms")
                        ?: throw ToolException("seek_by 需要 offset_ms")
                )
                "next" -> pm.next()
                "previous" -> pm.previous()
                "set_volume" -> pm.setVolume(
                    (args.doubleValue("volume")
                        ?: throw ToolException("set_volume 需要 volume")).toFloat()
                )
                else -> throw ToolException("未知 action：$action")
            }
            pm.snapshot()
        }
        ToolResult("操作完成：$action\n${statusText(status)}", statusJson(status))
    }

    private fun statusTool() = McpTool(
        name = "status",
        description = "查询电视当前播放状态：是否在播放、标题、进度、音量、队列位置和错误信息。",
        inputSchema = objectSchema(emptyMap())
    ) {
        val status = onMain { playerProvider().snapshot() }
        ToolResult(statusText(status), statusJson(status))
    }

    private fun listSubtitlesTool() = McpTool(
        name = "list_subtitles",
        description = "列出当前播放媒体的字幕轨，以及 SMB 视频同目录下可加载的外挂字幕文件。",
        inputSchema = objectSchema(emptyMap())
    ) {
        val manager = onMain { playerProvider() }
        val status = onMain { manager.snapshot() }
        if (!status.hasMedia) throw ToolException("当前没有播放内容")
        val info = onMain { manager.currentMediaInfo() }
        val tracks = onMain { manager.subtitleTracks() }
        val attached = onMain { manager.attachedSubtitleUris() }
        val files = if (info?.sourceId != null && info.path != null) {
            val source = sources.get(info.sourceId)
            if (source != null) {
                withContext(Dispatchers.IO) {
                    SubtitleFiles.discover(pool, source, info.path)
                }.filterNot { it.uri() in attached }
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }
        val selectedIndex = tracks.firstOrNull { it.selected }?.index
        val text = buildString {
            append("字幕（${status.title ?: "未命名"}）：")
            append(if (selectedIndex == null) "关闭" else "已开启")
            if (tracks.isEmpty()) {
                append("\n（没有内嵌字幕轨，可能仍在加载）")
            } else {
                tracks.forEach { track ->
                    append("\n")
                    append(if (track.selected) "* " else "  ")
                    append(track.index)
                    append(" | ")
                    append(track.label ?: track.language ?: "未命名")
                    append(" | ")
                    append(track.mimeType ?: "未知")
                }
            }
            if (files.isNotEmpty()) {
                append("\n可加载的外挂字幕：")
                files.forEach {
                    append("\n- ")
                    append(it.name)
                    append("  ")
                    append(it.path)
                }
            }
        }
        ToolResult(
            text = text,
            structured = buildJsonObject {
                put("has_media", true)
                put("selected_index", selectedIndex ?: -1)
                put(
                    "tracks",
                    buildJsonArray {
                        tracks.forEach { track ->
                            add(
                                buildJsonObject {
                                    put("index", track.index)
                                    put("language", track.language ?: "")
                                    put("label", track.label ?: "")
                                    put("mime_type", track.mimeType ?: "")
                                    put("selected", track.selected)
                                }
                            )
                        }
                    }
                )
                put(
                    "files",
                    buildJsonArray {
                        files.forEach { file ->
                            add(
                                buildJsonObject {
                                    put("name", file.name)
                                    put("path", file.path)
                                    put("source_id", file.sourceId)
                                    put("mime_type", file.mimeType)
                                }
                            )
                        }
                    }
                )
            }
        )
    }

    private fun setSubtitleTool() = McpTool(
        name = "set_subtitle",
        description = "控制当前字幕：按轨道索引选择、关闭，或加载 SMB / HTTP 外挂字幕。三个动作传一个即可。",
        inputSchema = objectSchema(
            mapOf(
                "track_index" to integerProperty("要选择的字幕轨索引；先用 list_subtitles 获取", minimum = 0),
                "off" to booleanProperty("true 表示关闭所有字幕"),
                "path" to stringProperty("SMB 外挂字幕路径，支持 srt/vtt/ass/ssa"),
                "url" to stringProperty("http/https 外挂字幕地址"),
                "source_id" to stringProperty("path 所属 SMB 片源 id，默认使用当前片源"),
                "language" to stringProperty("外挂字幕语言，例如 zh、en"),
                "label" to stringProperty("外挂字幕显示名称，默认取文件名")
            )
        )
    ) { args ->
        val off = args.booleanValue("off") ?: false
        val index = args.intValue("track_index")
        val path = args.stringValue("path")
        val url = args.stringValue("url")
        val actions = listOf(off, index != null, path != null, url != null).count { it }
        if (actions != 1) throw ToolException("track_index、off、path、url 必须且只能传一个")
        when {
            off -> {
                onMain { playerProvider().disableSubtitles() }
                ToolResult("已关闭字幕")
            }
            index != null -> {
                val selected = onMain {
                    val pm = playerProvider()
                    pm.selectSubtitle(index)
                    pm.subtitleTracks().firstOrNull { it.selected }
                }
                ToolResult("已切换字幕：${selected?.label ?: selected?.language ?: "轨道 $index"}")
            }
            path != null -> {
                val info = onMain { playerProvider().currentMediaInfo() }
                val source = args.stringValue("source_id")?.let {
                    sources.get(it) ?: throw ToolException("未找到片源：$it")
                } ?: info?.sourceId?.let { sources.get(it) } ?: resolveSource(null)
                val normalized = try {
                    SmbPath.normalize(path)
                } catch (e: IllegalArgumentException) {
                    throw ToolException(e.message ?: "路径无效")
                }
                if (normalized.isEmpty()) throw ToolException("path 不能为空")
                val mimeType = SubtitleFiles.mimeType(normalized)
                    ?: throw ToolException("不支持的字幕格式：${normalized.substringAfterLast('.', "")}")
                val label = args.stringValue("label") ?: normalized.substringAfterLast('/')
                onMain {
                    playerProvider().attachSubtitle(
                        Uri.parse(SmbPath.smbUri(source.id, normalized)),
                        mimeType,
                        args.stringValue("language"),
                        label
                    )
                }
                ToolResult("已加载外挂字幕：$label")
            }
            else -> {
                val subtitleUrl = url ?: throw ToolException("需要 path 或 url")
                val uri = runCatching { Uri.parse(subtitleUrl) }.getOrNull()
                val scheme = uri?.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") throw ToolException("url 仅支持 http/https")
                val name = subtitleUrl.substringAfterLast('/').substringBefore('?').ifBlank { "字幕" }
                val mimeType = SubtitleFiles.mimeType(name)
                    ?: throw ToolException("不支持的字幕格式：${name.substringAfterLast('.', "")}")
                val label = args.stringValue("label") ?: name
                onMain {
                    playerProvider().attachSubtitle(
                        uri!!,
                        mimeType,
                        args.stringValue("language"),
                        label
                    )
                }
                ToolResult("已加载外挂字幕：$label")
            }
        }
    }

    private fun setSubtitleStyleTool() = McpTool(
        name = "set_subtitle_style",
        description = "调整字幕显示样式：字号、文字颜色、背景和底部位置。只传要改的字段。",
        inputSchema = objectSchema(
            mapOf(
                "font_scale" to numberProperty("字号倍率，0.5 到 2.0，默认 1.0", minimum = 0.5, maximum = 2.0),
                "text_color" to stringProperty(
                    "文字颜色",
                    enumValues = listOf("white", "yellow", "cyan", "green")
                ),
                "background" to stringProperty(
                    "字幕背景",
                    enumValues = listOf("none", "dim", "solid")
                ),
                "bottom" to numberProperty("距底部比例，0 到 0.35，默认 0.08", minimum = 0.0, maximum = 0.35)
            )
        )
    ) { args ->
        val current = settings.subtitleStyle()
        var updated = current
        val fontScale = args.doubleValue("font_scale")?.toFloat()
        val bottom = args.doubleValue("bottom")?.toFloat()
        val textColor = args.stringValue("text_color")
        val background = args.stringValue("background")
        if (fontScale == null && bottom == null && textColor == null && background == null) {
            throw ToolException("至少需要 font_scale、text_color、background、bottom 中的一项")
        }
        if (fontScale != null) {
            if (fontScale < 0.5f || fontScale > 2.0f) throw ToolException("font_scale 范围是 0.5 到 2.0")
            updated = updated.copy(fontScale = fontScale)
        }
        if (bottom != null) {
            if (bottom < 0f || bottom > 0.35f) throw ToolException("bottom 范围是 0 到 0.35")
            updated = updated.copy(bottomFraction = bottom)
        }
        if (textColor != null) {
            val color = when (textColor) {
                "white" -> Color.WHITE
                "yellow" -> 0xFFFFEB3B.toInt()
                "cyan" -> 0xFF80DEEA.toInt()
                "green" -> 0xFFA5D6A7.toInt()
                else -> throw ToolException("不支持的文字颜色：$textColor")
            }
            updated = updated.copy(textColor = color)
        }
        if (background != null) {
            val color = when (background) {
                "none" -> Color.TRANSPARENT
                "dim" -> 0x99000000.toInt()
                "solid" -> Color.BLACK
                else -> throw ToolException("不支持的背景：$background")
            }
            updated = updated.copy(backgroundColor = color)
        }
        settings.setSubtitleStyle(updated)
        onUiChanged()
        ToolResult(
            text = "字幕样式已更新：字号 ${(updated.fontScale * 100).toInt()}%，底部 ${(updated.bottomFraction * 100).toInt()}%",
            structured = subtitleStyleJson(updated)
        )
    }

    private fun historyTool() = McpTool(
        name = "get_history",
        description = "获取最近播放记录，可用于回答“上次看到哪”和继续播放。",
        inputSchema = objectSchema(
            mapOf("limit" to integerProperty("返回条数，默认 10，最大 50", minimum = 1, maximum = 50, default = 10))
        )
    ) { args ->
        val limit = args.intValue("limit") ?: 10
        val entries = history.recent(limit)
        val text = if (entries.isEmpty()) {
            "还没有播放记录。"
        } else {
            val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            entries.joinToString("\n") { entry ->
                val state = if (entry.completed) "已看完" else "未看完"
                val duration = if (entry.durationMs > 0) formatTime(entry.durationMs) else "--:--"
                "${formatter.format(Date(entry.updatedAt))} | ${entry.title} | ${formatTime(entry.positionMs)} / $duration | $state"
            }
        }
        ToolResult(
            text = text,
            structured = buildJsonObject {
                put("entries", buildJsonArray {
                    entries.forEach { entry ->
                        add(
                            buildJsonObject {
                                put("title", entry.title)
                                put("position_ms", entry.positionMs)
                                put("duration_ms", entry.durationMs)
                                put("updated_at", entry.updatedAt)
                                put("completed", entry.completed)
                                entry.sourceId?.let { put("source_id", it) }
                                entry.path?.let { put("path", it) }
                                entry.url?.let { put("url", it) }
                            }
                        )
                    }
                })
            }
        )
    }

    private fun resumeHistoryTool() = McpTool(
        name = "resume_history",
        description = "从播放记录续播最近未看完的媒体；可按标题筛选。用于「继续播放 X」，不要自己拼 SMB 路径。",
        inputSchema = objectSchema(
            mapOf("title" to stringProperty("标题包含的关键字，例如 斗罗大陆；省略则取最近一条未看完记录"))
        )
    ) { args ->
        val query = args.stringValue("title")?.trim()
        val entries = history.recent(MAX_RESUME_HISTORY).filter { !it.completed }
        val entry = if (query != null) {
            entries.firstOrNull { it.title.contains(query, ignoreCase = true) }
                ?: throw ToolException("没有找到标题包含「$query」的未看完记录")
        } else {
            entries.firstOrNull() ?: throw ToolException("没有未看完的播放记录")
        }
        if (entry.url == null && (entry.sourceId == null || entry.path == null)) {
            throw ToolException("这条记录缺少片源信息")
        }
        val playArgs = buildJsonObject {
            entry.sourceId?.let { put("source_id", it) }
            entry.path?.let { put("path", it) }
            entry.url?.let { put("url", it) }
            put("title", entry.title)
            put("resume", true)
        }
        val media = MediaSpec.single(sources, history, playArgs, allowResume = true)
        val plan = MediaFiles.buildPlayPlan(sources, pool, playArgs, media, queueFolder = true)
        val status = onMain {
            val pm = playerProvider()
            if (plan.items.size > 1) {
                pm.playQueue(plan.items, plan.startIndex, plan.startMs)
            } else {
                pm.play(plan.items[plan.startIndex])
            }
            pm.snapshot()
        }
        onMain { onPlaybackStarted() }
        ToolResult(
            text = "已继续播放：${media.title}${plan.note?.let { "\n$it" }.orEmpty()}\n${statusText(status)}",
            structured = statusJson(status).let { base ->
                JsonObject(
                    base.toMutableMap().apply {
                        put("title", JsonPrimitive(media.title))
                        put("started_at_ms", JsonPrimitive(media.startMs))
                        put("resume_from_ms", JsonPrimitive(entry.positionMs))
                        entry.sourceId?.let { put("source_id", JsonPrimitive(it)) }
                        entry.path?.let { put("path", JsonPrimitive(it)) }
                        entry.url?.let { put("url", JsonPrimitive(it)) }
                    }
                )
            }
        )
    }

    private fun normalizeHost(value: String): String =
        value.trim().removePrefix("smb://").trimEnd('/')

    private fun JsonObject.rawString(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun resolveIptvSource(id: String?): IptvSource {
        if (id != null) return iptv.get(id) ?: throw ToolException("未找到电视源：$id")
        val all = iptv.all()
        if (all.isEmpty()) throw ToolException("电视端还没有配置 M3U 电视源")
        if (all.size > 1) {
            throw ToolException("有多个电视源，请指定 source_id：" + all.joinToString(", ") { it.id })
        }
        return all.first()
    }

    private suspend fun refreshIptv(source: IptvSource): List<CachedChannel> {
        val parsed = try {
            withContext(Dispatchers.IO) {
                val text = M3uLoader.load(source.url)
                val channels = M3uParser.parse(text, source.url)
                if (channels.isEmpty()) {
                    listOf(IptvChannel(source.name, source.url, "直接播放"))
                } else {
                    channels
                }
            }
        } catch (e: Exception) {
            val message = (e.message ?: "电视源读取失败").take(300)
            iptv.upsert(
                source.copy(
                    invalid = true,
                    lastError = message,
                    failedAt = System.currentTimeMillis()
                )
            )
            onUiChanged()
            throw ToolException("检测失败：$message")
        }
        val merged = withContext(Dispatchers.IO) {
            iptvChannels.replace(source.id, parsed)
        }
        iptv.upsert(source.copy(invalid = false, lastError = null, failedAt = 0L))
        onUiChanged()
        return merged
    }

    private suspend fun verifySmbFile(item: androidx.media3.common.MediaItem?) {
        MediaFiles.verifySmbFile(sources, pool, item)
    }

    private fun resolveSource(id: String?): SmbSource {
        if (id != null) return sources.get(id) ?: throw ToolException("未找到片源：$id")
        val all = sources.all()
        if (all.isEmpty()) throw ToolException("电视端还没有配置 SMB 片源")
        if (all.size > 1) {
            throw ToolException("有多个片源，请指定 source_id：" + all.joinToString(", ") { it.id })
        }
        return all.first()
    }

    private suspend fun <T> onMain(block: () -> T): T =
        withContext(Dispatchers.Main.immediate) { block() }

    private fun statusText(status: PlaybackStatus): String {
        if (!status.hasMedia) return "当前没有播放内容。"
        return buildString {
            append("状态：")
            append(
                when (status.state) {
                    "buffering" -> "缓冲中"
                    "playing" -> "播放中"
                    "paused" -> "已暂停"
                    "ended" -> "已结束"
                    else -> "空闲"
                }
            )
            append("\n标题：")
            append(status.title ?: "未命名")
            append("\n进度：")
            append(formatTime(status.positionMs))
            append(" / ")
            append(status.durationMs?.let { formatTime(it) } ?: "--:--")
            status.durationMs?.takeIf { it > 0 }?.let {
                append("（")
                append(String.format(Locale.US, "%.1f", status.positionMs * 100.0 / it))
                append("%）")
            }
            append("\n音量：")
            append((status.volume * 100).toInt())
            append("%")
            if (status.queueCount > 1) {
                append("\n队列：")
                append(status.queueIndex)
                append("/")
                append(status.queueCount)
            }
            status.error?.let {
                append("\n错误：")
                append(it)
                if (it.contains("STATUS_OBJECT_NAME_NOT_FOUND", ignoreCase = true) ||
                    it.contains("STATUS_OBJECT_PATH_NOT_FOUND", ignoreCase = true)
                ) {
                    append("\n提示：SMB 路径不存在。path 必须相对共享根目录且包含目录（例如 目录/文件名），可用 browse 获取。")
                }
            }
        }
    }

    private fun statusJson(status: PlaybackStatus): JsonObject = buildJsonObject {
        put("state", status.state)
        put("has_media", status.hasMedia)
        put("is_playing", status.isPlaying)
        put("position_ms", status.positionMs)
        status.durationMs?.let { put("duration_ms", it) }
        put("buffered_ms", status.bufferedMs)
        status.title?.let { put("title", it) }
        status.mediaKey?.let { put("media_key", it) }
        put("queue_index", status.queueIndex)
        put("queue_count", status.queueCount)
        put("volume", status.volume)
        status.error?.let { put("error", it) }
    }

    private fun subtitleStyleJson(style: SubtitleStyle): JsonObject = buildJsonObject {
        put("font_scale", style.fontScale)
        put("bottom", style.bottomFraction)
        put("text_color", when (style.textColor) {
            Color.WHITE -> "white"
            0xFFFFEB3B.toInt() -> "yellow"
            0xFF80DEEA.toInt() -> "cyan"
            0xFFA5D6A7.toInt() -> "green"
            else -> "custom"
        })
        put("background", when (style.backgroundColor) {
            Color.TRANSPARENT -> "none"
            0x99000000.toInt() -> "dim"
            Color.BLACK -> "solid"
            else -> "custom"
        })
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024
        var unit = units[0]
        for (i in units.indices) {
            unit = units[i]
            if (value < 1024 || i == units.lastIndex) break
            value /= 1024
        }
        return String.format(Locale.US, "%.1f %s", value, unit)
    }

    companion object {
        private const val MAX_BROWSE_ENTRIES = 500
        private const val MAX_QUEUE_ITEMS = 100
        private const val MAX_RESUME_HISTORY = 50
    }
}
