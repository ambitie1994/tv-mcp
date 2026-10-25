package com.ambitie.tvmcp.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.ambitie.tvmcp.data.SmbSource
import com.ambitie.tvmcp.data.SourceStore
import com.ambitie.tvmcp.mcp.ToolException
import com.ambitie.tvmcp.mcp.booleanValue
import com.ambitie.tvmcp.mcp.longValue
import com.ambitie.tvmcp.mcp.stringValue
import com.ambitie.tvmcp.smb.SmbPath
import kotlinx.serialization.json.JsonObject

data class ResolvedMedia(
    val item: MediaItem,
    val title: String,
    val key: String,
    val startMs: Long,
    val sourceLabel: String? = null
)

data class CurrentMedia(
    val key: String,
    val title: String,
    val sourceId: String?,
    val path: String?,
    val url: String?
)

object MediaSpec {
    private const val EXTRA_KEY = "tvmcp.historyKey"
    private const val EXTRA_SOURCE_ID = "tvmcp.sourceId"
    private const val EXTRA_PATH = "tvmcp.path"
    private const val EXTRA_URL = "tvmcp.url"
    private const val EXTRA_INTRO_MS = "tvmcp.introEndMs"
    private const val EXTRA_OUTRO_MS = "tvmcp.outroStartMs"
    private const val EXTRA_AUTO_INTRO = "tvmcp.autoSkipIntro"
    private const val EXTRA_AUTO_OUTRO = "tvmcp.autoSkipOutro"
    private const val EXTRA_SUBTITLE_LABEL = "tvmcp.subtitleLabel"
    private const val EXTRA_SUBTITLE_LANGUAGE = "tvmcp.subtitleLanguage"

    fun single(
        sources: SourceStore,
        history: PlaybackHistory,
        args: JsonObject,
        allowResume: Boolean
    ): ResolvedMedia {
        val resolved = build(sources, args)
        val explicitStart = args.longValue("start_ms")?.also {
            if (it < 0) throw ToolException("start_ms 不能为负数")
        }
        var startMs = explicitStart ?: 0L

        if (explicitStart == null && allowResume && (args.booleanValue("resume") ?: true)) {
            val saved = history.find(resolved.key)
            if (saved != null && !saved.completed && saved.positionMs > 5_000) {
                val nearEndWindow = if (saved.durationMs > 0) {
                    minOf(10_000L, saved.durationMs / 4)
                } else {
                    0L
                }
                val nearEnd = nearEndWindow > 0 && saved.positionMs > saved.durationMs - nearEndWindow
                if (!nearEnd) startMs = saved.positionMs
            }
        }
        return resolved.copy(startMs = startMs)
    }

    fun queueItem(sources: SourceStore, args: JsonObject): ResolvedMedia = build(sources, args)

    fun currentMedia(item: MediaItem?): CurrentMedia? {
        if (item == null) return null
        val extras = item.mediaMetadata.extras ?: return null
        val key = extras.getString(EXTRA_KEY) ?: item.mediaId.takeIf { it.isNotBlank() } ?: return null
        return CurrentMedia(
            key = key,
            title = item.mediaMetadata.title?.toString().orEmpty().ifBlank { "未命名" },
            sourceId = extras.getString(EXTRA_SOURCE_ID),
            path = extras.getString(EXTRA_PATH),
            url = extras.getString(EXTRA_URL)
        )
    }

    fun introEndMs(item: MediaItem?): Long =
        item?.mediaMetadata?.extras?.getLong(EXTRA_INTRO_MS, -1L) ?: -1L

    fun outroStartMs(item: MediaItem?): Long =
        item?.mediaMetadata?.extras?.getLong(EXTRA_OUTRO_MS, -1L) ?: -1L

    fun autoSkipIntro(item: MediaItem?): Boolean =
        item?.mediaMetadata?.extras?.getBoolean(EXTRA_AUTO_INTRO, true) ?: true

    fun autoSkipOutro(item: MediaItem?): Boolean =
        item?.mediaMetadata?.extras?.getBoolean(EXTRA_AUTO_OUTRO, true) ?: true

    fun subtitleLabel(item: MediaItem?): String? =
        item?.mediaMetadata?.extras?.getString(EXTRA_SUBTITLE_LABEL)

    fun subtitleLanguage(item: MediaItem?): String? =
        item?.mediaMetadata?.extras?.getString(EXTRA_SUBTITLE_LANGUAGE)

    private fun build(sources: SourceStore, args: JsonObject): ResolvedMedia {
        val url = args.stringValue("url")
        val sourceId = args.stringValue("source_id")
        val rawPath = args.stringValue("path")

        if (url != null && rawPath != null) {
            throw ToolException("url 和 path 只能二选一")
        }
        if (url == null && rawPath == null) {
            throw ToolException("需要提供 url，或 source_id + path")
        }

        val mimeType = args.stringValue("mime_type")
        val titleArg = args.stringValue("title")
        val subtitlePathArg = args.stringValue("subtitle_path")
        val subtitleUrlArg = args.stringValue("subtitle_url")
        if (subtitlePathArg != null && subtitleUrlArg != null) {
            throw ToolException("subtitle_path 和 subtitle_url 只能二选一")
        }
        val subtitleLanguage = args.stringValue("subtitle_language")
        val subtitleLabel = subtitlePathArg?.substringAfterLast('/')
            ?: subtitleUrlArg?.substringAfterLast('/')?.substringBefore('?')
        val introMs = args.longValue("intro_end_ms")?.coerceAtLeast(0L) ?: -1L
        val outroMs = args.longValue("outro_start_ms")?.coerceAtLeast(0L) ?: -1L
        if (introMs > 0 && outroMs > 0 && introMs >= outroMs) {
            throw ToolException("intro_end_ms 必须小于 outro_start_ms")
        }
        val autoIntro = args.booleanValue("auto_skip_intro") ?: true
        val autoOutro = args.booleanValue("auto_skip_outro") ?: true

        val uri: Uri
        val key: String
        val defaultTitle: String
        val source: SmbSource?
        val path: String?
        val httpUrl: String?

        if (url != null) {
            uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                throw ToolException("url 仅支持 http/https")
            }
            if (uri.host.isNullOrBlank()) throw ToolException("url 无效：$url")
            key = "url:$url"
            defaultTitle = url.substringAfterLast('/').substringBefore('?').ifBlank { url }
            source = null
            path = null
            httpUrl = url
        } else {
            source = resolveSource(sources, sourceId)
            val normalized = try {
                SmbPath.normalize(rawPath)
            } catch (e: IllegalArgumentException) {
                throw ToolException(e.message ?: "路径无效")
            }
            if (normalized.isEmpty()) throw ToolException("path 不能为空")
            uri = Uri.parse(SmbPath.smbUri(source.id, normalized))
            key = "smb:${source.id}:$normalized"
            defaultTitle = normalized.substringAfterLast('/')
            path = normalized
            httpUrl = null
        }

        val subtitleConfig = when {
            subtitlePathArg != null -> {
                if (source == null) throw ToolException("subtitle_path 仅支持 SMB 媒体")
                val subPath = try {
                    SmbPath.normalize(subtitlePathArg)
                } catch (e: IllegalArgumentException) {
                    throw ToolException("字幕路径无效：${e.message}")
                }
                if (subPath.isEmpty()) throw ToolException("subtitle_path 不能为空")
                val subMime = SubtitleFiles.mimeType(subPath)
                    ?: throw ToolException("不支持的字幕格式：${subPath.substringAfterLast('.', "")}")
                MediaItem.SubtitleConfiguration.Builder(
                    Uri.parse(SmbPath.smbUri(source.id, subPath))
                )
                    .setMimeType(subMime)
                    .setLanguage(subtitleLanguage)
                    .setLabel(subtitleLabel)
                    .build()
            }
            subtitleUrlArg != null -> {
                val subUri = Uri.parse(subtitleUrlArg)
                val scheme = subUri.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") {
                    throw ToolException("subtitle_url 仅支持 http/https")
                }
                val name = subtitleLabel ?: "字幕"
                val subMime = SubtitleFiles.mimeType(name)
                    ?: throw ToolException("不支持的字幕格式：${name.substringAfterLast('.', "")}")
                MediaItem.SubtitleConfiguration.Builder(subUri)
                    .setMimeType(subMime)
                    .setLanguage(subtitleLanguage)
                    .setLabel(name)
                    .build()
            }
            else -> null
        }

        val title = (titleArg ?: defaultTitle).trim().ifBlank { "未命名" }.take(300)
        val extras = Bundle().apply {
            putString(EXTRA_KEY, key)
            putString(EXTRA_SOURCE_ID, source?.id)
            putString(EXTRA_PATH, path)
            putString(EXTRA_URL, httpUrl)
            putLong(EXTRA_INTRO_MS, introMs)
            putLong(EXTRA_OUTRO_MS, outroMs)
            putBoolean(EXTRA_AUTO_INTRO, autoIntro)
            putBoolean(EXTRA_AUTO_OUTRO, autoOutro)
            subtitleLabel?.let { putString(EXTRA_SUBTITLE_LABEL, it) }
            subtitleLanguage?.let { putString(EXTRA_SUBTITLE_LANGUAGE, it) }
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setExtras(extras)
            .build()
        val builder = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(key)
            .setMediaMetadata(metadata)
        if (mimeType != null) builder.setMimeType(mimeType)
        if (subtitleConfig != null) builder.setSubtitleConfigurations(listOf(subtitleConfig))

        return ResolvedMedia(
            item = builder.build(),
            title = title,
            key = key,
            startMs = 0,
            sourceLabel = source?.label()
        )
    }

    private fun resolveSource(sources: SourceStore, sourceId: String?): SmbSource {
        if (sourceId != null) {
            return sources.get(sourceId) ?: throw ToolException("未找到片源：$sourceId")
        }
        val all = sources.all()
        if (all.isEmpty()) throw ToolException("电视端还没有配置 SMB 片源")
        if (all.size > 1) {
            val list = all.joinToString("；") { "${it.id}=${it.name}" }
            throw ToolException("有多个片源，请指定 source_id（$list）")
        }
        return all.first()
    }
}
