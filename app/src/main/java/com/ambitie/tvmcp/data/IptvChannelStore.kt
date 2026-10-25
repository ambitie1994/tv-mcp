package com.ambitie.tvmcp.data

import android.content.Context
import com.ambitie.tvmcp.iptv.IptvChannel
import com.ambitie.tvmcp.mcp.booleanValue
import com.ambitie.tvmcp.mcp.longValue
import com.ambitie.tvmcp.mcp.stringValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class CachedChannel(
    val name: String,
    val url: String,
    val group: String? = null,
    val invalid: Boolean = false,
    val lastError: String? = null,
    val failedAt: Long = 0L
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("url", url)
        if (group != null) put("group", group)
        put("invalid", invalid)
        if (lastError != null) put("lastError", lastError)
        put("failedAt", failedAt)
    }

    companion object {
        fun fromJson(json: JsonObject): CachedChannel? {
            val name = json.stringValue("name") ?: return null
            val url = json.stringValue("url") ?: return null
            return CachedChannel(
                name = name,
                url = url,
                group = json.stringValue("group"),
                invalid = json.booleanValue("invalid") ?: false,
                lastError = json.stringValue("lastError"),
                failedAt = json.longValue("failedAt") ?: 0L
            )
        }
    }
}

class IptvChannelStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    fun get(sourceId: String): List<CachedChannel> = synchronized(lock) {
        readAll()[sourceId] ?: emptyList()
    }

    fun replace(sourceId: String, channels: List<IptvChannel>): List<CachedChannel> = synchronized(lock) {
        val all = readAll().toMutableMap()
        val old = (all[sourceId] ?: emptyList()).associateBy { it.url }
        val merged = channels.take(MAX_CHANNELS).map { channel ->
            val previous = old[channel.url]
            CachedChannel(
                name = channel.name,
                url = channel.url,
                group = channel.group,
                invalid = previous?.invalid ?: false,
                lastError = previous?.lastError,
                failedAt = previous?.failedAt ?: 0L
            )
        }
        all[sourceId] = merged
        writeAll(all)
        merged
    }

    fun markInvalid(sourceId: String, url: String, error: String): Boolean = synchronized(lock) {
        val all = readAll().toMutableMap()
        val list = all[sourceId]?.toMutableList() ?: return false
        val index = list.indexOfFirst { it.url == url }
        if (index < 0) return false
        list[index] = list[index].copy(invalid = true, lastError = error, failedAt = System.currentTimeMillis())
        all[sourceId] = list
        writeAll(all)
        true
    }

    fun removeInvalid(sourceId: String): Int = synchronized(lock) {
        val all = readAll().toMutableMap()
        val list = all[sourceId] ?: return 0
        val kept = list.filterNot { it.invalid }
        val removed = list.size - kept.size
        if (removed > 0) {
            all[sourceId] = kept
            writeAll(all)
        }
        removed
    }

    fun removeInvalidAll(): Int = synchronized(lock) {
        val all = readAll()
        var removed = 0
        val updated = all.mapValues { (_, list) ->
            val kept = list.filterNot { it.invalid }
            removed += list.size - kept.size
            kept
        }
        if (removed > 0) writeAll(updated)
        removed
    }

    fun removeSource(sourceId: String) = synchronized(lock) {
        val all = readAll().toMutableMap()
        if (all.remove(sourceId) != null) writeAll(all)
    }

    fun invalidCount(): Int = synchronized(lock) {
        readAll().values.sumOf { list -> list.count { it.invalid } }
    }

    private fun readAll(): Map<String, List<CachedChannel>> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        return runCatching {
            val root = Json.parseToJsonElement(raw) as? JsonObject ?: return emptyMap()
            root.mapNotNull { (sourceId, value) ->
                val array = value as? JsonArray ?: return@mapNotNull null
                sourceId to array.mapNotNull { (it as? JsonObject)?.let(CachedChannel::fromJson) }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun writeAll(all: Map<String, List<CachedChannel>>) {
        val root = buildJsonObject {
            all.forEach { (sourceId, list) ->
                put(sourceId, buildJsonArray { list.forEach { add(it.toJson()) } })
            }
        }
        prefs.edit().putString(KEY, root.toString()).apply()
    }

    companion object {
        private const val PREFS = "tv_mcp_channels"
        private const val KEY = "channels"
        private const val MAX_CHANNELS = 5000
    }
}
