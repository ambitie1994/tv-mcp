package com.ambitie.tvmcp.iptv

import java.net.URI

data class IptvChannel(
    val name: String,
    val url: String,
    val group: String? = null,
    val logo: String? = null
)

object M3uParser {
    private val attributeRegex = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
    private const val MAX_CHANNELS = 5000

    fun parse(content: String, playlistUrl: String): List<IptvChannel> {
        val text = content.removePrefix("\uFEFF")
        val hlsMedia = text.lineSequence().any {
            it.trimStart().startsWith("#EXT-X-TARGETDURATION", ignoreCase = true)
        }
        val result = mutableListOf<IptvChannel>()
        var pending: Pending? = null

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val duration = line.substringAfter('#', "").substringAfter(':', "")
                        .substringBefore(',').trim().toDoubleOrNull()
                    if (hlsMedia && duration != null && duration > 0) {
                        pending = null
                        return@forEach
                    }
                    val attributes = attributeRegex.findAll(line)
                        .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    pending = Pending(
                        name = displayName(line),
                        tvgName = attributes["tvg-name"],
                        group = attributes["group-title"],
                        logo = attributes["tvg-logo"]
                    )
                }
                line.startsWith("#EXTGRP", ignoreCase = true) -> {
                    val group = line.substringAfter(':', "").trim()
                    if (group.isNotEmpty()) pending?.group = group
                }
                line.startsWith("#") -> Unit
                else -> {
                    val info = pending ?: return@forEach
                    val url = resolve(playlistUrl, line) ?: return@forEach
                    val name = info.name.ifBlank { info.tvgName.orEmpty() }.ifBlank { url }
                    result += IptvChannel(
                        name = name,
                        url = url,
                        group = info.group?.ifBlank { null },
                        logo = info.logo?.ifBlank { null }
                    )
                    pending = null
                    if (result.size >= MAX_CHANNELS) return result
                }
            }
        }
        return result.distinctBy { it.name to it.url }
    }

    private fun displayName(line: String): String {
        var inQuotes = false
        var lastComma = -1
        line.forEachIndexed { index, char ->
            if (char == '"') inQuotes = !inQuotes
            if (char == ',' && !inQuotes) lastComma = index
        }
        return if (lastComma >= 0) line.substring(lastComma + 1).trim() else ""
    }

    private fun resolve(baseUrl: String, reference: String): String? {
        val cleaned = reference.substringBefore('|').trim()
        if (cleaned.isEmpty()) return null
        return runCatching {
            val resolved = URI(PlaylistUrl.encode(baseUrl)).resolve(PlaylistUrl.encode(cleaned))
            if (resolved.scheme != "http" && resolved.scheme != "https") null else resolved.toString()
        }.getOrNull()
    }

    private data class Pending(
        val name: String,
        val tvgName: String?,
        var group: String?,
        val logo: String?
    )
}
