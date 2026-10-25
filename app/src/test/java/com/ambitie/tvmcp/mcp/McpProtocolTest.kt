package com.ambitie.tvmcp.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpProtocolTest {

    private fun protocol() = McpProtocol(
        serverName = "test-server",
        serverVersion = "1.0",
        instructions = "test instructions",
        tools = listOf(
            McpTool(
                name = "echo",
                description = "Echo text",
                inputSchema = objectSchema(
                    mapOf("text" to stringProperty("text to echo")),
                    listOf("text")
                )
            ) { args ->
                ToolResult(
                    text = "echo:${args.stringValue("text")}",
                    structured = Json.parseToJsonElement(
                        """{"echo":"${args.stringValue("text")}"}"""
                    ).jsonObject
                )
            },
            McpTool(
                name = "broken",
                description = "Always fails",
                inputSchema = objectSchema(emptyMap())
            ) {
                throw ToolException("bad input")
            }
        )
    )

    private fun request(json: String): JsonObject =
        Json.parseToJsonElement(json).jsonObject

    @Test
    fun `initialize negotiates supported protocol version`() = runBlocking {
        val response = protocol().handle(
            request(
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}"""
            )
        )
        assertNotNull(response)
        val result = response!!["result"]!!.jsonObject
        assertEquals("2025-06-18", result["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("test-server", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(result.containsKey("instructions"))
    }

    @Test
    fun `initialize falls back to latest version`() = runBlocking {
        val response = protocol().handle(
            request(
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}"""
            )
        )!!
        assertEquals(
            "2025-11-25",
            response["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `tools list contains schemas`() = runBlocking {
        val response = protocol().handle(
            request("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""")
        )!!
        val tools = response["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(2, tools.size)
        assertEquals("echo", tools[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(tools[0].jsonObject["inputSchema"]!!.jsonObject.containsKey("properties"))
    }

    @Test
    fun `tools call returns text and structured content`() = runBlocking {
        val response = protocol().handle(
            request(
                """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo","arguments":{"text":"hello"}}}"""
            )
        )!!
        val result = response["result"]!!.jsonObject
        assertFalse(result["isError"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(
            "echo:hello",
            result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        )
        assertEquals(
            "hello",
            result["structuredContent"]!!.jsonObject["echo"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `tool exception becomes isError result`() = runBlocking {
        val response = protocol().handle(
            request(
                """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"broken","arguments":{}}}"""
            )
        )!!
        val result = response["result"]!!.jsonObject
        assertTrue(result["isError"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(
            result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content.contains("bad input")
        )
    }

    @Test
    fun `unknown tool and method return json rpc errors`() = runBlocking {
        val unknownTool = protocol().handle(
            request(
                """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"nope","arguments":{}}}"""
            )
        )!!
        assertEquals(-32602, unknownTool["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)

        val unknownMethod = protocol().handle(
            request("""{"jsonrpc":"2.0","id":6,"method":"nope","params":{}}""")
        )!!
        assertEquals(-32601, unknownMethod["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    @Test
    fun `notification returns no response`() = runBlocking {
        val response = protocol().handle(
            request("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        )
        assertNull(response)
    }

    @Test
    fun `invalid jsonrpc version is rejected`() = runBlocking {
        val response = protocol().handle(
            request("""{"jsonrpc":"1.0","id":7,"method":"ping"}""")
        )!!
        assertEquals(-32600, response["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    @Test
    fun `ping returns empty result`() = runBlocking {
        val response = protocol().handle(
            request("""{"jsonrpc":"2.0","id":8,"method":"ping"}""")
        )!!
        assertTrue(response["result"]!!.jsonObject.isEmpty())
    }
}
