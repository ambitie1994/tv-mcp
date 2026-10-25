package com.ambitie.tvmcp.data

import android.content.Context
import android.graphics.Color
import android.util.Base64
import java.security.SecureRandom

data class SubtitleStyle(
    val fontScale: Float = 1.0f,
    val textColor: Int = Color.WHITE,
    val backgroundColor: Int = Color.TRANSPARENT,
    val bottomFraction: Float = 0.08f
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun token(): String {
        val existing = prefs.getString(KEY_TOKEN, null)
        if (!existing.isNullOrBlank()) return existing
        val token = newToken()
        prefs.edit().putString(KEY_TOKEN, token).apply()
        return token
    }

    fun rotateToken(): String {
        val token = newToken()
        prefs.edit().putString(KEY_TOKEN, token).apply()
        return token
    }

    fun subtitleStyle(): SubtitleStyle = SubtitleStyle(
        fontScale = prefs.getFloat(KEY_SUBTITLE_SCALE, 1.0f),
        textColor = prefs.getInt(KEY_SUBTITLE_TEXT_COLOR, Color.WHITE),
        backgroundColor = prefs.getInt(KEY_SUBTITLE_BACKGROUND, Color.TRANSPARENT),
        bottomFraction = prefs.getFloat(KEY_SUBTITLE_BOTTOM, 0.08f)
    )

    fun setSubtitleStyle(style: SubtitleStyle) {
        prefs.edit()
            .putFloat(KEY_SUBTITLE_SCALE, style.fontScale)
            .putInt(KEY_SUBTITLE_TEXT_COLOR, style.textColor)
            .putInt(KEY_SUBTITLE_BACKGROUND, style.backgroundColor)
            .putFloat(KEY_SUBTITLE_BOTTOM, style.bottomFraction)
            .apply()
    }

    private fun newToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    companion object {
        private const val PREFS = "tv_mcp_settings"
        private const val KEY_TOKEN = "mcp_token"
        private const val KEY_SUBTITLE_SCALE = "subtitle_font_scale"
        private const val KEY_SUBTITLE_TEXT_COLOR = "subtitle_text_color"
        private const val KEY_SUBTITLE_BACKGROUND = "subtitle_background"
        private const val KEY_SUBTITLE_BOTTOM = "subtitle_bottom"
    }
}
