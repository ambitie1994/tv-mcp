package com.ambitie.tvmcp.mcp

import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

class McpProtocol(
    private val serverName: String,
    private val serverVersion: String,
    private val instructions: String,
    tools: List<McpTool>,
    private val log: (String) -> Unit = {}
) {
    private val toolMap = tools.associateBy { it.name }
    private val tools = tools.toList()

    suspend fun handle(message: JsonObject): JsonObject? {
        val id = message["id"]
        val hasId = id != null && id !is JsonNull
        val method = message.stringValue("method")

        if (message.stringValue("jsonrpc") != "2.0" || method == null) {
            return if (hasId) error(id, -32600, "Invalid Request") else null
        }
        if (!hasId) {
            // notifications/initialized, notifications/cancelled, progress updates...
            return null
        }

        return try {
            when (method) {
                "initialize" -> success(id, initialize(message.objectValue("params") ?: JsonObject(emptyMap())))
                "ping" -> success(id, buildJsonObject { })
                "tools/list" -> success(id, listTools())
                "tools/call" -> callTool(id, message.objectValue("params") ?: JsonObject(emptyMap()))
                else -> error(id, -32601, "Method not found: $method")
            }
        } catch (e: Exception) {
            log("request failed: ${e.stackTraceToString()}")
            error(id, -32603, "Internal error")
        }
    }

    private fun initialize(params: JsonObject): JsonObject {
        val requested = params.stringValue("protocolVersion")
        val version = if (requested != null && requested in SUPPORTED_VERSIONS) {
            requested
        } else {
            SUPPORTED_VERSIONS.last()
        }
        return buildJsonObject {
            put("protocolVersion", version)
            put(
                "capabilities",
                buildJsonObject {
                    put("tools", buildJsonObject { put("listChanged", false) })
                }
            )
            put(
                "serverInfo",
                buildJsonObject {
                    put("name", serverName)
                    put("version", serverVersion)
                }
            )
            put("instructions", instructions)
        }
    }

    private fun listTools(): JsonObject = buildJsonObject {
        put(
            "tools",
            buildJsonArray {
                tools.forEach { tool ->
                    add(
                        buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("inputSchema", tool.inputSchema)
                        }
                    )
                }
            }
        )
    }

    private suspend fun callTool(id: JsonElement, params: JsonObject): JsonObject {
        val name = params.stringValue("name")
            ?: return error(id, -32602, "Missing tool name")
        val tool = toolMap[name]
            ?: return error(id, -32602, "Unknown tool: $name")
        val arguments = params.objectValue("arguments") ?: JsonObject(emptyMap())

        val result = try {
            tool.handler(arguments)
        } catch (e: ToolException) {
            ToolResult(e.message ?: "参数错误", isError = true)
        } catch (e: Exception) {
            log("tool '$name' failed: ${e.stackTraceToString()}")
            ToolResult("工具执行失败：${e.message ?: e.javaClass.simpleName}", isError = true)
        }
        return success(id, result.toJson())
    }

    private fun success(id: JsonElement, result: JsonElement): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    private fun error(id: JsonElement?, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
            }
        )
    }

    companion object {
        val SUPPORTED_VERSIONS = listOf("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25")
    }
}
