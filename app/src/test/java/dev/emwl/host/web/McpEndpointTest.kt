package dev.emwl.host.web

import dev.emwl.host.domain.DomainException
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpEndpointTest {

    private val registry = object : ToolRegistry {
        override val tools = listOf(
            ToolSpec(
                name = "echo",
                description = "Echo text",
                inputSchema = JSONObject().put("type", "object"),
                handler = { args -> JSONObject().put("echo", args.optString("text")) },
            )
        )

        override fun call(name: String, arguments: JSONObject): JSONObject =
            if (name == "echo") JSONObject().put("echo", arguments.optString("text"))
            else throw DomainException("UNKNOWN_TOOL", "unknown tool: $name")
    }

    private fun io.ktor.client.request.HttpRequestBuilder.json(body: String) {
        header(HttpHeaders.ContentType, "application/json")
        setBody(body)
    }

    @Test
    fun `initialize returns protocol version and tool capability`() = testApplication {
        application { mcpEndpoint(registry) }
        val response = client.post("/mcp") {
            json(
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                   "protocolVersion":"2025-06-18","capabilities":{},
                   "clientInfo":{"name":"test","version":"1"}}}"""
            )
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"protocolVersion\":\"2025-06-18\""))
        assertTrue(body.contains("\"tools\""))
    }

    @Test
    fun `tools list and call round trip`() = testApplication {
        application { mcpEndpoint(registry) }

        val list = client.post("/mcp") {
            json("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
        }.bodyAsText()
        assertTrue(list.contains("\"name\":\"echo\""))

        val call = client.post("/mcp") {
            json("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo","arguments":{"text":"hi"}}}""")
        }.bodyAsText()
        assertTrue(call.contains("\"isError\":false"))
        assertTrue(call.contains("hi"))
    }

    @Test
    fun `tool failures come back in band`() = testApplication {
        application { mcpEndpoint(registry) }
        val body = client.post("/mcp") {
            json("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"missing"}}""")
        }.bodyAsText()
        assertTrue(body.contains("\"isError\":true"))
        assertTrue(body.contains("UNKNOWN_TOOL"))
    }

    @Test
    fun `notifications are acknowledged without a body`() = testApplication {
        application { mcpEndpoint(registry) }
        val response = client.post("/mcp") {
            json("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `unknown method is a json rpc error`() = testApplication {
        application { mcpEndpoint(registry) }
        val body = client.post("/mcp") {
            json("""{"jsonrpc":"2.0","id":5,"method":"nope"}""")
        }.bodyAsText()
        assertTrue(body.contains("\"code\":-32601"))
    }
}
