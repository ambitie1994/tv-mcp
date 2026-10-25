package com.ambitie.tvmcp.data

import android.content.Context
import com.ambitie.tvmcp.mcp.booleanValue
import com.ambitie.tvmcp.mcp.longValue
import com.ambitie.tvmcp.mcp.stringValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class AiListItem(
    val title: String,
    val url: String? = null,
    val sourceId: String? = null,
    val path: String? = null,
    val group: String? = null,
    val invalid: Boolean = false,
    val lastError: String? = null,
    val failedAt: Long = 0L
) {
    fun key(): String? = when {
        !url.isNullOrBlank() -> "url:$url"
        !sourceId.isNullOrBlank() && !path.isNullOrBlank() -> "smb:$sourceId:$path"
        else -> null
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("title", title)
        if (url != null) put("url", url)
        if (sourceId != null) put("source_id", sourceId)
        if (path != null) put("path", path)
        if (group != null) put("group", group)
        put("invalid", invalid)
        if (lastError != null) put("lastError", lastError)
        put("failedAt", failedAt)
    }

    companion object {
        fun fromJson(json: JsonObject): AiListItem? {
            val title = json.stringValue("title") ?: return null
            return AiListItem(
                title = title,
                url = json.stringValue("url"),
                sourceId = json.stringValue("source_id"),
                path = json.stringValue("path"),
                group = json.stringValue("group"),
                invalid = json.booleanValue("invalid") ?: false,
                lastError = json.stringValue("lastError"),
                failedAt = json.longValue("failedAt") ?: 0L
            )
        }
    }
}

class AiListStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    fun name(): String = synchronized(lock) { prefs.getString(KEY_NAME, null).orEmpty().ifBlank { DEFAULT_NAME } }

    fun items(): List<AiListItem> = synchronized(lock) { readItems() }

    fun replace(name: String, items: List<AiListItem>) = synchronized(lock) {
        val previous = readItems().associateBy { it.key() }
        val merged = items.take(MAX_ITEMS).map { item ->
            val old = previous[item.key()]
            if (old == null) item else item.copy(
                invalid = old.invalid,
                lastError = old.lastError,
                failedAt = old.failedAt
            )
        }
        prefs.edit()
            .putString(KEY_NAME, name.ifBlank { DEFAULT_NAME })
            .putString(KEY_ITEMS, buildJsonArray { merged.forEach { add(it.toJson()) } }.toString())
            .apply()
    }

    fun markInvalidByKey(key: String, error: String): Boolean = synchronized(lock) {
        val list = readItems().toMutableList()
        val index = list.indexOfFirst { it.key() == key }
        if (index < 0) return false
        list[index] = list[index].copy(invalid = true, lastError = error, failedAt = System.currentTimeMillis())
        prefs.edit().putString(KEY_ITEMS, buildJsonArray { list.forEach { add(it.toJson()) } }.toString()).apply()
        true
    }

    fun removeInvalid(): Int = synchronized(lock) {
        val list = readItems()
        val kept = list.filterNot { it.invalid }
        val removed = list.size - kept.size
        if (removed > 0) {
            prefs.edit().putString(KEY_ITEMS, buildJsonArray { kept.forEach { add(it.toJson()) } }.toString()).apply()
        }
        removed
    }

    fun invalidCount(): Int = synchronized(lock) { readItems().count { it.invalid } }

    fun clear() = synchronized(lock) {
        prefs.edit().remove(KEY_NAME).remove(KEY_ITEMS).apply()
    }

    private fun readItems(): List<AiListItem> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            val array = Json.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
            array.mapNotNull { (it as? JsonObject)?.let(AiListItem::fromJson) }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val PREFS = "tv_mcp_ai_list"
        private const val KEY_NAME = "name"
        private const val KEY_ITEMS = "items"
        private const val DEFAULT_NAME = "AI 列表"
        private const val MAX_ITEMS = 200
    }
}
