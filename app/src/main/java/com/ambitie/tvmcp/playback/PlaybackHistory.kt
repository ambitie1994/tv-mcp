package com.ambitie.tvmcp.playback

import android.content.Context
import com.ambitie.tvmcp.mcp.booleanValue
import com.ambitie.tvmcp.mcp.longValue
import com.ambitie.tvmcp.mcp.stringValue
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

data class HistoryEntry(
    val key: String,
    val title: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val completed: Boolean,
    val sourceId: String? = null,
    val path: String? = null,
    val url: String? = null
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("key", key)
        put("title", title)
        put("position_ms", positionMs)
        put("duration_ms", durationMs)
        put("updated_at", updatedAt)
        put("completed", completed)
        if (sourceId != null) put("source_id", sourceId)
        if (path != null) put("path", path)
        if (url != null) put("url", url)
    }

    companion object {
        fun fromJson(json: JsonObject): HistoryEntry? {
            val key = json.stringValue("key") ?: return null
            return HistoryEntry(
                key = key,
                title = json.stringValue("title").orEmpty(),
                positionMs = json.longValue("position_ms") ?: 0L,
                durationMs = json.longValue("duration_ms") ?: 0L,
                updatedAt = json.longValue("updated_at") ?: 0L,
                completed = json.booleanValue("completed") ?: false,
                sourceId = json.stringValue("source_id"),
                path = json.stringValue("path"),
                url = json.stringValue("url")
            )
        }
    }
}

class PlaybackHistory(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    fun find(key: String): HistoryEntry? = synchronized(lock) { readAll()[key] }

    fun save(entry: HistoryEntry) = synchronized(lock) {
        val all = readAll().toMutableMap()
        all[entry.key] = entry
        val trimmed = all.values
            .sortedByDescending { it.updatedAt }
            .take(MAX_ENTRIES)
            .associateBy { it.key }
        writeAll(trimmed)
    }

    fun recent(limit: Int): List<HistoryEntry> = synchronized(lock) {
        readAll().values.sortedByDescending { it.updatedAt }.take(limit.coerceIn(1, 50))
    }

    fun remove(key: String) = synchronized(lock) {
        val all = readAll()
        if (!all.containsKey(key)) return@synchronized
        writeAll(all - key)
    }

    fun clear() = synchronized(lock) {
        prefs.edit().remove(KEY).apply()
    }

    private fun readAll(): Map<String, HistoryEntry> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        return runCatching {
            val root = Json.parseToJsonElement(raw) as? JsonObject ?: return emptyMap()
            root.mapNotNull { (key, value) ->
                val entry = (value as? JsonObject)?.let(HistoryEntry::fromJson) ?: return@mapNotNull null
                key to entry
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun writeAll(entries: Map<String, HistoryEntry>) {
        val root = buildJsonObject { entries.forEach { (key, entry) -> put(key, entry.toJson()) } }
        prefs.edit().putString(KEY, root.toString()).apply()
    }

    companion object {
        private const val PREFS = "tv_mcp_history"
        private const val KEY = "entries"
        private const val MAX_ENTRIES = 200
    }
}
