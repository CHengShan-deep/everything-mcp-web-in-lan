package dev.emwl.host.web

import dev.emwl.host.Metrics
import dev.emwl.host.Ports
import dev.emwl.host.domain.DomainException
import dev.emwl.host.domain.QueryFilters
import dev.emwl.host.service.SiteService
import org.json.JSONArray
import org.json.JSONObject

class ToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
    val handler: (JSONObject) -> JSONObject,
)

/**
 * The v1 MCP tool surface. Every tool goes through SiteService — the same write path
 * the admin API uses — and none of them exposes arbitrary SQL, paths or URLs.
 */
interface ToolRegistry {
    val tools: List<ToolSpec>
    fun call(name: String, arguments: JSONObject): JSONObject
}

class McpTools(
    private val service: SiteService,
    private val controlPort: () -> Int,
) : ToolRegistry {

    override val tools: List<ToolSpec> = listOf(
        tool(
            "server_info",
            "Report host version, site count, control port and LAN address.",
        ) {
            JSONObject()
                .put("name", "everything-mcp-web-in-lan")
                .put("version", VERSION)
                .put("controlPort", controlPort())
                .put("lanIpv4", Metrics.lanIpv4())
                .put("sites", service.listSites().size)
                .put("maxSites", Ports.MAX_SITES)
        },

        tool("site_list", "List hosted sites with their ports, exposure and auth mode.") {
            val items = JSONArray()
            service.listSites().forEach { items.put(service.siteJson(it).put("urls", service.urls(it))) }
            JSONObject().put("items", items)
        },

        tool(
            "site_get",
            "Get one site by id or slug.",
            schema(required = listOf("site")) { prop("site", "string") },
        ) { args ->
            val site = service.resolveSite(args.requireString("site"))
            JSONObject().put("site", service.siteJson(site)).put("urls", service.urls(site))
        },

        tool(
            "site_create",
            "Create a site. Returns the write token once; store it safely.",
            schema(required = listOf("slug", "name")) {
                prop("slug", "string")
                prop("name", "string")
            },
        ) { args ->
            val result = service.createSite(args.requireString("slug"), args.requireString("name"))
            val site = service.resolveSite(result.getJSONObject("site").getString("id"))
            service.startListener(site)
            result.put("urls", service.urls(site))
        },

        tool(
            "site_update",
            "Rename a site.",
            schema(required = listOf("site", "name")) {
                prop("site", "string")
                prop("name", "string")
            },
        ) { args ->
            val site = service.resolveSite(args.requireString("site"))
            val updated = service.renameSite(site.id, args.requireString("name"))
            JSONObject().put("site", service.siteJson(updated))
        },

        tool(
            "site_delete",
            "Delete a site, its data and its files. The port enters a cooldown.",
            schema(required = listOf("site")) { prop("site", "string") },
        ) { args ->
            val site = service.resolveSite(args.requireString("site"))
            JSONObject().put("deleted", service.deleteSite(site.id)).put("slug", site.slug)
        },

        tool(
            "site_deploy",
            "Deploy a site manifest (and optional static files). Destructive schema changes need confirmDestructive=true.",
            schema(required = listOf("site", "manifest")) {
                prop("site", "string")
                prop("manifest", "string")
                prop("files", "object")
                prop("confirmDestructive", "boolean")
            },
        ) { args ->
            val site = service.resolveSite(args.requireString("site"))
            val files = LinkedHashMap<String, String>()
            args.optJSONObject("files")?.let { json ->
                json.keys().forEach { path -> files[path] = json.getString(path) }
            }
            val result = service.deploy(
                site.id,
                args.requireString("manifest"),
                files,
                args.optBoolean("confirmDestructive", false),
            )
            val updated = service.resolveSite(site.id)
            service.startListener(updated)
            result.put("urls", service.urls(updated))
        },

        tool(
            "site_schema_plan",
            "Report what deploying this manifest would change in the database, without applying it.",
            schema(required = listOf("site", "manifest")) {
                prop("site", "string")
                prop("manifest", "string")
            },
        ) { args ->
            val site = service.resolveSite(args.requireString("site"))
            val manifest = dev.emwl.host.domain.Manifest.parse(args.requireString("manifest"))
            service.database(site).schemaPlan(manifest).toJson()
        },

        tool(
            "site_schema_get",
            "Return the deployed manifest of a site.",
            schema(required = listOf("site")) { prop("site", "string") },
        ) { args ->
            val site = service.resolveSite(args.requireString("site"))
            val manifest = service.manifest(site)
                ?: throw DomainException("SITE_NOT_DEPLOYED", "site has no manifest yet")
            JSONObject()
                .put("manifest", site.manifestJson ?: "")
                .put("tables", JSONArray(manifest.tables.keys.toList()))
        },

        tool(
            "site_data_query",
            "Read rows from one declared table.",
            schema(required = listOf("site", "table")) {
                prop("site", "string")
                prop("table", "string")
                prop("limit", "integer")
                prop("offset", "integer")
                prop("order", "string")
            },
        ) { args ->
            val site = service.resolveSite(args.requireString("site"))
            val manifest = service.manifest(site)
                ?: throw DomainException("SITE_NOT_DEPLOYED", "site has no manifest yet")
            val table = manifest.table(args.requireString("table"))
            val allowed = table.fields.map { it.name }.toSet() + "id"
            JSONObject(
                service.database(site).list(
                    table = table,
                    filters = emptyList(),
                    order = args.optString("order").takeIf { it.isNotEmpty() }
                        ?.let { QueryFilters.parseOrder(it, allowed) },
                    limit = args.optInt("limit", 20),
                    offset = args.optInt("offset", 0),
                    countTotal = true,
                ).toString()
            )
        },

        tool(
            "site_data_mutate",
            "Insert, update or delete one row in a declared table.",
            schema(required = listOf("site", "table", "op")) {
                prop("site", "string")
                prop("table", "string")
                prop("op", "string")
                prop("id", "integer")
                prop("values", "object")
            },
        ) { args ->
            val site = service.resolveSite(args.requireString("site"))
            val manifest = service.manifest(site)
                ?: throw DomainException("SITE_NOT_DEPLOYED", "site has no manifest yet")
            val table = manifest.table(args.requireString("table"))
            val database = service.database(site)
            val values = args.optJSONObject("values") ?: JSONObject()
            when (args.requireString("op")) {
                "insert" -> JSONObject().put("id", database.insert(table, values))
                "update" -> {
                    val id = args.optLong("id", -1)
                    if (id < 0) throw DomainException("INVALID_ARGUMENT", "id is required for update")
                    JSONObject().put("updated", database.update(table, id, values))
                }
                "delete" -> {
                    val id = args.optLong("id", -1)
                    if (id < 0) throw DomainException("INVALID_ARGUMENT", "id is required for delete")
                    JSONObject().put("deleted", database.delete(table, id))
                }
                else -> throw DomainException("INVALID_ARGUMENT", "op must be insert, update or delete")
            }
        },

        tool(
            "site_auth_get",
            "Read the site auth modes.",
            schema(required = listOf("site")) { prop("site", "string") },
        ) { args ->
            val site = service.resolveSite(args.requireString("site"))
            JSONObject().put("read", site.authRead).put("write", site.authWrite)
        },

        tool(
            "site_auth_set",
            "Change read/write auth. Making writes public requires confirmPublicWrite=true.",
            schema(required = listOf("site")) {
                prop("site", "string")
                prop("read", "string")
                prop("write", "string")
                prop("confirmPublicWrite", "boolean")
            },
        ) { args ->
            val site = service.resolveSite(args.requireString("site"))
            service.setAuth(
                site.id,
                args.optString("read").takeIf { it.isNotEmpty() },
                args.optString("write").takeIf { it.isNotEmpty() },
                confirmPublicWrite = args.optBoolean("confirmPublicWrite", false),
            )
        },

        tool(
            "site_logs_get",
            "Recent audit events for one site (or the whole host when site is omitted).",
            schema {
                prop("site", "string")
                prop("limit", "integer")
            },
        ) { args ->
            val site = args.optString("site").takeIf { it.isNotEmpty() }
                ?.let { service.resolveSite(it).slug }
            JSONObject().put("items", service.auditLog(site, args.optInt("limit", 50)))
        },

        tool(
            "site_token_rotate",
            "Rotate the read or write token and return the new token once.",
            schema(required = listOf("site", "which")) {
                prop("site", "string")
                prop("which", "string")
            },
        ) { args ->
            val site = service.resolveSite(args.requireString("site"))
            service.rotateToken(site.id, args.requireString("which"))
        },

        tool(
            "site_exposure_get",
            "Report whether the site is exposed to the LAN.",
            schema(required = listOf("site")) { prop("site", "string") },
        ) { args ->
            val site = service.resolveSite(args.requireString("site"))
            JSONObject().put("exposure", site.exposure).put("lan", site.isLanExposed)
        },

        tool(
            "site_exposure_set",
            "Expose the site to the LAN or restrict it to this device.",
            schema(required = listOf("site", "lan")) {
                prop("site", "string")
                prop("lan", "boolean")
            },
        ) { args ->
            val site = service.resolveSite(args.requireString("site"))
            if (!args.has("lan")) throw DomainException("INVALID_ARGUMENT", "lan is required")
            val updated = service.setExposure(site.id, args.getBoolean("lan"))
            JSONObject().put("site", service.siteJson(updated)).put("urls", service.urls(updated))
        },

        tool(
            "site_url_get",
            "Return local, LAN, named and gateway URLs for a site.",
            schema(required = listOf("site")) { prop("site", "string") },
        ) { args ->
            service.urls(service.resolveSite(args.requireString("site")))
        },

        tool(
            "site_files_list",
            "List uploaded files and current quota usage for a site.",
            schema(required = listOf("site")) { prop("site", "string") },
        ) { args ->
            val site = service.resolveSite(args.requireString("site"))
            val store = service.fileStore(site)
            JSONObject()
                .put("items", store.index.list())
                .put("usedBytes", store.usedBytes())
        },

        tool(
            "site_file_upload",
            "Upload a base64 file (max 2 MB here; larger files use the site HTTP API).",
            schema(required = listOf("site", "name", "contentBase64")) {
                prop("site", "string")
                prop("name", "string")
                prop("contentBase64", "string")
            },
        ) { args ->
            val site = service.resolveSite(args.requireString("site"))
            val manifest = service.manifest(site)
                ?: throw DomainException("SITE_NOT_DEPLOYED", "site has no manifest yet")
            val bytes = runCatching {
                android.util.Base64.decode(args.requireString("contentBase64"), android.util.Base64.NO_WRAP)
            }.getOrElse { throw DomainException("INVALID_ARGUMENT", "contentBase64 is not valid base64") }
            if (bytes.size > MCP_UPLOAD_LIMIT) {
                throw DomainException("FILE_TOO_LARGE", "MCP uploads are limited to $MCP_UPLOAD_LIMIT bytes")
            }
            val record = service.fileStore(site).save(args.requireString("name"), bytes, manifest.files)
            JSONObject()
                .put("id", record.id)
                .put("name", record.name)
                .put("mime", record.mime)
                .put("size", record.size)
                .put("sha256", record.sha256)
        },

        tool(
            "site_file_delete",
            "Delete one uploaded file.",
            schema(required = listOf("site", "id")) {
                prop("site", "string")
                prop("id", "string")
            },
        ) { args ->
            val site = service.resolveSite(args.requireString("site"))
            val deleted = service.fileStore(site).delete(args.requireString("id"))
            if (!deleted) throw DomainException("NOT_FOUND", "file not found")
            JSONObject().put("deleted", true)
        },

        tool(
            "site_backup_create",
            "Create a backup archive (manifest, public files, uploads, WAL-safe database snapshot).",
            schema(required = listOf("site")) { prop("site", "string") },
        ) { args ->
            val site = service.resolveSite(args.requireString("site"))
            service.backups().create(site)
        },

        tool(
            "site_backups_list",
            "List backup archives for a site.",
            schema(required = listOf("site")) { prop("site", "string") },
        ) { args ->
            val site = service.resolveSite(args.requireString("site"))
            JSONObject().put("items", service.backups().list(site))
        },

        tool(
            "site_backup_restore",
            "Restore a backup over the live site. Requires confirm=true; replaces data, files and manifest.",
            schema(required = listOf("site", "backup", "confirm")) {
                prop("site", "string")
                prop("backup", "string")
                prop("confirm", "boolean")
            },
        ) { args ->
            val site = service.resolveSite(args.requireString("site"))
            service.backups().restore(
                site,
                args.requireString("backup"),
                args.optBoolean("confirm", false),
            )
        },

        tool(
            "site_backup_restore_as",
            "Restore a backup into a new site (own id, port and write token). Requires confirm=true.",
            schema(required = listOf("site", "backup", "slug", "name", "confirm")) {
                prop("site", "string")
                prop("backup", "string")
                prop("slug", "string")
                prop("name", "string")
                prop("confirm", "boolean")
            },
        ) { args ->
            val source = service.resolveSite(args.requireString("site"))
            service.backups().restoreAsNew(
                source,
                args.requireString("backup"),
                args.requireString("slug"),
                args.requireString("name"),
                args.optBoolean("confirm", false),
            )
        },

        tool(
            "site_revision_list",
            "List stored content revisions (newest first) and mark the one being served.",
            schema(required = listOf("site")) { prop("site", "string") },
        ) { args ->
            service.revisions().list(service.resolveSite(args.requireString("site")))
        },

        tool(
            "site_revision_current",
            "Report the served revision, the live content digest and whether they still match.",
            schema(required = listOf("site")) { prop("site", "string") },
        ) { args ->
            service.revisions().current(service.resolveSite(args.requireString("site")))
        },

        tool(
            "site_revision_rollback",
            "Serve a stored revision again. Requires confirm=true; confirmDestructive=true if it narrows the schema.",
            schema(required = listOf("site", "revision", "confirm")) {
                prop("site", "string")
                prop("revision", "string")
                prop("confirm", "boolean")
                prop("confirmDestructive", "boolean")
            },
        ) { args ->
            val site = service.resolveSite(args.requireString("site"))
            service.revisions().rollback(
                site,
                args.requireString("revision"),
                args.optBoolean("confirm", false),
                args.optBoolean("confirmDestructive", false),
            )
        },

        tool(
            "site_requests_get",
            "Recent requests served by one site, newest first. status accepts a code (404) or a class (4xx).",
            schema(required = listOf("site")) {
                prop("site", "string")
                prop("limit", "integer")
                prop("offset", "integer")
                prop("status", "string")
                prop("path", "string")
            },
        ) { args ->
            val site = service.resolveSite(args.requireString("site"))
            service.database(site).listRequests(
                limit = args.optInt("limit", 50).coerceIn(1, 500),
                offset = args.optInt("offset", 0).coerceAtLeast(0),
                status = args.optString("status").takeIf { it.isNotEmpty() },
                path = args.optString("path").takeIf { it.isNotEmpty() },
            )
        },
    )

    private val byName = tools.associateBy { it.name }

    override fun call(name: String, arguments: JSONObject): JSONObject {
        val spec = byName[name] ?: throw DomainException("UNKNOWN_TOOL", "unknown tool: $name")
        return spec.handler(arguments)
    }

    companion object {
        const val VERSION = "0.1.0"
        const val MCP_UPLOAD_LIMIT = 2 * 1024 * 1024
    }
}

private fun tool(
    name: String,
    description: String,
    inputSchema: JSONObject = schema(),
    handler: (JSONObject) -> JSONObject,
): ToolSpec = ToolSpec(name, description, inputSchema, handler)

private fun schema(required: List<String> = emptyList(), props: SchemaBuilder.() -> Unit = {}): JSONObject {
    val builder = SchemaBuilder().apply(props)
    return JSONObject()
        .put("type", "object")
        .put("properties", builder.properties)
        .put("required", JSONArray(required))
}

private class SchemaBuilder {
    val properties = JSONObject()

    fun prop(name: String, type: String) {
        properties.put(name, JSONObject().put("type", type))
    }
}

private fun JSONObject.requireString(name: String): String {
    val value = optString(name)
    if (value.isEmpty()) throw DomainException("INVALID_ARGUMENT", "$name is required")
    return value
}
