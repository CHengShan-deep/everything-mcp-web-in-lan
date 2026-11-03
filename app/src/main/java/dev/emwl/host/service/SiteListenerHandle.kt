package dev.emwl.host.service

import android.content.Context
import dev.emwl.host.data.SiteRecord
import dev.emwl.host.domain.DomainException
import dev.emwl.host.domain.Manifest
import dev.emwl.host.domain.QueryFilters
import dev.emwl.host.domain.QueryFilters.Filter
import dev.emwl.host.domain.Tokens
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.json.JSONObject
import java.io.File

class SiteListenerHandle private constructor(private val engine: EmbeddedServer<*, *>) {

    fun stop() {
        runCatching { engine.stop(300, 800) }
    }

    companion object {
        fun start(context: Context, site: SiteRecord, service: SiteService): SiteListenerHandle {
            val host = if (site.isLanExposed) "0.0.0.0" else "127.0.0.1"
            val engine = embeddedServer(CIO, host = host, port = site.port) {
                siteModule(context, site.id, service)
            }.start(wait = false)
            return SiteListenerHandle(engine)
        }
    }
}

/**
 * Site routing reads the current record and manifest per request: auth rotation and
 * redeploys take effect without waiting for a listener restart.
 */
private fun Application.siteModule(context: Context, siteId: String, service: SiteService) {
    routing {
        get("/health") {
            val site = service.getSite(siteId)
            if (site == null) {
                call.respondJson(JSONObject().put("error", "site removed"), HttpStatusCode.NotFound)
                return@get
            }
            call.respondJson(
                JSONObject()
                    .put("ok", true)
                    .put("site", site.slug)
                    .put("deployed", service.manifest(site) != null)
                    .put("port", site.port)
            )
        }

        route("/api") {
            get("/{table}") { call.withSiteErrors { dataList(call, siteId, service) } }
            get("/{table}/{id}") { call.withSiteErrors { dataGet(call, siteId, service) } }
            post("/{table}") { call.withSiteErrors { dataInsert(call, siteId, service) } }
            patch("/{table}/{id}") { call.withSiteErrors { dataUpdate(call, siteId, service) } }
            delete("/{table}/{id}") { call.withSiteErrors { dataDelete(call, siteId, service) } }
        }

        get("/{path...}") {
            respondStatic(call, context, siteId, service)
        }
    }
}

private fun siteOf(siteId: String, service: SiteService): SiteRecord =
    service.getSite(siteId) ?: throw DomainException("SITE_NOT_FOUND", "site removed")

private fun manifestOf(site: SiteRecord, service: SiteService): Manifest =
    service.manifest(site) ?: throw DomainException("SITE_NOT_DEPLOYED", "site has no manifest yet")

private suspend fun dataList(call: ApplicationCall, siteId: String, service: SiteService) {
    val site = siteOf(siteId, service)
    val manifest = manifestOf(site, service)
    val table = manifest.table(call.parameters["table"] ?: "")
    authorize(call, site, write = false)

    val filters = ArrayList<Filter>()
    call.request.queryParameters.forEach { name, values ->
        if (name !in SET_QUERY_KEYS) {
            values.lastOrNull()?.let { raw -> filters += QueryFilters.parseFilter(name, raw) }
        }
    }
    filters.forEach { requireKnownField(table, it.field) }

    val allowedFields = table.fields.map { it.name }.toSet() + "id"
    val order = QueryFilters.parseOrder(call.request.queryParameters["order"], allowedFields)
    val limit = QueryFilters.parseLimit(call.request.queryParameters["limit"])
    val offset = QueryFilters.parseOffset(call.request.queryParameters["offset"])
    val countTotal = call.request.queryParameters["count"]?.toBooleanStrictOrNull() ?: false

    call.respondJson(service.database(site).list(table, filters, order, limit, offset, countTotal))
}

private suspend fun dataGet(call: ApplicationCall, siteId: String, service: SiteService) {
    val site = siteOf(siteId, service)
    val table = manifestOf(site, service).table(call.parameters["table"] ?: "")
    authorize(call, site, write = false)
    val id = call.parameters["id"]?.toLongOrNull()
        ?: throw DomainException("INVALID_ARGUMENT", "id must be a number")
    val row = service.database(site).get(table, id)
        ?: throw DomainException("NOT_FOUND", "row not found")
    call.respondJson(row)
}

private suspend fun dataInsert(call: ApplicationCall, siteId: String, service: SiteService) {
    val site = siteOf(siteId, service)
    val table = manifestOf(site, service).table(call.parameters["table"] ?: "")
    authorize(call, site, write = true)
    val body = JSONObject(call.receiveText())
    val id = service.database(site).insert(table, body)
    call.respondJson(JSONObject().put("id", id), HttpStatusCode.Created)
}

private suspend fun dataUpdate(call: ApplicationCall, siteId: String, service: SiteService) {
    val site = siteOf(siteId, service)
    val table = manifestOf(site, service).table(call.parameters["table"] ?: "")
    authorize(call, site, write = true)
    val id = call.parameters["id"]?.toLongOrNull()
        ?: throw DomainException("INVALID_ARGUMENT", "id must be a number")
    val updated = service.database(site).update(table, id, JSONObject(call.receiveText()))
    if (updated == 0) throw DomainException("NOT_FOUND", "row not found")
    call.respondJson(JSONObject().put("updated", updated))
}

private suspend fun dataDelete(call: ApplicationCall, siteId: String, service: SiteService) {
    val site = siteOf(siteId, service)
    val table = manifestOf(site, service).table(call.parameters["table"] ?: "")
    authorize(call, site, write = true)
    val id = call.parameters["id"]?.toLongOrNull()
        ?: throw DomainException("INVALID_ARGUMENT", "id must be a number")
    val deleted = service.database(site).delete(table, id)
    if (deleted == 0) throw DomainException("NOT_FOUND", "row not found")
    call.respondJson(JSONObject().put("deleted", deleted))
}

private fun requireKnownField(table: dev.emwl.host.domain.TableDef, field: String) {
    if (field != "id" && table.fields.none { it.name == field }) {
        throw DomainException("UNKNOWN_FIELD", "unknown field: $field")
    }
}

private fun authorize(call: ApplicationCall, site: SiteRecord, write: Boolean) {
    val mode = if (write) site.authWrite else site.authRead
    if (mode == "none") return
    val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: call.request.headers["X-Site-Token"]?.trim()
        ?: throw DomainException("AUTH_REQUIRED", "token required")
    if (!Tokens.matches(token, if (write) site.writeTokenHash else site.readTokenHash)) {
        throw DomainException("AUTH_REQUIRED", "token rejected")
    }
}

private suspend fun respondStatic(
    call: ApplicationCall,
    context: Context,
    siteId: String,
    service: SiteService,
) {
    val site = service.getSite(siteId)
    if (site == null) {
        call.respondText("not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
        return
    }
    val manifest = service.manifest(site)
    if (manifest == null) {
        call.respondText(
            "<!doctype html><html><body style=\"font-family:system-ui;margin:2rem\">" +
                "<h1>${site.name}</h1><p>This site has no deployment yet.</p></body></html>",
            ContentType.Text.Html,
            HttpStatusCode.ServiceUnavailable
        )
        return
    }

    val requested = call.parameters.getAll("path").orEmpty().joinToString("/").ifEmpty { "/" }
    if (requested != "/" && !Manifest.isSafeRelativePath(requested)) {
        call.respondText("not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
        return
    }

    val root = File(context.noBackupFilesDir, "sites/${site.id}/public").canonicalFile
    val relative = if (requested == "/") manifest.entry else requested
    val target = File(root, relative).canonicalFile
    val resolved = when {
        target.path.startsWith(root.path + File.separator) && target.isFile -> target
        manifest.spaFallback && !relative.substringAfterLast('/').contains('.') -> {
            val entry = File(root, manifest.entry).canonicalFile
            if (entry.path.startsWith(root.path + File.separator) && entry.isFile) entry else null
        }
        else -> null
    }

    if (resolved == null) {
        call.respondText("not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
        return
    }
    call.respondText(resolved.readText(), contentTypeOf(resolved.name))
}

private fun contentTypeOf(name: String): ContentType = when (name.substringAfterLast('.', "").lowercase()) {
    "html", "htm" -> ContentType.Text.Html
    "css" -> ContentType.Text.CSS
    "js", "mjs" -> ContentType.Application.JavaScript
    "json" -> ContentType.Application.Json
    "svg" -> ContentType.Image.SVG
    "png" -> ContentType.Image.PNG
    "jpg", "jpeg" -> ContentType.Image.JPEG
    "webp" -> ContentType.parse("image/webp")
    "ico" -> ContentType.parse("image/x-icon")
    "woff2" -> ContentType.parse("font/woff2")
    "txt" -> ContentType.Text.Plain
    else -> ContentType.Application.OctetStream
}

private suspend fun ApplicationCall.respondJson(body: JSONObject, status: HttpStatusCode = HttpStatusCode.OK) {
    respondText(body.toString(), ContentType.Application.Json, status)
}

private suspend fun ApplicationCall.withSiteErrors(block: suspend () -> Unit) {
    try {
        block()
    } catch (t: DomainException) {
        val status = when (t.code) {
            "SITE_NOT_FOUND", "TABLE_NOT_FOUND", "NOT_FOUND" -> HttpStatusCode.NotFound
            "AUTH_REQUIRED" -> HttpStatusCode.Unauthorized
            "CONFLICT" -> HttpStatusCode.Conflict
            else -> HttpStatusCode.BadRequest
        }
        respondJson(
            JSONObject().put(
                "error",
                JSONObject().put("code", t.code).put("message", t.message ?: t.code)
            ),
            status
        )
    } catch (t: Throwable) {
        respondJson(
            JSONObject().put(
                "error",
                JSONObject().put("code", "INTERNAL").put("message", "request failed")
            ),
            HttpStatusCode.InternalServerError
        )
    }
}

private val SET_QUERY_KEYS = setOf("limit", "offset", "order", "count", "isnull")
