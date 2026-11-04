package dev.emwl.host.domain

import org.json.JSONArray
import org.json.JSONObject

data class ColumnState(val name: String, val type: String, val notNull: Boolean)

data class TableState(val name: String, val columns: List<ColumnState>, val rows: Int)

data class SchemaChange(
    val kind: String,
    val target: String,
    val detail: String,
    val destructive: Boolean,
)

/**
 * What deploying a manifest would do to an existing database. Additive changes apply
 * silently; anything that drops or rewrites data must be confirmed by the caller.
 */
data class SchemaPlan(
    val changes: List<SchemaChange>,
    val rebuild: Set<String>,
    val dropTables: List<String>,
) {

    val destructive: List<SchemaChange> get() = changes.filter { it.destructive }

    fun summary(): String = destructive.joinToString("; ") { "${it.kind.replace('_', ' ')} ${it.target}" }

    fun toJson(): JSONObject {
        val items = JSONArray()
        changes.forEach { change ->
            items.put(
                JSONObject()
                    .put("kind", change.kind)
                    .put("target", change.target)
                    .put("detail", change.detail)
                    .put("destructive", change.destructive)
            )
        }
        return JSONObject()
            .put("changes", items)
            .put("destructive", JSONArray(destructive.map { it.target }))
            .put("rebuild", JSONArray(rebuild.toSortedSet().toList()))
            .put("dropTables", JSONArray(dropTables))
            .put("requiresConfirmation", destructive.isNotEmpty())
    }
}

object SchemaDiff {

    private const val PRIMARY_KEY = "id"

    fun plan(current: List<TableState>, manifest: Manifest): SchemaPlan {
        val changes = ArrayList<SchemaChange>()
        val rebuild = HashSet<String>()

        val dropTables = current.map { it.name }.filter { name -> manifest.tables[name] == null }.sorted()
        dropTables.forEach { table ->
            changes += SchemaChange("drop_table", table, "table is no longer declared", true)
        }

        manifest.tables.values.forEach { table ->
            val state = current.firstOrNull { it.name == table.name }
            if (state == null) {
                changes += SchemaChange("create_table", table.name, "${table.fields.size} field(s)", false)
                return@forEach
            }

            val existing = state.columns.associateBy { it.name }
            val declared = table.fields.associateBy { it.name }

            existing.keys
                .filter { it != PRIMARY_KEY && declared[it] == null }
                .sorted()
                .forEach { column ->
                    changes += SchemaChange("drop_column", "${table.name}.$column", "field is no longer declared", true)
                    rebuild += table.name
                }

            table.fields.forEach { field ->
                val column = existing[field.name]
                if (column == null) {
                    // SQLite cannot add a NOT NULL column without a usable default once rows exist,
                    // so a required field on a populated table is backfilled by rebuilding.
                    val backfill = field.required && state.rows > 0
                    changes += SchemaChange(
                        if (backfill) "backfill_column" else "add_column",
                        "${table.name}.${field.name}",
                        field.type.sqlType,
                        backfill,
                    )
                    if (backfill) rebuild += table.name
                } else {
                    val currentType = column.type.uppercase()
                    val wantedType = field.type.sqlType.uppercase()
                    if (currentType != wantedType) {
                        changes += SchemaChange(
                            "change_type",
                            "${table.name}.${field.name}",
                            "$currentType → $wantedType",
                            true,
                        )
                        rebuild += table.name
                    }
                    if (column.notNull && !field.required) {
                        changes += SchemaChange(
                            "relax_required",
                            "${table.name}.${field.name}",
                            "required → optional",
                            false,
                        )
                        rebuild += table.name
                    }
                    if (!column.notNull && field.required) {
                        val backfill = state.rows > 0
                        changes += SchemaChange(
                            "enforce_required",
                            "${table.name}.${field.name}",
                            if (backfill) "optional → required, nulls filled" else "optional → required",
                            backfill,
                        )
                        rebuild += table.name
                    }
                }
            }
        }

        return SchemaPlan(changes, rebuild, dropTables)
    }
}
