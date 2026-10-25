package com.ambitie.tvmcp.data

import com.ambitie.tvmcp.mcp.booleanValue
import com.ambitie.tvmcp.mcp.longValue
import com.ambitie.tvmcp.mcp.stringValue
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

data class SmbSource(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 445,
    val share: String,
    val domain: String = "",
    val username: String = "",
    val password: String = "",
    val invalid: Boolean = false,
    val lastError: String? = null,
    val failedAt: Long = 0L
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("host", host)
        put("port", port)
        put("share", share)
        put("domain", domain)
        put("username", username)
        put("password", password)
        put("invalid", invalid)
        if (lastError != null) put("lastError", lastError)
        put("failedAt", failedAt)
    }

    fun publicJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("host", host)
        put("port", port)
        put("share", share)
        put("invalid", invalid)
        if (lastError != null) put("lastError", lastError)
        put("failedAt", failedAt)
    }

    fun label(): String = if (host.isBlank()) name else "$name ($host/$share)"

    companion object {
        fun fromJson(json: JsonObject): SmbSource? {
            val id = json.stringValue("id") ?: return null
            val name = json.stringValue("name") ?: return null
            val host = json.stringValue("host") ?: return null
            val share = json.stringValue("share") ?: return null
            val port = (json.longValue("port") ?: 445L).toInt().coerceIn(1, 65535)
            return SmbSource(
                id = id,
                name = name,
                host = host,
                port = port,
                share = share,
                domain = json.stringValue("domain").orEmpty(),
                username = json.stringValue("username").orEmpty(),
                password = json.stringValue("password").orEmpty(),
                invalid = json.booleanValue("invalid") ?: false,
                lastError = json.stringValue("lastError"),
                failedAt = json.longValue("failedAt") ?: 0L
            )
        }
    }
}
