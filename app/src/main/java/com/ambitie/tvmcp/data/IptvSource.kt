package com.ambitie.tvmcp.data

import com.ambitie.tvmcp.mcp.booleanValue
import com.ambitie.tvmcp.mcp.longValue
import com.ambitie.tvmcp.mcp.stringValue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class IptvSource(
    val id: String,
    val name: String,
    val url: String,
    val invalid: Boolean = false,
    val lastError: String? = null,
    val failedAt: Long = 0L
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("url", url)
        put("invalid", invalid)
        if (lastError != null) put("lastError", lastError)
        put("failedAt", failedAt)
    }

    fun publicJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("url", url)
        put("invalid", invalid)
        if (lastError != null) put("lastError", lastError)
        put("failedAt", failedAt)
    }

    companion object {
        fun fromJson(json: JsonObject): IptvSource? {
            val id = json.stringValue("id") ?: return null
            val name = json.stringValue("name") ?: return null
            val url = json.stringValue("url") ?: return null
            return IptvSource(
                id = id,
                name = name,
                url = url,
                invalid = json.booleanValue("invalid") ?: false,
                lastError = json.stringValue("lastError"),
                failedAt = json.longValue("failedAt") ?: 0L
            )
        }
    }
}
