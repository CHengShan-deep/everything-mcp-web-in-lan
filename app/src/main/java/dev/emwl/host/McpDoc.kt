package dev.emwl.host

import dev.emwl.host.web.ToolSpec

/**
 * The shareable hand-off document: everything an agent needs to talk to this device's MCP
 * control plane, generated from the live tool registry so it never drifts from the server.
 */
object McpDoc {

    fun fileName(): String = "pocketserve-mcp.md"

    fun build(port: Int, token: String, tools: List<ToolSpec>): String = buildString {
        appendLine("# PocketServe MCP endpoint")
        appendLine()
        appendLine("PocketServe hosts websites from an Android phone on the local network.")
        appendLine("Paste this document into your agent so it can manage those sites.")
        appendLine()
        appendLine("## Connection")
        appendLine()
        appendLine("- Endpoint: `http://127.0.0.1:$port/mcp` (loopback only — the agent must run on this device)")
        appendLine("- Auth header: `Authorization: Bearer $token`")
        appendLine("- Transport: JSON-RPC 2.0 over HTTP `POST`, JSON responses, no sessions and no SSE")
        appendLine("- Optional first call: `initialize` with protocol version `2025-06-18`")
        appendLine()
        appendLine("Token: `$token`")
        appendLine()
        appendLine("> The token controls every site on this device. Treat it as a secret.")
        appendLine()
        appendLine("## Smoke test")
        appendLine()
        appendLine("```bash")
        appendLine("curl -s http://127.0.0.1:$port/mcp \\")
        appendLine("  -H 'Authorization: Bearer $token' -H 'content-type: application/json' \\")
        appendLine("  -d '{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}'")
        appendLine("```")
        appendLine()
        appendLine("## Typical flow")
        appendLine()
        appendLine("1. `site_create` → returns the site and its write token once")
        appendLine("2. `site_deploy` with a manifest (tables/fields/entry) and optional static files")
        appendLine("3. `site_exposure_set` with `lan: true` to open it to the local network")
        appendLine("4. `site_url_get` for the local, LAN and named URLs")
        appendLine("5. `site_backup_create` / `site_revision_list` for snapshots and rollbacks")
        appendLine()
        appendLine("## Tools (${tools.size})")
        appendLine()
        tools.sortedBy { it.name }.forEach { tool ->
            val required = tool.inputSchema.optJSONArray("required")
            val args = buildList {
                for (index in 0 until (required?.length() ?: 0)) add(required!!.getString(index))
            }
            val signature = if (args.isEmpty()) "" else " — required: ${args.joinToString(", ") { "`$it`" }}"
            appendLine("- `${tool.name}` — ${tool.description}$signature")
        }
        appendLine()
        appendLine("## Error handling")
        appendLine()
        appendLine("Transport errors come back as JSON-RPC `error`; tool failures come back as a normal")
        appendLine("result with `isError: true` and a `{code, message}` body. Codes worth handling:")
        appendLine()
        appendLine("- `SCHEMA_DESTRUCTIVE` — the deploy or rollback would drop stored data; retry with")
        appendLine("  `confirmDestructive: true` only after the user agrees")
        appendLine("- `CONFIRM_REQUIRED` — restore/rollback needs `confirm: true`")
        appendLine("- `SITE_NOT_FOUND`, `TABLE_NOT_FOUND`, `NOT_FOUND` — re-read `site_list`/`site_schema_get`")
        appendLine("- `PUBLIC_WRITE_CONFIRMATION_REQUIRED` — making writes anonymous is deliberate")
        appendLine("- `MAINTENANCE` — a backup is quiescing this site; retry shortly")
        appendLine()
        appendLine("## Notes")
        appendLine()
        appendLine("- Data plane: one port per site; the hub on `47101` routes by `Host` header")
        appendLine("- Limits: 32 sites, 20 revisions and 20 backups per site, last 1000 requests logged")
    }
}
