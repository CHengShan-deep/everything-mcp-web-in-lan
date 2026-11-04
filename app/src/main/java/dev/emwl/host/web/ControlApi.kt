package dev.emwl.host.web

import android.content.Context
import dev.emwl.host.HostState
import dev.emwl.host.Metrics
import dev.emwl.host.Ports
import dev.emwl.host.UiBridge
import dev.emwl.host.domain.DomainException
import dev.emwl.host.domain.Manifest
import dev.emwl.host.domain.RequestGuards
import dev.emwl.host.domain.Tokens
import dev.emwl.host.service.SiteService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.application.install
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Loopback control plane: admin JSON API, the web console, and the MCP endpoint.
 * Everything that can mutate state requires a bearer token.
 */
fun Application.controlModule(
    context: Context,
    service: SiteService,
    tokens: ControlTokens,
    currentControlPort: () -> Int,
) {
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()

        if (!RequestGuards.isLoopbackHost(call.request.headers["Host"])) {
            call.respondText(
                """{"error":{"code":"FORBIDDEN","message":"loopback Host required"}}""",
                ContentType.Application.Json,
                HttpStatusCode.Forbidden
            )
            finish()
            return@intercept
        }
        if (!RequestGuards.isAllowedOrigin(call.request.headers["Origin"])) {
            call.respondText(
                """{"error":{"code":"FORBIDDEN","message":"origin rejected"}}""",
                ContentType.Application.Json,
                HttpStatusCode.Forbidden
            )
            finish()
            return@intercept
        }

        val expectedHash = when {
            path.startsWith("/admin/api") -> tokens.adminHash()
            path == "/mcp" -> tokens.mcpHash()
            path == "/diag" || path == "/sites" || path == "/ui/action" -> tokens.adminHash()
            else -> null
        }
        if (expectedHash != null) {
            val provided = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: call.request.headers["X-Admin-Token"]?.trim()
            if (!Tokens.matches(provided, expectedHash)) {
                call.respondText(
                    """{"error":{"code":"AUTH_REQUIRED","message":"bearer token required"}}""",
                    ContentType.Application.Json,
                    HttpStatusCode.Unauthorized
                )
                finish()
            }
        }
    }

    routing {
        get("/health") {
            call.respondText(
                JSONObject()
                    .put("ok", true)
                    .put("pid", android.os.Process.myPid())
                    .put("sdk", android.os.Build.VERSION.SDK_INT)
                    .put("controlPort", currentControlPort())
                    .put("sites", service.listSites().size)
                    .toString(),
                ContentType.Application.Json
            )
        }

        get("/diag") {
            call.respondText(diagJson(service, currentControlPort()), ContentType.Application.Json)
        }

        get("/sites") {
            call.respondText(siteListJson(service).toString(), ContentType.Application.Json)
        }

        get("/ui") {
            call.respondText(UiBridge.snapshot().toString(), ContentType.Application.Json)
        }

        post("/ui/action") {
            val body = call.receiveText()
            call.respondText(
                withContext(Dispatchers.Main) { UiBridge.perform(body) }.toString(),
                ContentType.Application.Json
            )
        }

        get("/admin") {
            val html = runCatching {
                context.assets.open("admin.html").bufferedReader().use { it.readText() }
            }.getOrElse { "<h1>admin asset missing</h1>" }
            call.respondText(html, ContentType.Text.Html)
        }

        route("/admin/api") {
            get("/state") {
                call.respondJson(
                    JSONObject()
                        .put("sites", service.listSites().size)
                        .put("maxSites", Ports.MAX_SITES)
                        .put("controlPort", currentControlPort())
                        .put("lanIpv4", Metrics.lanIpv4())
                        .put("baseDomain", service.baseDomain)
                        .put("running", HostState.running)
                )
            }

            get("/sites") { call.respondJson(siteListJson(service)) }

            post("/sites") {
                call.api { body ->
                    val result = service.createSite(
                        slug = body.optString("slug"),
                        name = body.optString("name"),
                    )
                    val site = service.resolveSite(result.getJSONObject("site").getString("id"))
                    service.startListener(site)
                    result.put("urls", service.urls(site))
                    result
                }
            }

            get("/sites/{ref}") {
                call.api {
                    val site = service.resolveSite(call.parameters["ref"].orEmpty())
                    val manifest = service.manifest(site)
                    JSONObject()
                        .put("site", service.siteJson(site))
                        .put("urls", service.urls(site))
                        .put("manifest", site.manifestJson ?: JSONObject.NULL)
                        .put("tables", manifest?.tables?.keys?.toList() ?: emptyList<String>())
                }
            }

            patch("/sites/{ref}") {
                call.api { body ->
                    val site = service.resolveSite(call.parameters["ref"].orEmpty())
                    val updated = service.renameSite(site.id, body.optString("name", site.name))
                    JSONObject().put("site", service.siteJson(updated))
                }
            }

            delete("/sites/{ref}") {
                call.api {
                    val site = service.resolveSite(call.parameters["ref"].orEmpty())
                    JSONObject().put("deleted", service.deleteSite(site.id)).put("slug", site.slug)
                }
            }

            post("/sites/{ref}/deploy") {
                call.api { body ->
                    val site = service.resolveSite(call.parameters["ref"].orEmpty())
                    val files = LinkedHashMap<String, String>()
                    body.optJSONObject("files")?.let { json ->
                        json.keys().forEach { path -> files[path] = json.getString(path) }
                    }
                    val result = service.deploy(
                        id = site.id,
                        manifestJson = body.optString("manifest"),
                        files = files,
                    )
                    val updated = service.resolveSite(site.id)
                    service.startListener(updated)
                    result.put("urls", service.urls(updated))
                }
            }

            put("/sites/{ref}/exposure") {
                call.api { body ->
                    val site = service.resolveSite(call.parameters["ref"].orEmpty())
                    val updated = service.setExposure(site.id, body.optBoolean("lan", false))
                    JSONObject().put("site", service.siteJson(updated)).put("urls", service.urls(updated))
                }
            }

            put("/sites/{ref}/auth") {
                call.api { body ->
                    val site = service.resolveSite(call.parameters["ref"].orEmpty())
                    service.setAuth(
                        site.id,
                        body.optString("read").takeIf { it.isNotEmpty() },
                        body.optString("write").takeIf { it.isNotEmpty() },
                        confirmPublicWrite = body.optBoolean("confirmPublicWrite", false),
                    )
                }
            }

            get("/sites/{ref}/logs") {
                call.api {
                    val site = service.resolveSite(call.parameters["ref"].orEmpty())
                    JSONObject().put("items", service.auditLog(site.slug, 100))
                }
            }

            get("/logs") {
                call.api {
                    JSONObject().put("items", service.auditLog(null, 100))
                }
            }

            post("/sites/{ref}/token") {
                call.api { body ->
                    val site = service.resolveSite(call.parameters["ref"].orEmpty())
                    service.rotateToken(site.id, body.optString("which", "write"))
                }
            }

            get("/sites/{ref}/urls") {
                call.api {
                    val site = service.resolveSite(call.parameters["ref"].orEmpty())
                    service.urls(site)
                }
            }

            get("/sites/{ref}/data/{table}") {
                call.api {
                    val site = service.resolveSite(call.parameters["ref"].orEmpty())
                    val manifest = service.manifest(site)
                        ?: throw DomainException("SITE_NOT_DEPLOYED", "site has no manifest yet")
                    val table = manifest.table(call.parameters["table"].orEmpty())
                    JSONObject(
                        service.database(site).list(
                            table = table,
                            filters = emptyList(),
                            order = null,
                            limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20,
                            offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0,
                            countTotal = true,
                        ).toString()
                    )
                }
            }

            get("/sites/{ref}/files") {
                call.api {
                    val site = service.resolveSite(call.parameters["ref"].orEmpty())
                    val store = service.fileStore(site)
                    JSONObject()
                        .put("items", store.index.list())
                        .put("usedBytes", store.usedBytes())
                        .put("quotaBytes", dev.emwl.host.service.SiteFileStore.QUOTA_BYTES)
                }
            }

            post("/sites/{ref}/files") {
                call.api { body ->
                    val site = service.resolveSite(call.parameters["ref"].orEmpty())
                    val manifest = service.manifest(site)
                        ?: throw DomainException("SITE_NOT_DEPLOYED", "site has no manifest yet")
                    val name = body.optString("name").ifEmpty { "upload.bin" }
                    val bytes = runCatching {
                        android.util.Base64.decode(
                            body.optString("contentBase64"),
                            android.util.Base64.NO_WRAP
                        )
                    }.getOrElse {
                        throw DomainException("INVALID_ARGUMENT", "contentBase64 is not valid base64")
                    }
                    val record = service.fileStore(site).save(name, bytes, manifest.files)
                    JSONObject()
                        .put("id", record.id)
                        .put("name", record.name)
                        .put("mime", record.mime)
                        .put("size", record.size)
                        .put("sha256", record.sha256)
                }
            }

            delete("/sites/{ref}/files/{id}") {
                call.api {
                    val site = service.resolveSite(call.parameters["ref"].orEmpty())
                    val id = call.parameters["id"].orEmpty()
                    if (!service.fileStore(site).delete(id)) {
                        throw DomainException("NOT_FOUND", "file not found")
                    }
                    JSONObject().put("deleted", id)
                }
            }

            get("/sites/{ref}/backups") {
                call.api {
                    val site = service.resolveSite(call.parameters["ref"].orEmpty())
                    JSONObject().put("items", service.backups().list(site))
                }
            }

            post("/sites/{ref}/backups") {
                call.api {
                    val site = service.resolveSite(call.parameters["ref"].orEmpty())
                    service.backups().create(site)
                }
            }

            post("/sites/{ref}/backups/{backup}/restore") {
                call.api { body ->
                    val site = service.resolveSite(call.parameters["ref"].orEmpty())
                    service.backups().restore(
                        site,
                        call.parameters["backup"].orEmpty(),
                        body.optBoolean("confirm", false),
                    )
                }
            }
        }
    }

    mcpEndpoint(McpTools(service, currentControlPort))
}

private fun siteListJson(service: SiteService): JSONObject {
    val items = JSONArray()
    service.listSites().forEach { site ->
        items.put(service.siteJson(site).put("urls", service.urls(site)))
    }
    return JSONObject().put("items", items)
}

private fun diagJson(service: SiteService, controlPort: Int): String = JSONObject()
    .put("pid", android.os.Process.myPid())
    .put("controlPort", controlPort)
    .put("lanIpv4", Metrics.lanIpv4())
    .put("threads", Metrics.threadCount())
    .put("fds", Metrics.fileDescriptors())
    .put("pssKb", Metrics.pssKb())
    .put("sites", service.listSites().size)
    .put("baseDomain", service.baseDomain)
    .toString()

private suspend fun ApplicationCall.respondJson(body: JSONObject, status: HttpStatusCode = HttpStatusCode.OK) {
    respondText(body.toString(), ContentType.Application.Json, status)
}

private suspend fun ApplicationCall.api(block: (JSONObject) -> JSONObject) {
    try {
        val raw = runCatching { receiveText() }.getOrElse { "{}" }
        val body = if (raw.isBlank()) JSONObject() else JSONObject(raw)
        respondJson(withContext(Dispatchers.IO) { block(body) })
    } catch (t: DomainException) {
        val status = when (t.code) {
            "SITE_NOT_FOUND", "TABLE_NOT_FOUND", "NOT_FOUND" -> HttpStatusCode.NotFound
            "AUTH_REQUIRED" -> HttpStatusCode.Unauthorized
            "SLUG_TAKEN", "CONFLICT", "PORT_EXHAUSTED" -> HttpStatusCode.Conflict
            else -> HttpStatusCode.BadRequest
        }
        respondJson(
            JSONObject().put(
                "error",
                JSONObject().put("code", t.code).put("message", t.message ?: t.code)
            ),
            status
        )
    } catch (t: Exception) {
        respondJson(
            JSONObject().put(
                "error",
                JSONObject().put("code", "INVALID_JSON").put("message", "request body rejected")
            ),
            HttpStatusCode.BadRequest
        )
    }
}
