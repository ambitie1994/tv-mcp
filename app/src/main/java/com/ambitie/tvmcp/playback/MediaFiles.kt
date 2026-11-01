package com.ambitie.tvmcp.playback

import androidx.media3.common.MediaItem
import com.ambitie.tvmcp.data.SmbSource
import com.ambitie.tvmcp.data.SourceStore
import com.ambitie.tvmcp.mcp.ToolException
import com.ambitie.tvmcp.smb.SmbEntry
import com.ambitie.tvmcp.smb.SmbPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class PlayPlan(
    val items: List<ResolvedMedia>,
    val startIndex: Int,
    val startMs: Long,
    val note: String? = null
)

object MediaFiles {
    const val MAX_FOLDER_ITEMS = 100

    private val PLAYABLE_EXTENSIONS = setOf(
        "mp4", "m4v", "mkv", "mov", "avi", "wmv", "flv", "f4v", "webm",
        "mpg", "mpeg", "mpe", "ts", "m2ts", "mts", "vob", "ogv", "3gp", "3gpp",
        "rm", "rmvb", "divx", "asf",
        "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "ape"
    )

    fun isPlayable(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in PLAYABLE_EXTENSIONS

    fun playable(entries: List<SmbEntry>): List<SmbEntry> =
        entries.filter { !it.isDirectory && isPlayable(it.name) && !it.name.startsWith('.') && !it.name.startsWith('~') }
            .sortedWith { a, b -> naturalCompare(a.name, b.name) }

    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                val sa = a.substring(i, ei).trimStart('0')
                val sb = b.substring(j, ej).trimStart('0')
                if (sa.length != sb.length) return sa.length - sb.length
                val cmp = sa.compareTo(sb)
                if (cmp != 0) return cmp
                i = ei
                j = ej
            } else {
                val la = ca.lowercaseChar()
                val lb = cb.lowercaseChar()
                if (la != lb) return la.code - lb.code
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    suspend fun buildPlayPlan(
        sources: SourceStore,
        pool: SmbPool,
        args: JsonObject,
        media: ResolvedMedia,
        queueFolder: Boolean
    ): PlayPlan {
        val info = MediaSpec.currentMedia(media.item)
        val path = info?.path
        val source = info?.sourceId?.let { sources.get(it) }
        if (source == null || path == null) {
            return PlayPlan(listOf(media), 0, media.startMs)
        }
        val parent = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val parentEntries = listDir(pool, source, parent)

        if (!queueFolder) {
            requireFile(source, parent, name, parentEntries)
            return PlayPlan(listOf(media), 0, media.startMs)
        }

        if (parentEntries.any { it.isDirectory && it.name == name }) {
            val files = playable(listDir(pool, source, path))
            if (files.isEmpty()) {
                throw ToolException("文件夹 ${source.share}/$path 里没有可播放的媒体文件")
            }
            val items = files.take(MAX_FOLDER_ITEMS).map { queueMedia(sources, source, it) }
            val truncated = if (files.size > items.size) "，取前 ${items.size} 个" else ""
            return PlayPlan(
                items = items,
                startIndex = 0,
                startMs = media.startMs,
                note = "已把文件夹内 ${files.size} 个媒体文件按文件名排序连播$truncated"
            )
        }

        requireFile(source, parent, name, parentEntries)

        val files = playable(parentEntries)
        val rawIndex = files.indexOfFirst { it.name == name }
        if (rawIndex < 0 || files.size < 2) {
            return PlayPlan(listOf(media), 0, media.startMs)
        }

        val start = if (files.size <= MAX_FOLDER_ITEMS) {
            0
        } else {
            (rawIndex - MAX_FOLDER_ITEMS / 2).coerceIn(0, files.size - MAX_FOLDER_ITEMS)
        }
        val queue = files.subList(start, minOf(start + MAX_FOLDER_ITEMS, files.size))
        val startIndex = rawIndex - start
        val items = queue.map { queueMedia(sources, source, it) }.toMutableList()
        items[startIndex] = media
        val truncated = if (files.size > queue.size) "（只排当前集附近 ${queue.size} 个）" else ""
        return PlayPlan(
            items = items,
            startIndex = startIndex,
            startMs = media.startMs,
            note = "已把 ${source.share}/$parent 内 ${files.size} 个媒体文件按文件名排序加入队列$truncated"
        )
    }

    suspend fun verifySmbFile(sources: SourceStore, pool: SmbPool, item: MediaItem?) {
        val info = MediaSpec.currentMedia(item) ?: return
        val path = info.path ?: return
        val source = info.sourceId?.let { sources.get(it) } ?: return
        val parent = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val entries = listDir(pool, source, parent)
        requireFile(source, parent, name, entries)
    }

    private suspend fun listDir(pool: SmbPool, source: SmbSource, dir: String): List<SmbEntry> =
        try {
            withContext(Dispatchers.IO) { pool.list(source, dir) }
        } catch (e: ToolException) {
            throw e
        } catch (e: Exception) {
            throw ToolException("无法读取 SMB 目录 ${source.share}/$dir：${e.message ?: "连接失败"}")
        }

    private fun requireFile(source: SmbSource, parent: String, name: String, entries: List<SmbEntry>) {
        if (entries.any { !it.isDirectory && it.name == name }) return
        val dirs = entries.filter { it.isDirectory }.take(6).joinToString("、") { it.name }
        val files = entries.filter { !it.isDirectory }.take(6).joinToString("、") { it.name }
        val hint = buildString {
            append("SMB 文件不存在：${source.share}/")
            if (parent.isNotEmpty()) append("$parent/")
            append(name)
            if (dirs.isNotEmpty()) append("；子目录：$dirs")
            if (files.isNotEmpty()) append("；文件：$files")
            append("。path 必须是相对共享根目录的完整路径（例如 目录/文件名），可用 browse 获取。")
        }
        throw ToolException(hint)
    }

    private fun queueMedia(sources: SourceStore, source: SmbSource, entry: SmbEntry): ResolvedMedia {
        val args = buildJsonObject {
            put("source_id", source.id)
            put("path", entry.path)
            put("title", entry.name)
        }
        return try {
            MediaSpec.queueItem(sources, args)
        } catch (e: ToolException) {
            throw ToolException("无法加入队列：${entry.name}（${e.message}）")
        }
    }
}
