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
            "Deploy a site manifest (and optional static files). Creates or migrates tables.",
            schema(required = listOf("site", "manifest")) {
                prop("site", "string")
                prop("manifest", "string")
                prop("files", "object")
            },
        ) { args ->
            val site = service.resolveSite(args.requireString("site"))
            val files = LinkedHashMap<String, String>()
            args.optJSONObject("files")?.let { json ->
                json.keys().forEach { path -> files[path] = json.getString(path) }
            }
            val result = service.deploy(site.id, args.requireString("manifest"), files)
            val updated = service.resolveSite(site.id)
            service.startListener(updated)
            result.put("urls", service.urls(updated))
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
            "Change read/write auth. Switching write to none makes the site publicly writable.",
            schema(required = listOf("site")) {
                prop("site", "string")
                prop("read", "string")
                prop("write", "string")
            },
        ) { args ->
            val site = service.resolveSite(args.requireString("site"))
            service.setAuth(
                site.id,
                args.optString("read").takeIf { it.isNotEmpty() },
                args.optString("write").takeIf { it.isNotEmpty() },
            )
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
    )

    private val byName = tools.associateBy { it.name }

    override fun call(name: String, arguments: JSONObject): JSONObject {
        val spec = byName[name] ?: throw DomainException("UNKNOWN_TOOL", "unknown tool: $name")
        return spec.handler(arguments)
    }

    companion object {
        const val VERSION = "0.1.0"
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
