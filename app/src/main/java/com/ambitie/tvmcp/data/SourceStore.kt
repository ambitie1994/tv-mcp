package com.ambitie.tvmcp.data

import android.content.Context
import kotlinx.serialization.json.add
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import java.util.UUID

class SourceStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    fun all(): List<SmbSource> = synchronized(lock) { readAll() }

    fun get(id: String): SmbSource? = synchronized(lock) {
        readAll().firstOrNull { it.id == id }
    }

    fun upsert(source: SmbSource) = synchronized(lock) {
        val list = readAll().toMutableList()
        val index = list.indexOfFirst { it.id == source.id }
        if (index >= 0) list[index] = source else list.add(source)
        writeAll(list)
    }

    fun delete(id: String) = synchronized(lock) {
        writeAll(readAll().filterNot { it.id == id })
    }

    fun newId(): String = synchronized(lock) {
        val existing = readAll().mapTo(HashSet()) { it.id }
        var id: String
        do {
            id = "s" + UUID.randomUUID().toString().replace("-", "").take(6)
        } while (id in existing)
        id
    }

    private fun readAll(): List<SmbSource> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = Json.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
            array.mapNotNull { (it as? JsonObject)?.let(SmbSource::fromJson) }
        }.getOrDefault(emptyList())
    }

    private fun writeAll(list: List<SmbSource>) {
        val array = buildJsonArray { list.forEach { add(it.toJson()) } }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    companion object {
        private const val PREFS = "tv_mcp_sources"
        private const val KEY = "sources"
    }
}
