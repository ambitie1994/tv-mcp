# TV MCP

[English](README.md) | [简体中文](README.zh-CN.md)

TV MCP 把一个 Android TV（或带触摸屏的 Android 设备）变成一个由 MCP 客户端控制的媒体播放器。应用内嵌 Streamable HTTP MCP Server 和 ExoPlayer，AI 助手或任何支持 MCP 的客户端都可以浏览 SMB 共享、播放 HTTP/HLS 流、管理 M3U IPTV 频道、控制播放和字幕。

电视本身也能独立使用：界面以遥控器为主，触屏设备有按需出现的 HUD 和右下角的小菜单入口。

## 功能

- **应用内 MCP Server**：`POST /mcp`，默认端口 `8765`（被占用时尝试 `8766`–`8769`），Bearer Token 鉴权。
- **21 个 MCP 工具**：覆盖 SMB 片源、IPTV、播放、历史和字幕。
- **SMB2/SMB3**：基于 [smbj](https://github.com/hierynomus/smbj)，带自定义 ExoPlayer `DataSource`（`tvsmb://`）。支持 Guest 和用户名/密码共享。
- **HTTP/HTTPS、HLS、DASH**：使用 AndroidX Media3 / ExoPlayer 播放。
- **M3U/M3U8 IPTV**：添加播放列表、缓存频道、按名称/分组筛选、按频道名或序号播放。
- **播放队列**、历史续播、片头/片尾自动跳过、跳转、音量、上一集/下一集。
- **字幕**：内嵌字幕轨，以及 SMB/HTTP 上的 SRT/VTT/ASS/SSA 外挂字幕；支持字号、颜色、背景和垂直位置。
- **遥控器优先界面**：侧边菜单；触屏设备有按需 HUD 和小浮窗菜单。
- **配对二维码**和手机友好的 `/setup` 页面，用来添加 SMB/IPTV 片源。
- **前台服务**：通知栏控制，可选开机自启。

## 环境要求

- Android 8.1（API 27）或更新版本；使用 `compileSdk`/`targetSdk` 36 构建。
- 已在小米小爱触屏音箱 LX04（800×480，Android 8.1）上测试。主要设计目标是带遥控器的 Android TV；纯触屏设备可以通过屏幕菜单和 HUD 操作。
- JDK 17、Android SDK 36、Gradle 9.7.1（wrapper 会处理 Gradle）。
- 设备需要和 NAS 或 IPTV 源在同一个局域网。

## 构建

```bash
git clone https://github.com/ambitie1994/tv-mcp.git
cd tv-mcp

# 如果 Android SDK 没有被自动识别：
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

说明：

- `local.properties` 已被 gitignore，不要提交。
- Gradle wrapper 默认使用腾讯镜像，方便中国大陆下载。如果想用官方源，把 `gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl` 改成 `https://services.gradle.org/distributions/gradle-9.7.1-bin.zip`。目前没有固定 SHA-256 校验值。
- 本仓库构建的是 debug APK；正式发布请自己配置签名。

## 首次使用

1. 打开应用。它会启动前台 MCP 服务并显示首页/菜单。
2. 如果希望 MCP 发起播放时把播放器带到前台，请授予**显示在其他应用上层**权限。
3. 打开**菜单 → 连接 AI**，扫描二维码，或复制地址：
   `http://<电视IP>:8765/mcp?token=<token>`
4. 通过电视界面、`/setup` 网页（配对页第二个二维码）或 MCP 工具添加片源。
5. 如果 Token 泄露，在配对页重新生成。

## 连接 MCP 客户端

- **传输方式**：Streamable HTTP，端点 `POST /mcp`。
- **鉴权**：`Authorization: Bearer <token>` 或 `?token=<token>`。
- **会话**：无状态；服务端不返回 `Mcp-Session-Id`。
- **方法**：`initialize`、`ping`、`tools/list`、`tools/call`；通知返回 HTTP `202`。
- **SSE**：`GET /mcp` 返回 `405`（不提供长连接事件流）。带 `Accept: text/event-stream` 的 `POST` 会返回单条 SSE 事件。
- **协议版本**：`2024-11-05`、`2025-03-26`、`2025-06-18`、`2025-11-25`。

客户端配置示例（具体字段以你的 MCP 客户端为准）：

```json
{
  "mcpServers": {
    "tv": {
      "type": "streamable-http",
      "url": "http://192.168.1.50:8765/mcp?token=YOUR_TOKEN"
    }
  }
}
```

## MCP 工具（21 个）

| 分组 | 工具 |
| --- | --- |
| SMB 片源 | `list_sources`、`save_smb_source`、`delete_smb_source`、`test_smb_source`、`browse` |
| IPTV | `list_iptv_sources`、`save_iptv_source`、`delete_iptv_source`、`test_iptv_source`、`list_channels`、`play_channel` |
| 播放 | `play`、`play_queue`、`control`、`status`、`show_list` |
| 历史 | `get_history`、`resume_history` |
| 字幕 | `list_subtitles`、`set_subtitle`、`set_subtitle_style` |

### 典型调用链

- **播放 SMB 文件**：`list_sources` → `browse` → `play {source_id, path}`。

  `path` 必须使用 `browse` 返回的完整相对路径（例如 `Shows/Episode 01.mp4`），不能只传文件名。

- **继续观看**：`resume_history {title: "..."}`。

  它会使用历史里保存的 `source_id`/`path`；不要自己拼 SMB 路径。

- **IPTV**：`save_iptv_source {name, url}` → `test_iptv_source` → `list_channels` → `play_channel {name|index}`。
- **字幕**：`list_subtitles` → `set_subtitle {track_index|path|url}`，再用 `set_subtitle_style` 调整样式。
- **首页列表**：`show_list {items}` 会把列表放到电视首页，方便遥控器选择。条目可以是 `url`，也可以是 `source_id` + `path`。

### 给 AI 客户端的建议

- `play` 返回时只代表已经 `prepare()`，不代表文件已经完全打开；异步失败请用 `status` 查看 `error`。
- SMB 的 `path` 是相对共享根目录的路径，目录名也要包含在内。
- SMB 用户名为空时按 **Guest** 登录，不是匿名/null session。
- 续播优先用 `resume_history`，播放频道优先用 `play_channel`，不要让模型自己拼路径或复制 URL。

## 安全模型

这个应用是为**可信局域网**设计的。

- **不要把 8765 端口暴露到公网。**
- 静态 Bearer Token 是唯一的鉴权方式。它包含在配对 URL/二维码里，请当作密码对待；泄露后请重新生成。
- 为了访问局域网片源，HTTP 默认是明文（`usesCleartextTraffic="true"`），CORS 允许任意来源，方便浏览器型 MCP 客户端连接。
- SMB 密码以明文保存在应用私有 SharedPreferences 中。建议使用专门的只读 NAS 账号。
- `/setup` 页面同样由 Token 保护，但走的是 HTTP。
- 不使用任何统计、遥测或云服务；应用只连接你配置的片源。

## 已知限制

- 不支持音轨切换（之前的音轨工具已被有意移除）。
- IPTV 只支持 M3U/M3U8；没有 EPG、回看或 DRM。
- 只支持 SMB2/SMB3；不支持 SMB1、SFTP、NFS。
- Android 15+ 限制了从 `BOOT_COMPLETED` 启动 `mediaPlayback` 前台服务，开机自启可能不生效。
- 解码能力取决于设备；低端硬件可能无法播放 4K/高码率文件。
- 每个应用只有一个播放器实例；支持队列，但不支持多房间同步。
- 本仓库默认构建 debug APK。正式签名包见下面的 **Release signing**。

### Release signing（正式签名）

如果没有提供 keystore，release 包是未签名的。通过环境变量提供：

```bash
export TVMCP_KEYSTORE=/absolute/path/to/tvmcp-release.jks
export TVMCP_STORE_PASSWORD='...'
export TVMCP_KEY_ALIAS=tvmcp
export TVMCP_KEY_PASSWORD='...'
./gradlew :app:assembleRelease
```

keystore 和密码文件必须备份在仓库之外。丢失后将无法给已安装正式包的用户做覆盖升级。不要把 keystore 或密码提交到仓库。
- IPTV 内容由用户提供，本项目不托管、不提供任何直播源。

## 目录结构

```text
app/src/main/java/com/ambitie/tvmcp/
├── core/        应用级核心：存储、播放器、HTTP Server
├── mcp/         手写 JSON-RPC MCP + Ktor Streamable HTTP Server
├── tools/       MCP 工具（SMB、IPTV、播放、字幕、历史）
├── smb/         smbj 连接池 + tvsmb:// ExoPlayer DataSource
├── iptv/        M3U 加载与解析
├── playback/    ExoPlayer 封装、媒体解析、历史、字幕
├── data/        SharedPreferences 存储（片源、IPTV、频道、AI 列表、设置）
├── service/     前台服务 + 开机广播
└── ui/          代码构建的遥控器/触屏界面
```

早期设计讨论在 [docs/design-discussion.md](docs/design-discussion.md)，属于历史记录，可能和当前实现不一致。

## 测试

```bash
./gradlew :app:testDebugUnitTest
```

目前有 30 个 JVM 单元测试，覆盖 MCP 协议/HTTP 服务、SMB 路径安全、M3U 解析和字幕后缀。没有 instrumentation 测试。`lintDebug` 当前是 0 error（仍有 warning）。

## 许可证

MIT，见 [LICENSE](LICENSE)。

第三方许可说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 致谢

AndroidX、Media3/ExoPlayer、Ktor、kotlinx.coroutines、kotlinx.serialization、smbj、ZXing、SLF4J、Bouncy Castle。

## 免责声明

本软件按“原样”提供，不附带任何担保。你需要对自己播放的媒体负责，并遵守 IPTV/流媒体服务方的版权要求。
