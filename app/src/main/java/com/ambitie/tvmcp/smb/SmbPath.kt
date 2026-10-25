package com.ambitie.tvmcp.smb

import com.ambitie.tvmcp.data.SmbSource
import java.net.URLEncoder

object SmbPath {
    fun normalize(input: String?): String {
        val replaced = (input ?: "").replace('\\', '/').trim()
        require(!replaced.contains('\u0000')) { "路径包含非法字符" }
        val segments = replaced.split('/').filter { it.isNotEmpty() && it != "." }
        require(segments.none { it == ".." }) { "路径不能包含 .." }
        return segments.joinToString("/")
    }

    fun smbUri(sourceId: String, path: String): String {
        val normalized = normalize(path)
        if (normalized.isEmpty()) return "tvsmb://$sourceId/"
        return "tvsmb://$sourceId/" + normalized.split('/').joinToString("/") { encodeSegment(it) }
    }

    fun displayPath(source: SmbSource, path: String): String {
        val normalized = normalize(path)
        return "smb://${source.host}/${source.share}" + if (normalized.isEmpty()) "" else "/$normalized"
    }

    fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
}
