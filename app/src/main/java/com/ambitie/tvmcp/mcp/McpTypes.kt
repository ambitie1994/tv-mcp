package com.ambitie.tvmcp.mcp

import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val handler: suspend (JsonObject) -> ToolResult
)

data class ToolResult(
    val text: String,
    val structured: JsonObject? = null,
    val isError: Boolean = false
) {
    fun toJson(): JsonObject = buildJsonObject {
        put(
            "content",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    }
                )
            }
        )
        put("isError", isError)
        if (structured != null) put("structuredContent", structured)
    }
}

class ToolException(message: String) : Exception(message)
