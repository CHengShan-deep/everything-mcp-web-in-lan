package dev.emwl.host.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import dev.emwl.host.domain.DomainException
import dev.emwl.host.domain.FieldDef
import dev.emwl.host.domain.FieldType
import dev.emwl.host.domain.QueryFilters.Filter
import dev.emwl.host.domain.Manifest
import dev.emwl.host.domain.QueryFilters
import dev.emwl.host.domain.TableDef
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * One SQLite database per site. Only tables declared in the site manifest exist;
 * every value written here passes type, length and unknown-field validation first.
 */
class SiteDatabase(context: Context, siteId: String) {

    private val db: SQLiteDatabase

    init {
        val dir = File(context.noBackupFilesDir, "sites").apply { mkdirs() }
        db = SQLiteDatabase.openOrCreateDatabase(File(dir, "$siteId.db"), null)
        db.enableWriteAheadLogging()
    }

    fun applySchema(manifest: Manifest): JSONObject {
        val created = JSONArray()
        val altered = JSONArray()
        val indexes = JSONArray()

        manifest.tables.values.forEach { table ->
            val existing = columnsOf(table.name)
            if (existing.isEmpty()) {
                db.execSQL(createTableSql(table))
                created.put(table.name)
            } else {
                table.fields.forEach { field ->
                    if (field.name !in existing) {
                        db.execSQL("ALTER TABLE \"${table.name}\" ADD COLUMN ${columnSql(field)}")
                        altered.put("${table.name}.${field.name}")
                    }
                }
            }
            table.indexes.forEach { columns ->
                val name = "idx_${table.name}_${columns.joinToString("_")}"
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS \"$name\" ON \"${table.name}\" (${columns.joinToString(", ") { "\"$it\"" }})"
                )
                indexes.put(name)
            }
        }

        return JSONObject()
            .put("created", created)
            .put("altered", altered)
            .put("indexes", indexes)
    }

    fun list(
        table: TableDef,
        filters: List<Filter>,
        order: Pair<String, String>?,
        limit: Int,
        offset: Int,
        countTotal: Boolean,
    ): JSONObject {
        val where = StringBuilder()
        val args = ArrayList<String>()
        filters.forEach { filter ->
            val (sql, value) = QueryFilters.sqlFor(filter)
            if (where.isEmpty()) where.append(" WHERE ") else where.append(" AND ")
            where.append(sql)
            args += value.toString()
        }

        val sql = buildString {
            append("SELECT * FROM \"${table.name}\"")
            append(where)
            order?.let { append(" ORDER BY \"${it.first}\" ${it.second}") }
            append(" LIMIT ? OFFSET ?")
        }
        args += limit.toString()
        args += offset.toString()

        val items = JSONArray()
        db.rawQuery(sql, args.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) items.put(rowToJson(cursor, table))
        }

        val result = JSONObject()
            .put("items", items)
            .put("limit", limit)
            .put("offset", offset)

        if (countTotal) {
            val countSql = "SELECT COUNT(*) FROM \"${table.name}\"$where"
            val countArgs = args.take(filters.size).toTypedArray()
            db.rawQuery(countSql, countArgs).use { cursor ->
                if (cursor.moveToFirst()) result.put("total", cursor.getLong(0))
            }
        }
        return result
    }

    fun get(table: TableDef, id: Long): JSONObject? =
        db.rawQuery("SELECT * FROM \"${table.name}\" WHERE id = ?", arrayOf(id.toString())).use { cursor ->
            if (cursor.moveToFirst()) rowToJson(cursor, table) else null
        }

    fun insert(table: TableDef, values: JSONObject, now: Long = System.currentTimeMillis()): Long {
        val row = encodeRow(table, values, isInsert = true, now = now)
        if (table.fields.any { it.required && it.name !in row }) {
            val missing = table.fields.filter { it.required && it.name !in row }.map { it.name }
            throw DomainException("INVALID_VALUE", "missing required field(s): ${missing.joinToString()}")
        }
        return try {
            db.insertOrThrow(table.name, null, row.toContentValues())
        } catch (t: SQLiteConstraintException) {
            throw DomainException("CONFLICT", "constraint failed: ${t.message}")
        }
    }

    fun update(table: TableDef, id: Long, values: JSONObject, now: Long = System.currentTimeMillis()): Int {
        val row = encodeRow(table, values, isInsert = false, now = now)
        if (row.isEmpty()) {
            throw DomainException("INVALID_VALUE", "no writable fields in request body")
        }
        return try {
            db.update(table.name, row.toContentValues(), "id = ?", arrayOf(id.toString()))
        } catch (t: SQLiteConstraintException) {
            throw DomainException("CONFLICT", "constraint failed: ${t.message}")
        }
    }

    fun delete(table: TableDef, id: Long): Int =
        db.delete(table.name, "id = ?", arrayOf(id.toString()))

    fun close() {
        runCatching { db.close() }
    }

    private fun encodeRow(table: TableDef, values: JSONObject, isInsert: Boolean, now: Long): Map<String, Any?> {
        val fields = table.fields.associateBy { it.name }
        val encoded = LinkedHashMap<String, Any?>()

        for (name in values.keys()) {
            if (name == "id") continue
            val field = fields[name]
                ?: throw DomainException("UNKNOWN_FIELD", "unknown field: $name")
            encoded[field.name] = encodeValue(field, values.get(name), now)
        }

        if (isInsert) {
            table.fields.forEach { field ->
                if (field.name in encoded) return@forEach
                when (field.default) {
                    "now" -> encoded[field.name] = now
                    null -> Unit
                    else -> encoded[field.name] = encodeValue(field, field.default, now)
                }
            }
        }
        return encoded
    }

    private fun encodeValue(field: FieldDef, raw: Any?, now: Long): Any? {
        if (raw == null || raw == JSONObject.NULL) return null
        return when (field.type) {
            FieldType.TEXT -> {
                val text = raw.toString()
                val max = field.maxLength
                if (max != null && text.length > max) {
                    throw DomainException("INVALID_VALUE", "${field.name} exceeds maxLength $max")
                }
                text
            }
            FieldType.INT -> (raw as? Number)?.toLong()
                ?: throw DomainException("INVALID_VALUE", "${field.name} must be an integer")
            FieldType.REAL -> (raw as? Number)?.toDouble()
                ?: throw DomainException("INVALID_VALUE", "${field.name} must be a number")
            FieldType.BOOLEAN -> when (raw) {
                is Boolean -> if (raw) 1L else 0L
                is Number -> if (raw.toInt() != 0) 1L else 0L
                else -> throw DomainException("INVALID_VALUE", "${field.name} must be a boolean")
            }
            FieldType.DATETIME -> when (raw) {
                is Number -> raw.toLong()
                "now" -> now
                is String -> try {
                    Instant.parse(raw).toEpochMilli()
                } catch (_: DateTimeParseException) {
                    throw DomainException("INVALID_VALUE", "${field.name} must be an ISO-8601 UTC instant")
                }
                else -> throw DomainException("INVALID_VALUE", "${field.name} must be an ISO-8601 UTC instant")
            }
            FieldType.JSON -> when (raw) {
                is JSONObject, is JSONArray -> raw.toString()
                is String -> {
                    runCatching { JSONObject(raw) }.recoverCatching { JSONArray(raw) }.getOrElse {
                        throw DomainException("INVALID_VALUE", "${field.name} must be valid JSON")
                    }
                    raw
                }
                else -> raw.toString()
            }
        }
    }

    private fun rowToJson(cursor: Cursor, table: TableDef): JSONObject {
        val fields = table.fields.associateBy { it.name }
        val row = JSONObject()
        row.put("id", cursor.getLong(cursor.getColumnIndexOrThrow("id")))
        table.fields.forEach { field ->
            val index = cursor.getColumnIndex(field.name)
            if (index < 0 || cursor.isNull(index)) {
                row.put(field.name, JSONObject.NULL)
                return@forEach
            }
            row.put(
                field.name,
                when (field.type) {
                    FieldType.TEXT, FieldType.JSON -> cursor.getString(index)
                    FieldType.INT -> cursor.getLong(index)
                    FieldType.REAL -> cursor.getDouble(index)
                    FieldType.BOOLEAN -> cursor.getInt(index) != 0
                    FieldType.DATETIME -> Instant.ofEpochMilli(cursor.getLong(index)).toString()
                }
            )
        }
        return row
    }

    private fun columnsOf(table: String): Set<String> {
        val columns = HashSet<String>()
        db.rawQuery("PRAGMA table_info(\"$table\")", null).use { cursor ->
            while (cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
        }
        return columns
    }

    private fun createTableSql(table: TableDef): String = buildString {
        append("CREATE TABLE \"${table.name}\" (id INTEGER PRIMARY KEY AUTOINCREMENT")
        table.fields.forEach { field ->
            append(", ")
            append(columnSql(field))
        }
        append(")")
    }

    private fun columnSql(field: FieldDef): String = buildString {
        append("\"${field.name}\" ${field.type.sqlType}")
        if (field.required) append(" NOT NULL")
        if (field.type == FieldType.BOOLEAN && field.default != null) {
            append(" DEFAULT ${if (field.default == "true") 1 else 0}")
        }
    }

    private fun Map<String, Any?>.toContentValues(): ContentValues =
        ContentValues().apply {
            forEach { (name, value) ->
                when (value) {
                    null -> putNull(name)
                    is Long -> put(name, value)
                    is Int -> put(name, value)
                    is Double -> put(name, value)
                    is Boolean -> put(name, if (value) 1 else 0)
                    else -> put(name, value.toString())
                }
            }
        }
}
