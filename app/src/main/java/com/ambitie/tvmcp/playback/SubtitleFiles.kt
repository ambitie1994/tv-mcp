package com.ambitie.tvmcp.playback

import com.ambitie.tvmcp.data.SmbSource
import com.ambitie.tvmcp.smb.SmbPath
import com.ambitie.tvmcp.smb.SmbPool

data class SubtitleFile(
    val sourceId: String,
    val name: String,
    val path: String,
    val mimeType: String
) {
    fun uri(): String = SmbPath.smbUri(sourceId, path)
}

object SubtitleFiles {
    private val MIME_TYPES = mapOf(
        "srt" to "application/x-subrip",
        "vtt" to "text/vtt",
        "ass" to "text/x-ssa",
        "ssa" to "text/x-ssa"
    )

    fun mimeType(name: String): String? =
        MIME_TYPES[name.substringAfterLast('.', "").lowercase()]

    fun isSubtitle(name: String): Boolean = mimeType(name) != null

    fun discover(pool: SmbPool, source: SmbSource, mediaPath: String, limit: Int = 20): List<SubtitleFile> {
        val directory = mediaPath.substringBeforeLast('/', "")
        val baseName = mediaPath.substringAfterLast('/').substringBeforeLast('.').lowercase()
        val entries = runCatching { pool.list(source, directory) }.getOrDefault(emptyList())
        val subtitles = entries.filter { !it.isDirectory && isSubtitle(it.name) }
        val matching = if (baseName.isEmpty()) {
            subtitles
        } else {
            subtitles.filter { it.name.substringBeforeLast('.').lowercase().startsWith(baseName) }
        }
        val selected = if (matching.isNotEmpty()) matching else subtitles
        return selected.take(limit).map { entry ->
            SubtitleFile(
                sourceId = source.id,
                name = entry.name,
                path = entry.path,
                mimeType = mimeType(entry.name)!!
            )
        }
    }
}
