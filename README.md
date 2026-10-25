# TV MCP

[English](README.md) | [简体中文](README.zh-CN.md)

TV MCP turns an Android TV — or a touchscreen Android device — into a media player controlled by an MCP (Model Context Protocol) client. It embeds a Streamable HTTP MCP server and ExoPlayer, so an AI assistant or any MCP-capable client can browse SMB shares, play HTTP/HLS streams, manage M3U IPTV channels, control playback, and manage subtitles.

The TV is still usable on its own: the UI is remote-first, with a touch-friendly on-demand HUD and a small floating menu entry for devices without a remote.

## Features

- **MCP server in the app**: Streamable HTTP endpoint at `POST /mcp`, port `8765` (falls back to `8766`–`8769`), bearer-token authentication.
- **21 MCP tools** for SMB sources, IPTV, playback, history and subtitles.
- **SMB2/SMB3** via [smbj](https://github.com/hierynomus/smbj), with a custom ExoPlayer `DataSource` (`tvsmb://`). Supports guest access and username/password shares.
- **HTTP/HTTPS, HLS and DASH** playback through AndroidX Media3 / ExoPlayer.
- **M3U/M3U8 IPTV**: add playlists, cache channels, filter by name/group, play by channel name or index.
- **Playback queue**, resume from history, intro/outro auto-skip, seek, volume, next/previous.
- **Subtitles**: embedded tracks and external SRT/VTT/ASS/SSA files from SMB or HTTP; font size, color, background and vertical position.
- **Remote-first UI** with a side menu, plus a touch-only floating menu and a tap-to-show HUD.
- **Pairing QR code** and a phone-friendly `/setup` page for adding SMB/IPTV sources.
- **Foreground service** with notification controls and an optional boot receiver.

## Requirements

- Android 8.1 (API 27) or newer. Built with `compileSdk`/`targetSdk` 36.
- Tested on a Xiaomi XiaoAI LX04 touchscreen speaker (800×480, Android 8.1). The primary design target is Android TV with a remote; touch-only devices work through the on-screen menu/HUD.
- JDK 17, Android SDK 36, Gradle 9.7.1 (the wrapper handles Gradle).
- A device on the same LAN as your NAS or IPTV provider.

## Build

```bash
git clone https://github.com/ambitie1994/tv-mcp.git
cd tv-mcp

# If your Android SDK is not auto-detected:
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Notes:

- `local.properties` is gitignored. Do not commit it.
- The Gradle wrapper points at the Tencent Gradle mirror for faster downloads in mainland China. If you prefer the official source, change `distributionUrl` in `gradle/wrapper/gradle-wrapper.properties` to `https://services.gradle.org/distributions/gradle-9.7.1-bin.zip`. No SHA-256 checksum is currently pinned.
- This repository builds a debug APK. Add your own signing configuration for a release build.

## First run

1. Open the app. It starts the foreground MCP service and shows the home screen/menu.
2. Grant **Display over other apps** if you want the player to come to the front when playback is started from MCP.
3. Open **Menu → Connect AI** and scan the QR code, or copy the URL:
   `http://<device-ip>:8765/mcp?token=<token>`
4. Add sources with the TV UI, the `/setup` page (the second QR code on the pairing screen), or MCP tools.
5. Rotate the token from the pairing screen if it has been exposed.

## Connect an MCP client

- **Transport**: Streamable HTTP, endpoint `POST /mcp`.
- **Authentication**: `Authorization: Bearer <token>` or `?token=<token>`.
- **Sessions**: stateless; the server does not issue `Mcp-Session-Id`.
- **Methods**: `initialize`, `ping`, `tools/list`, `tools/call`. Notifications return HTTP `202`.
- **SSE**: `GET /mcp` returns `405` (no long-lived event stream). A `POST` with `Accept: text/event-stream` returns a single SSE event.
- **Protocol versions**: `2024-11-05`, `2025-03-26`, `2025-06-18`, `2025-11-25`.

Example client configuration (syntax depends on your MCP client):

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

## MCP tools (21)

| Group | Tools |
| --- | --- |
| SMB sources | `list_sources`, `save_smb_source`, `delete_smb_source`, `test_smb_source`, `browse` |
| IPTV | `list_iptv_sources`, `save_iptv_source`, `delete_iptv_source`, `test_iptv_source`, `list_channels`, `play_channel` |
| Playback | `play`, `play_queue`, `control`, `status`, `show_list` |
| History | `get_history`, `resume_history` |
| Subtitles | `list_subtitles`, `set_subtitle`, `set_subtitle_style` |

### Typical flows

- **Play an SMB file**: `list_sources` → `browse` → `play {source_id, path}`.

  Use the exact `path` returned by `browse` (relative to the share root, for example `Shows/Episode 01.mp4`), not just the file name.

- **Continue watching**: `resume_history {title: "..."}`.

  It reads the stored `source_id`/`path` from history. Do not assemble SMB paths manually.

- **IPTV**: `save_iptv_source {name, url}` → `test_iptv_source` → `list_channels` → `play_channel {name|index}`.
- **Subtitles**: `list_subtitles` → `set_subtitle {track_index|path|url}` and `set_subtitle_style`.
- **Home list**: `show_list {items}` puts a list on the TV home screen for the remote. Items can be a `url` or a `source_id` + `path`.

### Tips for AI clients

- `play` returns after `prepare()`, not after the file has fully opened. Call `status` and check the `error` field for asynchronous failures.
- SMB `path` values are relative to the share root. Directories are part of the path.
- A blank SMB username means **Guest** login, not an anonymous/null session.
- Prefer `resume_history` over reconstructing paths, and `play_channel` over copying channel URLs.

## Security model

This app is designed for a **trusted local network**.

- **Never expose port 8765 to the internet.**
- The bearer token is the only authentication. It is embedded in the pairing URL/QR; treat it like a password and rotate it if leaked.
- HTTP is cleartext by design (`usesCleartextTraffic="true"`) for LAN sources, and CORS allows any origin so browser-based MCP clients can connect.
- SMB credentials are stored in the app's private SharedPreferences in plaintext. Use a dedicated, read-only NAS account.
- The `/setup` page is protected by the same token, but it is served over HTTP.
- No analytics, telemetry or cloud service is used. The app only connects to sources you configure.

## Known limitations

- Audio-track switching is not implemented (the earlier tool was intentionally removed).
- IPTV supports M3U/M3U8 only; there is no EPG, catch-up or DRM.
- SMB2/SMB3 only. SMB1, SFTP and NFS are not supported.
- Android 15+ restricts starting `mediaPlayback` foreground services from `BOOT_COMPLETED`; auto-start after boot may not work on those devices.
- Codec support depends on the device. 4K/high-bitrate files may not play on low-end hardware.
- One player instance per app; queues are supported, but there is no multi-room sync.
- This repository builds debug APKs; release signing is up to you.
- IPTV content is supplied by the user. The project does not host or provide streams.

## Project layout

```text
app/src/main/java/com/ambitie/tvmcp/
├── core/        TvMcpCore: app-scoped stores, player and HTTP server
├── mcp/         hand-written JSON-RPC MCP + Ktor Streamable HTTP server
├── tools/       MCP tools (SMB, IPTV, playback, subtitles, history)
├── smb/         smbj connection pool + tvsmb:// ExoPlayer DataSource
├── iptv/        M3U loader/parser
├── playback/    ExoPlayer wrapper, media specs, history, subtitles
├── data/        SharedPreferences stores (sources, IPTV, channels, AI list, settings)
├── service/     foreground service + boot receiver
└── ui/          code-built remote/touch UI
```

Early design notes are in [docs/design-discussion.md](docs/design-discussion.md). They are historical and may not match the current implementation.

## Testing

```bash
./gradlew :app:testDebugUnitTest
```

There are 30 JVM unit tests covering the MCP protocol/HTTP server, SMB path safety, M3U parsing and subtitle extensions. No instrumentation tests are included. `lintDebug` currently reports 0 errors (warnings remain).

## License

MIT. See [LICENSE](LICENSE).

Third-party notices are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Acknowledgements

AndroidX, Media3/ExoPlayer, Ktor, kotlinx.coroutines, kotlinx.serialization, smbj, ZXing, SLF4J and Bouncy Castle.

## Disclaimer

This software is provided as-is, without warranty. You are responsible for the media you play and for complying with the rights of any IPTV or stream provider.
