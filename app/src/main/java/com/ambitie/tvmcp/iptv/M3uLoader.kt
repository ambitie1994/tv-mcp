package com.ambitie.tvmcp.iptv

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.GZIPInputStream

object M3uLoader {
    private const val MAX_BYTES = 4 * 1024 * 1024

    fun load(url: String): String {
        val uri = URI(PlaylistUrl.encode(url))
        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") { "电视源地址必须是 http/https" }

        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "TVMCP/1.0")
        connection.setRequestProperty("Accept-Encoding", "identity")
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("电视源请求失败：HTTP $code")
            val encoding = connection.contentEncoding.orEmpty()
            val stream = if (encoding.contains("gzip", ignoreCase = true)) {
                GZIPInputStream(connection.inputStream)
            } else {
                connection.inputStream
            }
            stream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (total < MAX_BYTES) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    total += read
                }
                return String(output.toByteArray(), Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }
}
