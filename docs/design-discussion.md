# 设计讨论记录（历史）

> 这是项目早期与 AI 的设计讨论，包含当时考虑过但**未全部实现**的方案（例如 SSE、官方 MCP SDK、Room、本地 HTTP 代理、音轨切换等）。当前实现、工具列表和安全边界以 [README](../README.md) 为准。

用户：
技术上的可行性： 能不能开发一个apk，给电视调用，然后开放mcp-server，让客户端给他共享文件地址，让电视播放？

AI：
技术上完全可行。你设想的这个方案，本质上是将一个 **Android TV 应用**同时作为 **MCP Server（服务端）** 和 **媒体播放器**，构建一个专为电视场景设计的轻量级“接收器”。

### 🧩 架构拆解：三个核心组件

你的设想可以拆解为三个核心部分，每一部分在技术上都有成熟的方案可以参考。

#### 1. 📡 MCP Server：电视端的“服务接口”
在 Android TV 应用内实现一个 MCP Server，技术上已有开源先例。例如，**AndroidTVMCP** 就是一个专门为 Android TV 提供远程控制功能的 MCP Server 实现。你可以参考其架构，在 APK 中嵌入一个轻量级的 MCP Server，负责：
- **监听客户端请求**：通过 stdio 或 Streamable HTTP 等传输方式接收来自 MCP Client 的指令。
- **解析文件地址**：从请求中提取客户端共享的文件 URL 或本地路径。
- **触发播放动作**：将解析出的媒体地址传递给电视上的播放器组件。

#### 2. 📁 文件地址共享：客户端如何“递”文件
“让客户端给他共享文件地址”是可行的，但需要注意，**MCP 协议本身更侧重于“工具调用”和“资源访问”，而非大文件传输**。因此，更实际的做法是**传递文件的网络地址（URL）**，而非直接传输文件内容。

客户端可以有几种方式“共享”文件地址：
- **直接输入/粘贴 URL**：最简单的方式，客户端将文件的 HTTP/HTTPS 流媒体地址发送给电视。
- **局域网共享路径**：如果文件在局域网内（如 NAS 或 PC），客户端可以发送类似 `smb://192.168.1.100/video.mp4` 的 SMB 路径。
- **云盘/网盘分享链接**：客户端将云盘文件的分享链接发送给电视，电视端应用负责解析和播放。

#### 3. ▶️ 电视端播放：接收地址并播放
电视端收到文件地址后，播放环节本身没有技术障碍。Android TV 上有大量现成的媒体播放器支持播放网络 URL：
- **通用播放器**：如 **Rlex**、**Vimu Media Player** 等，均支持用户输入网络媒体 URL 进行播放。
- **专用接收器**：**ExoAirPlayer** 是一个很好的参考，它本身就是一个无界面的接收器应用，通过 HTTP API 接收视频 URL 并自动全屏播放。你的 APK 完全可以采用类似的设计模式。

### 🛠️ 技术实现路径建议

综合来看，最可行的技术栈和实现路径如下：

1.  **APK 基础框架**：开发一个 Android TV 应用，其核心是一个前台服务（Foreground Service），确保在后台稳定运行。
2.  **MCP Server 集成**：在应用中集成 MCP SDK（如官方提供的 Kotlin/Java 版本），实现一个简单的 MCP Server，暴露一个名为 `play_media` 的工具（Tool）。该工具接受一个 `media_url` 参数。
3.  **媒体播放能力**：在 APK 中集成 **ExoPlayer**（Google 官方的媒体播放库），这是 Android 平台上处理视频播放的标准方案。当 MCP Server 接收到 `media_url` 后，调用 ExoPlayer 开始播放。
4.  **客户端交互**：你的 MCP Client（可以是手机 App、桌面程序或 AI Agent）通过 MCP 协议向电视端的 MCP Server 发送 `play_media` 请求，并附上文件的 URL 地址。

### ⚠️ 需要注意的挑战

虽然整体可行，但在实际开发中需要留意以下几点：

- **网络发现与连接**：电视和客户端需要在同一局域网内，或者通过云端中继服务进行连接。可以借鉴 **TVWizard** 的架构，它包含一个云端中继（Relay）和一个电视端的桥接 APK（Bridge APK）。
- **安全与权限**：电视端 APK 需要具备网络访问权限，并且如果涉及本地文件播放，还需要处理 Android TV 的存储访问权限。此外，MCP Server 的端口应做好访问控制，避免被局域网内未授权的设备调用。
- **媒体格式与编码**：电视端的 ExoPlayer 需要支持目标文件的编码格式（如 H.264, HEVC, AV1 等）。对于复杂的封装格式或编码，可能需要额外配置解码器。
- **MCP 协议限制**：MCP 协议最初为 AI 工具调用设计，直接用它传输大文件并不合适。因此，**通过 MCP 传递“文件地址”而非“文件本身”是更合理的设计**。

### 💎 总结

你设想的“APK + MCP Server + 客户端共享地址 + 电视播放”方案，在技术上是完全可行的。**AndroidTVMCP** 和 **ExoAirPlayer** 这两个开源项目分别验证了“电视端 MCP Server”和“电视端 URL 播放接收器”的可行性。将两者的思路结合，用 ExoPlayer 作为播放内核，用 MCP 作为控制协议，就能构建出你设想的应用。

下面是 Android TV 端 MCP Server 的完整代码实现思路，按项目结构、依赖配置、前台服务、MCP Server 初始化、工具注册、媒体播放集成的顺序展开。

---

## 1. 项目结构建议

```
app/src/main/java/com/example/tvmcp/
├── McpServerService.kt          # 前台服务：承载 MCP Server + ExoPlayer
├── McpServerManager.kt          # MCP Server 初始化与工具注册
├── MediaPlayerManager.kt        # ExoPlayer 封装与播放控制
├── MainActivity.kt              # 显示服务状态、MCP 连接地址
└── ui/                          # Compose UI（可选）
app/src/main/res/
└── xml/network_security_config.xml   # 允许局域网明文 HTTP（开发阶段）
```

MCP Server 运行在前台服务中，保证电视待机或切换输入源时连接不中断。

---

## 2. 依赖配置

在 `app/build.gradle.kts` 中添加：

```kotlin
dependencies {
    // MCP Kotlin SDK（服务端 + 客户端 API）
    implementation("io.modelcontextprotocol:kotlin-sdk:0.7.2")

    // Ktor 服务端引擎（用于 SSE 传输）
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-sse:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    // Media3 ExoPlayer（播放内核）
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    // 前台服务 + 协程
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
```

`kotlin-sdk` 是 umbrella 包，同时包含 client 和 server API。如果只需要服务端能力，也可以只引入 `kotlin-sdk-server`。

---

## 3. 前台服务实现

电视端的 MCP Server 需要常驻运行，必须使用前台服务，避免被系统回收。

```kotlin
class McpServerService : Service() {

    private lateinit var mcpServerManager: McpServerManager
    private lateinit var mediaPlayerManager: MediaPlayerManager

    override fun onCreate() {
        super.onCreate()
        mediaPlayerManager = MediaPlayerManager(this)
        mcpServerManager = McpServerManager(mediaPlayerManager)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        mcpServerManager.start(port = 8080)
        return START_STICKY
    }

    override fun onDestroy() {
        mcpServerManager.stop()
        mediaPlayerManager.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TV MCP Server")
            .setContentText("等待客户端连接…")
            .setSmallIcon(R.drawable.ic_tv)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "mcp_server_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.example.tvmcp.START"
    }
}
```

在 `AndroidManifest.xml` 中声明：

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.INTERNET" />

<service
    android:name=".McpServerService"
    android:foregroundServiceType="mediaPlayback"
    android:exported="false" />
```

Android 14 及以上必须声明 `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 权限，否则 `startForeground()` 会抛出 `SecurityException`。

---

## 4. MCP Server 初始化与工具注册

### 4.1 创建 Server 实例

```kotlin
class McpServerManager(
    private val mediaPlayerManager: MediaPlayerManager
) {
    private var server: Server? = null
    private var ktorServer: ApplicationEngine? = null

    fun start(port: Int = 8080) {
        val mcpServer = Server(
            serverInfo = Implementation(
                name = "android-tv-mcp",
                version = "1.0.0"
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true)
                )
            )
        )

        registerPlayMediaTool(mcpServer)
        registerPlaybackControlTool(mcpServer)

        // 启动 Ktor + SSE 传输
        ktorServer = embeddedServer(CIO, port = port) {
            install(SSE)
            routing {
                sse("/sse") {
                    val transport = SseServerTransport(this)
                    mcpServer.createSession(transport)
                }
            }
        }.start(wait = false)

        server = mcpServer
    }

    fun stop() {
        ktorServer?.stop(1000, 2000)
        server?.close()
    }
}
```

`Server` 类来自 `io.modelcontextprotocol.kotlin.sdk.server`，`createSession(transport)` 负责建立与客户端的 MCP 会话。SSE 端点为 `http://<TV_IP>:8080/sse`，客户端连接该地址后即可发现工具列表。

### 4.2 注册 `play_media` 工具

这是整个方案的核心工具——客户端传入媒体 URL，电视端开始播放。

```kotlin
private fun registerPlayMediaTool(server: Server) {
    server.addTool(
        name = "play_media",
        description = "在电视上播放指定URL的媒体文件。支持HTTP/HTTPS直链、HLS(m3u8)、RTSP等格式。",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("media_url", buildJsonObject {
                    put("type", "string")
                    put("description", "媒体文件的网络地址")
                })
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "可选的显示标题")
                })
            },
            required = listOf("media_url")
        )
    ) { request ->
        val mediaUrl = request.arguments
            ?.get("media_url")
            ?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("错误：缺少 media_url 参数")),
                isError = true
            )

        val title = request.arguments
            ?.get("title")
            ?.jsonPrimitive?.content

        return@addTool try {
            mediaPlayerManager.play(mediaUrl, title)
            CallToolResult(
                content = listOf(
                    TextContent("已开始播放：$mediaUrl")
                )
            )
        } catch (e: Exception) {
            CallToolResult(
                content = listOf(
                    TextContent("播放失败：${e.message}")
                ),
                isError = true
            )
        }
    }
}
```

`addTool` 接受工具名称、描述、输入 JSON Schema 和一个 suspend 处理函数，处理函数返回 `CallToolResult`。`inputSchema` 中的 `required` 字段会帮助 LLM 客户端正确构造调用参数。

### 4.3 注册播放控制工具（可选）

```kotlin
private fun registerPlaybackControlTool(server: Server) {
    server.addTool(
        name = "playback_control",
        description = "控制当前媒体播放状态",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("pause"); add("resume")
                        add("stop"); add("seek_forward")
                        add("seek_backward")
                    })
                })
            },
            required = listOf("action")
        )
    ) { request ->
        val action = request.arguments
            ?.get("action")
            ?.jsonPrimitive?.content ?: ""

        val result = mediaPlayerManager.control(action)
        CallToolResult(content = listOf(TextContent(result)))
    }
}
```

---

## 5. 媒体播放集成（ExoPlayer）

```kotlin
class MediaPlayerManager(private val context: Context) {

    private val player: ExoPlayer by lazy {
        ExoPlayer.Builder(context).build()
    }

    fun play(url: String, title: String? = null) {
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title ?: url.substringAfterLast('/'))
                    .build()
            )
            .build()

        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
    }

    fun control(action: String): String {
        return when (action) {
            "pause" -> { player.pause(); "已暂停" }
            "resume" -> { player.play(); "继续播放" }
            "stop" -> { player.stop(); player.clearMediaItems(); "已停止" }
            "seek_forward" -> {
                val pos = (player.currentPosition + 30_000)
                    .coerceAtMost(player.duration)
                player.seekTo(pos); "快进30秒"
            }
            "seek_backward" -> {
                val pos = (player.currentPosition - 30_000).coerceAtLeast(0)
                player.seekTo(pos); "后退30秒"
            }
            else -> "未知操作：$action"
        }
    }

    fun release() {
        player.release()
    }
}
```

`MediaItem.Builder` 支持同时设置 URI 和媒体元数据（标题、艺术家等），适合在电视端显示播放信息。ExoPlayer 原生支持 HLS、DASH、SmoothStreaming 的自适应流媒体播放。如果需要播放 `rtsp://` 流，只需将 URI 替换为 RTSP 地址即可。

---

## 6. 客户端交互示例

客户端（手机 App、桌面程序或 AI Agent）通过 SSE 连接到电视端的 MCP Server 后，先发现工具，再调用 `play_media`。以下是一个 Kotlin 客户端示例：

```kotlin
suspend fun playOnTv(tvIp: String, mediaUrl: String) {
    val httpClient = HttpClient { install(SSE) }
    val client = Client(
        clientInfo = Implementation(
            name = "tv-controller",
            version = "1.0.0"
        )
    )

    val transport = StreamableHttpClientTransport(
        client = httpClient,
        url = "http://$tvIp:8080/sse"
    )

    client.connect(transport)

    // 调用 play_media 工具
    val result = client.callTool(
        name = "play_media",
        arguments = mapOf(
            "media_url" to JsonPrimitive(mediaUrl),
            "title" to JsonPrimitive("测试视频")
        )
    )

    println(result.content)
    client.close()
}
```

MCP 客户端通过 `StreamableHttpClientTransport` 连接到服务端 URL，然后使用 `callTool` 调用远程工具。如果是 AI Agent 场景（如 Claude Desktop、Goose 等），只需在客户端的 MCP 配置中添加电视端的 SSE 地址即可，Agent 会自动发现 `play_media` 工具并根据自然语言指令调用它。

---

## 7. 关键注意事项

**网络安全配置**：开发阶段电视端使用 HTTP 明文连接，需要在 `AndroidManifest.xml` 中配置 `android:usesCleartextTraffic="true"` 或提供 `network_security_config.xml`，否则 Android 9+ 会阻止所有明文流量。

**端口绑定**：SSE 服务默认绑定 `0.0.0.0:8080` 以便局域网内其他设备访问。生产环境建议加入简单的 token 校验，避免局域网内任意设备都能控制电视。

**ExoPlayer 与前台服务的生命周期**：ExoPlayer 实例建议在 `McpServerService` 的 `onCreate` 中创建、`onDestroy` 中 `release()`，而不是放在 Activity 中。这样即使电视回到主界面，播放和 MCP 连接都不会中断。

**MCP 协议限制**：`play_media` 工具接收的是 URL 字符串，而非文件本身。如果客户端想播放本地文件，需要先通过局域网共享（如 SMB、HTTP 文件服务）暴露一个可访问的地址，再将那个地址传给电视端。

**Android TV 权限**：Android TV 应用默认没有存储读写权限，但播放网络 URL 只需要 `INTERNET` 权限。如果未来要支持本地文件播放（如从 U 盘读取），则需要额外申请 `READ_EXTERNAL_STORAGE` 或 `MANAGE_EXTERNAL_STORAGE`。

---

整体实现路径可以概括为：**前台服务保活 → Ktor + SSE 暴露 MCP Server → `addTool` 注册 `play_media` → ExoPlayer 接收 URL 并播放**。这套架构的开源参考项目包括 [android-mcp-server](https://github.com/alguojian/android-mcp-server)（SSE 传输的 Android MCP Server 示例）和 [mcp-on-android-tv](https://github.com/MiddlePoint-Solutions/mcp-on-android-tv)（直接跑在 Android TV 上的 MCP Server，使用 Kotlin SDK 0.7.2 和 SSE 传输）。


用户：
mcp tool 要设定什么？我觉的smb sftp之类的都可以提供，就是不知道能不能播放不卡顿


AI：
你提到的 SMB、SFTP 这些协议，确实都可以通过 MCP 工具来提供，但播放是否卡顿，核心不在于协议本身，而在于**如何将协议集成进播放器**。直接播放和通过本地代理中转，效果天差地别。

### 🛠️ MCP Tool 设计：暴露哪些能力

基于你的场景，我建议设计三个核心工具，让客户端（AI Agent）能像操作本地文件一样操作网络共享：

**1. `list_directory`：浏览网络文件**
让 AI Agent 能列出 SMB/SFTP 共享中的文件，这是后续一切操作的前提。你需要传入 `protocol`（smb/sftp）、`host`、`path` 等参数。MCP 的 `smb-mcp` 项目就提供了类似的 `smb_list_directory` 工具。

**2. `play_media`：触发播放**
这是核心工具。关键设计决策是：**Tool 的参数是网络文件路径（如 `smb://...`），而不是文件内容本身**。你可以让 AI Agent 传入 `protocol`、`host`、`file_path` 等，由电视端 APK 内部的逻辑去处理连接和播放。

**3. `control_playback`：播放控制**
提供 `pause`、`resume`、`stop`、`seek` 等操作，让 AI Agent 能控制播放进度。

### ⚡ 播放性能：为什么“卡顿”是核心挑战

你担心的卡顿，根源在于 **ExoPlayer 原生并不直接支持 SMB/SFTP 协议**。ExoPlayer 的标准数据源只处理 HTTP、本地文件等，要播放网络共享文件，必须通过自定义 `DataSource` 来“桥接”。

这里有两种典型的实现路径，性能差异巨大：

| 方案 | 实现方式 | 性能表现 | 原因 |
| :--- | :--- | :--- | :--- |
| **直接集成** | 在 ExoPlayer 中实现一个自定义 `DataSource`，直接通过 SMB/SFTP 库读取数据。 | 容易卡顿，尤其拖动进度条时。 | SMB/SFTP 是**块级文件访问协议**，ExoPlayer 的随机读取（seek）会转化为大量低效的网络请求。 |
| **本地 HTTP 代理** | 在电视端启动一个轻量级 HTTP 服务器。ExoPlayer 播放 `http://localhost:xxxx/video.mp4`，代理再将 HTTP 的 `Range` 请求转换为对 SMB/SFTP 的精准字节读取。 | **流畅，可实现“零缓冲”秒播和秒拖拽**。 | HTTP 是流式协议，ExoPlayer 能高效处理。代理层解决了协议转换和请求优化问题。 |

**行业实践也印证了这一点**：Kodi 默认对 SMB/NFS 等局域网协议**不启用缓存**，导致“即读即播”，这是很多用户感觉卡顿的原因。而解决卡顿的成熟方案，正是上述的**本地代理方案**，它能让播放器对本地代理发起 HTTP 请求，由代理去精准拉取网络共享上的字节。

### 📊 协议选择：SMB、SFTP 还是 NFS？

从性能角度看，不同协议在 Android TV 上的表现有差异：

*   **SMB**：最通用，兼容性最好。SMB v3 在局域网内速度可观（40-100+ MB/s），足以应对大多数 4K 播放。但 SMB 在 Android 上的实现（如 jcifs-ng）有时会有兼容性问题，导致播放失败或中断。
*   **SFTP**：安全性好，但速度通常比 SMB 慢。由于加密开销，SFTP 的吞吐量可能只有 SMB 的一半左右。对于高码率视频，这可能成为瓶颈。
*   **NFS**：在 Linux/Android 平台上性能通常最佳，CPU 负载更低，速度更快。如果你追求极致的 4K 播放性能，NFS 是值得考虑的选项。

### 💎 总结与建议

所以，**技术上完全可以为 SMB、SFTP 等协议开发 MCP 工具，但关键在于播放器的集成方式**。

我的核心建议是：**不要尝试在 ExoPlayer 中直接实现 SMB/SFTP 的 `DataSource`，而是采用“本地 HTTP 代理”的架构。**

具体来说：
1.  **在电视端 APK 中内置一个轻量级 HTTP 服务器**。
2.  **MCP Tool (`play_media`) 接收到网络文件路径后**，APK 启动代理，并生成一个 `http://127.0.0.1:port/...` 的本地地址。
3.  **将这个本地 HTTP 地址交给 ExoPlayer 播放**。ExoPlayer 会以最高效的方式处理这个流。
4.  **代理层负责将 HTTP Range 请求翻译成对 SMB/SFTP 的精准字节读取**。

这样，你既能通过 MCP 灵活地支持 SMB、SFTP 等多种协议，又能确保电视端的播放体验流畅不卡顿。至于具体选择哪种协议，可以优先考虑 **SMB v3**（兼顾通用性和速度），如果对 4K 高码率有极致要求，再评估 **NFS** 方案。


用户：
技术上的可行性： 能不能开发一个apk，给电视调用，然后开放mcp-server，让客户端给他共享文件地址，让电视播放？


AI：
自动播放下一集和播放记录是让这个方案从“能播”进化到“好用”的关键。以下是这两个功能的技术实现思路。

---

### 🛠️ MCP Tool 设计：新增哪些能力

为了支持自动播放和播放记录，需要在原有的 `play_media` 和 `control_playback` 基础上，扩展以下工具：

**1. `play_series`：以剧集队列形式播放**

这是实现自动连播的核心。客户端（AI Agent）不再只传单个文件 URL，而是传入一个**剧集列表**和起始集数。

```kotlin
server.addTool(
    name = "play_series",
    description = "按顺序播放电视剧集，支持自动连播下一集。",
    inputSchema = ToolSchema(
        properties = buildJsonObject {
            put("episodes", buildJsonObject {
                put("type", "array")
                put("description", "剧集列表，每项包含 media_url 和 title")
                put("items", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("media_url", buildJsonObject { put("type", "string") })
                        put("title", buildJsonObject { put("type", "string") })
                    })
                })
            })
            put("start_index", buildJsonObject {
                put("type", "integer")
                put("description", "从第几集开始播放（从0开始）")
            })
        },
        required = listOf("episodes")
    )
) { request ->
    // 解析剧集列表，构建 ExoPlayer 播放队列
    // 具体实现见下文
}
```

**2. `get_playback_history`：查询播放记录**

让 AI Agent 能读取历史，回答“上次看到哪了”这类问题。

```kotlin
server.addTool(
    name = "get_playback_history",
    description = "获取播放历史记录，可按剧集名称筛选。",
    inputSchema = ToolSchema(
        properties = buildJsonObject {
            put("series_title", buildJsonObject {
                put("type", "string")
                put("description", "可选，按剧集名称筛选")
            })
        }
    )
) { request ->
    val history = playbackHistoryDao.getAll()  // Room 查询
    CallToolResult(content = listOf(TextContent(formatHistory(history))))
}
```

**3. `resume_playback`：从上次位置继续播放**

```kotlin
server.addTool(
    name = "resume_playback",
    description = "从上次观看位置继续播放指定剧集。",
    inputSchema = ToolSchema(
        properties = buildJsonObject {
            put("series_title", buildJsonObject { put("type", "string") })
            put("episode_index", buildJsonObject { put("type", "integer") })
        },
        required = listOf("series_title")
    )
) { request ->
    val record = playbackHistoryDao.findLatestBySeries(title)
    mediaPlayerManager.resumeFrom(record.mediaUrl, record.positionMs)
}
```

这样，AI Agent 的调用链就完整了：**`get_playback_history` 查询 → `resume_playback` 续播 → 播放结束后自动触发下一集**。


### ▶️ 自动播放下一集：ExoPlayer 的实现

ExoPlayer 原生支持播放队列，这是实现自动连播最优雅的方式。

#### 方案一：使用 `setMediaItems` 构建播放列表（推荐）

ExoPlayer 的 `Player` 接口支持直接传入**媒体项列表**，播放器会自动按顺序播放，当前一项结束时无缝切换到下一项。

```kotlin
fun playSeries(episodes: List<Episode>, startIndex: Int = 0) {
    val mediaItems = episodes.map { ep ->
        MediaItem.Builder()
            .setUri(ep.mediaUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(ep.title)
                    .build()
            )
            .build()
    }

    player.setMediaItems(mediaItems, startIndex, 0L)
    player.prepare()
    player.play()
}
```

`setMediaItems(mediaItems, startIndex, 0L)` 的第三个参数是起始播放位置（毫秒），设为 `0L` 表示从剧集开头播放。播放器会自动处理相邻剧集之间的缓冲和切换，实现无缝连播。

#### 方案二：监听 `STATE_ENDED` 手动切换

如果需要更精细的控制（比如在片尾曲时提前切换，或弹出“下一集”提示），可以监听播放器状态。

```kotlin
player.addListener(object : Player.Listener {
    override fun onPlaybackStateChanged(state: Int) {
        if (state == Player.STATE_ENDED) {
            // 当前剧集播放完毕
            val nextIndex = player.currentMediaItemIndex + 1
            if (nextIndex < player.mediaItemCount) {
                player.seekTo(nextIndex, 0L)
                player.play()
            } else {
                // 已是最后一集，停止播放
                onSeriesFinished()
            }
        }
    }
})
```

**注意**：社区中曾报告 `STATE_ENDED` 可能触发两次的问题，原因是监听器被重复添加。务必在添加监听器前先 `removeListener`，或使用 `addListener` 的幂等封装，确保只注册一次。

#### 进阶：提前预加载下一集

为了消除集与集之间的缓冲等待，可以在当前剧集播放到**剩余30秒**时，提前预取下一集的数据。Mythos 的 TV 播放器实现中，`TvPlaybackSession` 会在当前剧集播放期间就预取下一集的详细信息和继续观看列表，确保下一集画面在切换时**从第一帧开始就能立即渲染**，而不是等待网络往返。

```kotlin
player.addListener(object : Player.Listener {
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        // 监听播放位置变化，接近结尾时预加载
        val remaining = player.duration - player.currentPosition
        if (remaining in 0..30_000 && !isPreloadingNext) {
            isPreloadingNext = true
            preloadNextEpisode()
        }
    }
})
```


### 💾 播放记录持久化：Room 数据库

播放记录的存储需要记录：**剧集标识、媒体 URL、播放位置、总时长、最后观看时间、是否已看完**。

#### 数据实体定义

```kotlin
@Entity(tableName = "playback_history")
data class PlaybackRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seriesTitle: String,           // 剧集名称，如"权力的游戏"
    val episodeIndex: Int,             // 集数索引
    val episodeTitle: String,          // 单集标题
    val mediaUrl: String,              // 媒体地址
    val positionMs: Long,              // 当前播放位置（毫秒）
    val durationMs: Long,              // 总时长
    val lastWatchedAt: Long,           // 最后观看时间戳
    val isCompleted: Boolean = false   // 是否已看完
)
```

#### DAO 接口

```kotlin
@Dao
interface PlaybackHistoryDao {
    @Query("SELECT * FROM playback_history WHERE seriesTitle = :title ORDER BY episodeIndex DESC LIMIT 1")
    suspend fun findLatestBySeries(title: String): PlaybackRecord?

    @Query("SELECT * FROM playback_history ORDER BY lastWatchedAt DESC")
    suspend fun getAll(): List<PlaybackRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: PlaybackRecord)
}
```

Room 是 Android Jetpack 的 SQLite 抽象层，提供编译时查询验证和类型安全的实体映射，非常适合播放历史这类结构化数据的持久化。NewPipe 等成熟的开源项目就是使用 Room 数据库来管理观看历史的。

#### 何时保存记录

| 触发时机 | 保存内容 |
| :--- | :--- |
| `onPause()` / `onStop()` | 保存当前播放位置，用于续播 |
| `STATE_ENDED` | 标记 `isCompleted = true`，同时记录下一集为待播放 |
| 用户手动切换剧集 | 保存上一集进度，开始记录新一集 |
| 定时器（如每30秒） | 增量保存，防止崩溃丢失进度 |

```kotlin
override fun onPause() {
    super.onPause()
    val position = player.currentPosition
    val duration = player.duration
    // 异步写入 Room
    lifecycleScope.launch {
        playbackHistoryDao.upsert(
            PlaybackRecord(
                seriesTitle = currentSeries,
                episodeIndex = currentEpisodeIndex,
                episodeTitle = currentEpisodeTitle,
                mediaUrl = currentMediaUrl,
                positionMs = position,
                durationMs = duration,
                lastWatchedAt = System.currentTimeMillis()
            )
        )
    }
}
```

#### 恢复播放

当用户再次打开应用或通过 MCP 调用 `resume_playback` 时，从 Room 读取记录并定位。

```kotlin
fun resumeFrom(record: PlaybackRecord) {
    val mediaItem = MediaItem.Builder()
        .setUri(record.mediaUrl)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(record.episodeTitle)
                .build()
        )
        .build()

    player.setMediaItem(mediaItem)
    player.prepare()
    player.seekTo(record.positionMs)  // 精准定位到上次位置
    player.play()
}
```

ExoPlayer 通过 `player.getCurrentPosition()` 获取当前播放位置，用 `player.seekTo()` 精准定位，这是实现续播的标准做法。


### 📺 与 Android TV “接下来观看” 集成（可选进阶）

如果希望在 Android TV 的系统级“接下来观看”频道中展示你的剧集，可以使用 **Watch Next API**。

根据 Google 的官方指南，当用户观看剧集超过 **2分钟** 时，即可将该剧集添加到“接下来观看”频道；当用户**看完一集**且服务中有下一集时，使用 `WATCH_NEXT_TYPE_NEXT` 将下一集添加到频道中。

```kotlin
val watchNextProgram = WatchNextProgram.Builder()
    .setType(TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE)
    .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT)
    .setTitle(episodeTitle)
    .setDescription(episodeDescription)
    .setPosterArtUri(posterUri)
    .setIntentUri(deepLinkToEpisode)
    .build()

contentResolver.insert(
    TvContractCompat.WatchNextPrograms.CONTENT_URI,
    watchNextProgram.toContentValues()
)
```

当用户从系统频道点击你的剧集时，会通过 deep link 跳回你的应用并自动播放。这个功能需要应用通过 Google 的认证流程才能正式发布。


### 💎 总结

整体实现路径可以概括为：

1. **MCP Tool 层**：新增 `play_series`（批量播放）、`get_playback_history`（查询历史）、`resume_playback`（续播）三个工具。
2. **ExoPlayer 层**：用 `setMediaItems` 构建播放队列实现自动连播，通过 `Player.Listener` 的 `STATE_ENDED` 回调处理剧集切换，必要时预加载下一集消除缓冲。
3. **持久化层**：用 Room 数据库存储播放记录，在 `onPause()` 和 `STATE_ENDED` 时写入，通过 `seekTo` 恢复进度。
4. **系统集成层**（可选）：通过 Watch Next API 将剧集发布到 Android TV 的系统“接下来观看”频道。

这样，你的电视端 APK 就不仅是一个“能播 URL 的播放器”，而是一个具备**剧集管理、断点续播、自动连播**能力的完整媒体中心了。

用户：
还有跳过片头 片尾什么的
AI：
加上“跳过片头片尾”后，这个方案就补齐了最后一块核心体验。实现这个功能，ExoPlayer 提供了不止一种路径，但**最符合流媒体交互习惯、也最灵活的方式，是“监听 + 手动跳过”**，而不是在播放前就把视频“剪”掉。

### 🛠️ MCP Tool 的参数扩展

不需要新增工具，扩展已有的 `play_media` 或 `play_series` 即可。增加 `intro_end_ms`（片头结束时间）和 `outro_start_ms`（片尾开始时间）两个可选参数。

```kotlin
// 在 inputSchema 的 properties 中增加
put("intro_end_ms", buildJsonObject {
    put("type", "integer")
    put("description", "片头结束时间点（毫秒），用于跳过片头")
})
put("outro_start_ms", buildJsonObject {
    put("type", "integer")
    put("description", "片尾开始时间点（毫秒），用于跳过片尾")
})
```

这样，客户端（AI Agent）在发送播放指令时，就能一并把该集的片头片尾信息告诉电视端。

### ▶️ ExoPlayer 层的两种实现模式

#### 模式一：Netflix 式“跳过”按钮（推荐）

这种方式不修改媒体本身，只在特定时间点触发 UI 事件，让用户或 AI Agent 决定是否跳过。核心是利用 ExoPlayer 的 `PlayerMessage` 在指定播放位置发送消息。

```kotlin
fun setupSkipMarkers(player: ExoPlayer, introEndMs: Long, outroStartMs: Long) {
    // 片头结束前，发送消息以显示“跳过片头”按钮
    player.createMessage { _, _ ->
        // 通知 UI 层：现在可以显示“跳过片头”按钮了
        onSkipIntroAvailable { player.seekTo(introEndMs) }
    }.setPosition(introEndMs).setHandler(Handler(Looper.getMainLooper())).send()

    // 片尾开始前，发送消息以显示“下一集”/“跳过片尾”按钮
    player.createMessage { _, _ ->
        onSkipOutroAvailable { 
            // 可以 seek 到结尾触发 STATE_ENDED，或直接切下一集
            player.seekTo(player.duration) 
        }
    }.setPosition(outroStartMs).setHandler(Handler(Looper.getMainLooper())).send()
}
```

**关键点**：`PlayerMessage` 只在播放位置**实际经过**设定点时触发。如果用户手动拖拽进度条越过了片头结束点，按钮就不会出现，这符合预期。你还需要监听 `Player.Listener` 的 `onPositionDiscontinuity`，在用户手动 seek 越过 `introEndMs` 时，主动移除“跳过片头”按钮。

#### 模式二：硬裁剪（ClippingMediaSource / ClippingConfiguration）

如果你希望**完全移除**片头片尾，让视频“看起来”就是正片，可以使用 `ClippingMediaSource` 或 `MediaItem.ClippingConfiguration`。它可以在 `MediaSource` 层面将一个媒体源裁剪到指定区间。

```kotlin
// 使用 ClippingMediaSource 的示例（旧版 API，逻辑清晰）
val videoSource = ExtractorMediaSource.Factory(...).createMediaSource(videoUri)
val clippedSource = ClippingMediaSource(
    videoSource,
    introEndMs * 1000,          // startPositionUs
    outroStartMs * 1000          // endPositionUs
)
```

**为什么不首选这种方式**：裁剪是**静态**的，用户无法选择“这次我想看片头”。而且，对于 HLS 等流媒体，裁剪起点如果不是关键帧，可能会引发初始播放异常或兼容性问题。它更适合片头片尾完全固定的归档场景，而不是灵活的剧集播放。

### 📊 片头片尾时间戳从哪里来？

工具和播放器就绪后，数据源是关键。有三条路径，按可靠性从高到低排列：

**1. 手动配置 / 剧集元数据（最可靠）**
对于你通过 SMB/SFTP 管理的本地剧集，可以在 MCP 客户端侧维护一个简单的 JSON 或数据库，记录每部剧的片头片尾时间。AI Agent 在调用 `play_series` 时，自动为每一集带上这些参数。这是**零误差**的方案。

**2. 播放器内手动标记（渐进式）**
在电视 UI 上提供“标记片头结束”和“标记片尾开始”的按钮。用户观看时点一下，应用将时间戳与该集关联并持久化到 Room 数据库。下次播放同一集或同系列剧集时，自动应用。

**3. 自动检测（最省力，但有误差）**
可以利用 FFmpeg 的分析能力，在媒体入库时预先检测。常用信号包括：
- **黑屏检测**（`blackdetect`）：片头片尾常有黑场过渡。
- **静音检测**（`silencedetect`）：结合黑屏可以定位无声的片头字幕段。
- **场景切换**（`select='gt(scene,0.4)'`）：检测画面内容的剧烈变化点。

将这些信号的时间戳取交集或进行逻辑判断，可以估算出片头片尾的大致范围。`senzu_player` 这个 Flutter 插件就明确列出了对“Chapters & skip OP/ED”的支持，说明该模式在播放器层面是成熟可行的。但自动检测**无法保证 100% 准确**，建议作为“推荐时间”提供给用户确认，而非静默强制执行。

### 🔗 与剧集队列的整合

在上一轮设计的 `play_series` 中，播放队列是由多个 `MediaItem` 组成的。`PlayerMessage` 的方案天然兼容播放列表：你可以在 `onMediaItemTransition` 回调中，为**当前刚切换到的这一集**重新设置片头片尾的监听消息。

```kotlin
player.addListener(object : Player.Listener {
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val index = player.currentMediaItemIndex
        val episode = episodes[index]
        // 为这一集重新布置跳过监听
        setupSkipMarkers(player, episode.introEndMs, episode.outroStartMs)
    }
})
```

这样，无论自动连播到第几集，跳过片头片尾的逻辑都会正确跟随。

### 💎 总结

跳过片头片尾的实现优先级建议如下：

1. **MCP 层**：在 `play_media` / `play_series` 的 Schema 中加入 `intro_end_ms` 和 `outro_start_ms` 参数。
2. **播放器层**：**首选 `PlayerMessage` 的“监听 + 手动跳过”模式**，它灵活、符合直觉，且兼容播放列表；硬裁剪（`ClippingMediaSource`）仅作为固定片源的特例备选。
3. **数据层**：优先使用**手动配置或标记**的精确时间戳；自动检测（FFmpeg 黑屏/静音/场景分析）可作为辅助推荐，但不建议作为唯一来源。
4. **队列整合**：在 `onMediaItemTransition` 中为每一集重新绑定跳过监听，确保连播时功能不丢失。

用户：
还有音轨 调节的，
AI：
在之前的架构上叠加音轨控制，技术上同样是可行的，而且 ExoPlayer 为此提供了比较清晰的 API。这里可以把“音轨调节”拆成两个层面：**切换音轨**（比如从国语切到英语）和**调节音量**。

### 🛠️ MCP Tool 设计：拆成两个工具

建议保持工具职责单一，分别设计两个工具。

**1. `list_audio_tracks`：查询可用音轨**

在切换之前，客户端（AI Agent）需要知道当前媒体文件里有哪些音轨，比如语言、声道数、编码格式等。

```kotlin
server.addTool(
    name = "list_audio_tracks",
    description = "列出当前播放媒体中所有可用的音轨信息（语言、编码、是否被选中）。",
    inputSchema = ToolSchema(properties = buildJsonObject {}) // 无需参数
) { _ ->
    val tracksInfo = mediaPlayerManager.getAudioTracks()
    CallToolResult(content = listOf(TextContent(formatTracks(tracksInfo))))
}
```

**2. `set_audio_track`：切换音轨**

这是核心工具。参数可以设计得灵活一些，既支持**按语言标签**选择，也支持**按轨道索引**选择。

```kotlin
server.addTool(
    name = "set_audio_track",
    description = "切换当前媒体的音轨，可按语言或轨道索引选择。",
    inputSchema = ToolSchema(
        properties = buildJsonObject {
            put("language", buildJsonObject {
                put("type", "string")
                put("description", "音轨语言标签（如 'zh', 'en', 'ja'）")
            })
            put("track_index", buildJsonObject {
                put("type", "integer")
                put("description", "音轨在列表中的索引（从0开始），优先级高于 language")
            })
        }
    )
) { request ->
    val language = request.arguments?.get("language")?.jsonPrimitive?.content
    val trackIndex = request.arguments?.get("track_index")?.jsonPrimitive?.intOrNull
    mediaPlayerManager.selectAudioTrack(language, trackIndex)
    CallToolResult(content = listOf(TextContent("音轨切换指令已发送")))
}
```

如果同时传了 `track_index` 和 `language`，以 `track_index` 为准。这样 AI Agent 可以先调用 `list_audio_tracks` 拿到精确索引，避免语言标签匹配失败的问题。

**3. 音量调节可以并入已有的 `playback_control`**

音量调节本质上是播放控制的一部分，不需要单独开工具。在之前 `playback_control` 的 `action` 枚举里增加 `set_volume`，再补一个 `volume` 参数即可。

```kotlin
// 在 playback_control 的 schema 中增加
put("volume", buildJsonObject {
    put("type", "number")
    put("description", "音量值，0.0 为静音，1.0 为最大音量")
})
```

处理时：

```kotlin
"set_volume" -> {
    val vol = request.arguments?.get("volume")?.jsonPrimitive?.floatOrNull ?: 1f
    mediaPlayerManager.setVolume(vol.coerceIn(0f, 1f))
    "音量已设置为 $vol"
}
```

### 📋 ExoPlayer 层：音轨枚举与切换

ExoPlayer 的音轨管理围绕 `TrackSelectionParameters` 和 `DefaultTrackSelector` 展开，但**直接操作索引需要一些额外处理**，因为 `setPreferredAudioLanguage` 是按语言“偏好”选择，而不是按绝对索引。

#### 枚举音轨

通过 `player.currentTracks`（Media3）或 `player.getCurrentTracksInfo()`（旧版）可以拿到所有轨道分组。

```kotlin
fun getAudioTracks(): List<AudioTrackInfo> {
    val tracks = player.currentTracks
    val result = mutableListOf<AudioTrackInfo>()
    var globalIndex = 0

    for (group in tracks.groups) {
        if (group.type != C.TRACK_TYPE_AUDIO) continue
        for (i in 0 until group.length) {
            val format = group.getTrackFormat(i)
            result.add(
                AudioTrackInfo(
                    index = globalIndex++,
                    language = format.language ?: "und",
                    label = format.label ?: format.language ?: "Track $globalIndex",
                    isSelected = group.isTrackSelected(i)
                )
            )
        }
    }
    return result
}
```

同一个 `TrackGroup` 里的多个音轨通常代表**同一种语言的不同码率**，ExoPlayer 会进行自适应选择。对于“切换语言”的场景，真正需要区分的是**不同的 TrackGroup**，但对外暴露时，把每个 group 的首个 track 作为一个可切换项会更直观。

#### 按语言切换

这是 ExoPlayer 官方推荐的方式，利用 `setPreferredAudioLanguage` 让选择器自动匹配。

```kotlin
fun selectAudioTrackByLanguage(language: String) {
    val params = player.trackSelectionParameters
        .buildUpon()
        .setPreferredAudioLanguage(language)
        .build()
    player.trackSelectionParameters = params
}
```

这段代码的逻辑是：基于当前参数构建新参数，设置首选音频语言，然后重新应用到播放器。语言标签需使用 **IETF BCP 47** 格式（如 `"zh"`、`"en"`、`"zh-Hans"`）。

#### 按索引切换（需要手动映射）

`TrackSelectionParameters` 没有“直接选第 N 个音轨”的 API。如果客户端坚持按索引切换，需要**自己维护索引到 `TrackGroup` 的映射**，然后通过 `DefaultTrackSelector` 的 `setSelectionOverride` 强制选中特定 group。

```kotlin
fun selectAudioTrackByIndex(index: Int) {
    val selector = player.trackSelector as? DefaultTrackSelector ?: return
    val groups = player.currentTracks.groups
    val audioGroups = groups.filter { it.type == C.TRACK_TYPE_AUDIO }

    if (index !in audioGroups.indices) return
    val targetGroup = audioGroups[index].mediaTrackGroup

    val override = DefaultTrackSelector.SelectionOverride(targetGroup, 0)
    selector.setSelectionOverride(C.TRACK_TYPE_AUDIO, audioGroups, override)

    // 应用参数变更
    player.trackSelectionParameters = selector.buildUponParameters().build()
}
```

这种方式更“硬”，会**固定**选中指定的音轨，不再随语言偏好自动变化，适合“精确指定”的场景。

### 🔊 音量调节：用 `player.volume` 即可

ExoPlayer 的 `Player` 接口直接暴露了 `volume` 属性，取值 `0.0f`（静音）到 `1.0f`（原始增益）。

```kotlin
fun setVolume(volume: Float) {
    player.volume = volume.coerceIn(0f, 1f)
}
```

这是应用层的**软件音量**，与设备系统音量独立。如果需要在电视上弹出系统音量条，可以改用 `AudioManager` 调节 `STREAM_MUSIC`，但通常播放器内部调节 `player.volume` 更灵活，也不会干扰其他应用的声音。社区中也有通过 `MediaCodecAudioRenderer.MSG_SET_VOLUME` 消息在渲染线程精确控制音量的方式，但 `player.volume` 已经足够覆盖绝大多数场景。

### 📺 与剧集队列的整合

音轨和音量需要跟随**当前播放的剧集**变化。在 `onMediaItemTransition` 回调中，可以重置音轨偏好和音量策略。

```kotlin
player.addListener(object : Player.Listener {
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // 切换集数后，可以恢复默认音轨偏好（如首选中文）
        selectAudioTrackByLanguage("zh")
        // 音量通常保持不变，但如果每集有独立音量元数据，可以在这里应用
    }

    override fun onTracksInfoChanged(tracksInfo: TracksInfo) {
        // 轨道信息变化时，可以通知 MCP 客户端更新可用音轨列表
        // 例如通过 SSE 推送事件，让 AI Agent 知道新一集有哪些音轨
    }
})
```

`onTracksInfoChanged` 是一个有用的钩子：当 ExoPlayer 完成媒体准备、可用轨道发生变化时，它会触发。如果你的 MCP Server 支持服务端主动推送，可以在这里把最新的音轨列表推给客户端，而不是等客户端轮询 `list_audio_tracks`。

### 💎 总结

音轨控制可以拆解为三层：

1. **MCP 工具层**：新增 `list_audio_tracks`（枚举）和 `set_audio_track`（切换，支持语言或索引），音量调节并入 `playback_control`。
2. **ExoPlayer 层**：语言切换用 `setPreferredAudioLanguage`（官方推荐、自适应友好）；索引切换用 `DefaultTrackSelector.setSelectionOverride`（精确但硬性）；音量直接用 `player.volume`。
3. **生命周期整合**：在 `onMediaItemTransition` 中重置音轨策略，在 `onTracksInfoChanged` 中可选的向客户端推送音轨变化事件。

这样，AI Agent 就能完成完整的音轨操作链：**`list_audio_tracks` 查看 → `set_audio_track` 切换 → 音量通过 `playback_control` 调节**，且一切都在 ExoPlayer 原生能力范围内，没有额外的兼容性风险。