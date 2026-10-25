package com.ambitie.tvmcp.iptv

internal object PlaylistUrl {
    private const val HEX = "0123456789ABCDEF"
    private const val URI_ASCII = "-._~:/?#[]@!$&'()*+,;="

    fun encode(raw: String): String {
        val text = raw.trim()
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val code = text.codePointAt(i)
            val count = Character.charCount(code)
            if (code == '%'.code && i + 2 < text.length &&
                isHex(text[i + 1]) && isHex(text[i + 2])
            ) {
                out.append(text, i, i + 3)
                i += 3
                continue
            }
            val safe = code in 'a'.code..'z'.code ||
                code in 'A'.code..'Z'.code ||
                code in '0'.code..'9'.code ||
                code < 0x80 && URI_ASCII.indexOf(code.toChar()) >= 0
            if (safe) {
                out.appendCodePoint(code)
            } else {
                val bytes = String(Character.toChars(code)).toByteArray(Charsets.UTF_8)
                for (byte in bytes) {
                    val value = byte.toInt() and 0xFF
                    out.append('%').append(HEX[value ushr 4]).append(HEX[value and 0x0F])
                }
            }
            i += count
        }
        return out.toString()
    }

    private fun isHex(char: Char): Boolean =
        char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'
}
