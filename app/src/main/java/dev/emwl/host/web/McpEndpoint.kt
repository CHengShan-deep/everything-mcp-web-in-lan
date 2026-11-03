package dev.emwl.host.web

import dev.emwl.host.domain.DomainException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP over Streamable HTTP with JSON responses: one JSON-RPC message per POST,
 * no server-initiated streams and no session state. Authentication is enforced by
 * the control-plane interceptor before this handler runs.
 */
fun Application.mcpEndpoint(tools: ToolRegistry) {
    routing {
        post("/mcp") {
            val raw = call.receiveText().trim()
            if (raw.isEmpty()) {
                call.respondText(parseError().toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            if (raw.startsWith("[")) {
                val batch = runCatching { JSONArray(raw) }.getOrNull()
                if (batch == null) {
                    call.respondText(parseError().toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
                val responses = JSONArray()
                for (index in 0 until batch.length()) {
                    batch.optJSONObject(index)?.let { message ->
                        handle(message, tools)?.let { responses.put(it) }
                    }
                }
                if (responses.length() == 0) {
                    call.respond(HttpStatusCode.Accepted)
                } else {
                    call.respondText(responses.toString(), ContentType.Application.Json)
                }
                return@post
            }

            val message = runCatching { JSONObject(raw) }.getOrNull()
            if (message == null) {
                call.respondText(parseError().toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            val response = handle(message, tools)
            if (response == null) {
                call.respond(HttpStatusCode.Accepted)
            } else {
                call.respondText(response.toString(), ContentType.Application.Json)
            }
        }

        get("/mcp") {
            call.respondText(
                """{"error":"server-initiated streams are not supported"}""",
                ContentType.Application.Json,
                HttpStatusCode.MethodNotAllowed
            )
        }

        delete("/mcp") {
            call.respondText(
                """{"error":"sessions are not supported"}""",
                ContentType.Application.Json,
                HttpStatusCode.MethodNotAllowed
            )
        }
    }
}

private suspend fun handle(message: JSONObject, tools: ToolRegistry): JSONObject? {
    val id = message.opt("id")
    val isNotification = !message.has("id")
    val method = message.optString("method")
    val params = message.optJSONObject("params") ?: JSONObject()

    if (isNotification) {
        return null
    }

    val result = when (method) {
        "initialize" -> initializeResult(params)
        "ping" -> JSONObject()
        "tools/list" -> toolsList(tools)
        "tools/call" -> callTool(tools, params)
        else -> return errorResponse(id, -32601, "Method not found: $method")
    }

    return JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id)
        .put("result", result)
}

private fun initializeResult(params: JSONObject): JSONObject {
    val requested = params.optString("protocolVersion")
    val version = if (requested in SUPPORTED_VERSIONS) requested else MCP_PROTOCOL_VERSION
    return JSONObject()
        .put("protocolVersion", version)
        .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
        .put(
            "serverInfo",
            JSONObject()
                .put("name", "everything-mcp-web-in-lan")
                .put("version", McpTools.VERSION)
        )
}

private fun toolsList(tools: ToolRegistry): JSONObject {
    val array = JSONArray()
    tools.tools.forEach { spec ->
        array.put(
            JSONObject()
                .put("name", spec.name)
                .put("description", spec.description)
                .put("inputSchema", spec.inputSchema)
        )
    }
    return JSONObject().put("tools", array)
}

private suspend fun callTool(tools: ToolRegistry, params: JSONObject): JSONObject {
    val name = params.optString("name")
    val arguments = params.optJSONObject("arguments") ?: JSONObject()
    val (ok, text) = withContext(Dispatchers.IO) {
        try {
            true to tools.call(name, arguments).toString()
        } catch (t: DomainException) {
            false to errorJson(t.code, t.message ?: t.code).toString()
        } catch (t: Throwable) {
            false to errorJson("INTERNAL", t.message ?: "tool failed").toString()
        }
    }
    return JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        .put("isError", !ok)
}

private fun errorResponse(id: Any?, code: Int, message: String): JSONObject = JSONObject()
    .put("jsonrpc", "2.0")
    .apply { if (id != null) put("id", id) else put("id", JSONObject.NULL) }
    .put("error", JSONObject().put("code", code).put("message", message))

private fun parseError(): JSONObject = errorResponse(null, -32700, "Parse error")

private fun errorJson(code: String, message: String): JSONObject =
    JSONObject().put("error", JSONObject().put("code", code).put("message", message))

private val SUPPORTED_VERSIONS = setOf("2024-11-05", "2025-03-26", "2025-06-18")
private const val MCP_PROTOCOL_VERSION = "2025-06-18"
