package com.ambitie.tvmcp.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

class McpHttpServerTest {

    private lateinit var server: McpHttpServer
    private lateinit var protocol: McpProtocol

    private fun newProtocol() = McpProtocol(
        serverName = "http-test",
        serverVersion = "1.0",
        instructions = "test",
        tools = listOf(
            McpTool(
                name = "echo",
                description = "echo",
                inputSchema = objectSchema(mapOf("text" to stringProperty("text")))
            ) { args ->
                ToolResult("echo:${args.stringValue("text")}")
            }
        )
    )

    @Before
    fun setUp() {
        protocol = newProtocol()
        val freePort = ServerSocket(0).use { it.localPort }
        server = McpHttpServer(protocol, "secret", freePort, "127.0.0.1")
        assertTrue(server.start())
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private data class Response(val code: Int, val body: String, val contentType: String)

    private fun post(
        body: String,
        token: String? = "secret",
        accept: String = "application/json, text/event-stream",
        queryToken: String? = null
    ): Response {
        val url = URL(
            "http://127.0.0.1:${server.port}/mcp" + if (queryToken != null) "?token=$queryToken" else ""
        )
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", accept)
        if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val contentType = connection.contentType.orEmpty()
        connection.disconnect()
        return Response(code, text, contentType)
    }

    @Test
    fun `initialize over http`() {
        val response = post(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"junit","version":"1"}}}"""
        )
        assertEquals(200, response.code)
        assertTrue(response.contentType.startsWith("application/json"))
        val result = Json.parseToJsonElement(response.body).jsonObject["result"]!!.jsonObject
        assertEquals("http-test", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tools list and call over http`() {
        val list = post("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""")
        assertEquals(200, list.code)
        assertTrue(list.body.contains("\"echo\""))

        val call = post(
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo","arguments":{"text":"hi"}}}"""
        )
        assertEquals(200, call.code)
        assertTrue(call.body.contains("echo:hi"))
    }

    @Test
    fun `auth is required`() {
        val body = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
        assertEquals(401, post(body, token = null).code)
        assertEquals(401, post(body, token = "wrong").code)
        assertEquals(200, post(body, queryToken = "secret").code)
    }

    @Test
    fun `notification returns 202`() {
        val response = post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertEquals(202, response.code)
    }

    @Test
    fun `sse only accept gets event stream`() {
        val response = post(
            """{"jsonrpc":"2.0","id":9,"method":"ping"}""",
            accept = "text/event-stream"
        )
        assertEquals(200, response.code)
        assertTrue(response.contentType.startsWith("text/event-stream"))
        assertTrue(response.body.contains("event: message"))
        assertTrue(response.body.contains("\"result\""))
    }

    @Test
    fun `parse error returns 400`() {
        val response = post("{not json")
        assertEquals(400, response.code)
        assertTrue(response.body.contains("-32700"))
    }

    @Test
    fun `health endpoint`() {
        val url = URL("http://127.0.0.1:${server.port}/health")
        val connection = url.openConnection() as HttpURLConnection
        assertEquals(200, connection.responseCode)
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        assertTrue(text.contains("\"ok\":true"))
        connection.disconnect()
    }
}
