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
import dev.emwl.host.domain.ColumnState
import dev.emwl.host.domain.Manifest
import dev.emwl.host.domain.QueryFilters
import dev.emwl.host.domain.SchemaDiff
import dev.emwl.host.domain.SchemaPlan
import dev.emwl.host.domain.TableDef
import dev.emwl.host.domain.TableState
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

    @Volatile private var maintenance = false
    private var inserts = 0

    init {
        val dir = File(context.noBackupFilesDir, "sites").apply { mkdirs() }
        db = SQLiteDatabase.openOrCreateDatabase(File(dir, "$siteId.db"), null)
        db.enableWriteAheadLogging()
        ensureRequestTable()
    }

    fun file(): File = File(db.path)

    fun isMaintenance(): Boolean = maintenance

    private fun requireWritable() {
        if (maintenance) {
            throw DomainException("MAINTENANCE", "site is being backed up; retry shortly")
        }
    }

    /**
     * WAL-safe snapshot for backups. `VACUUM INTO` is used when the engine supports it;
     * otherwise writes are quiesced, the WAL is truncated, and the main file is copied.
     * A checkpoint that reports busy is treated as failure, never as success.
     */
    fun snapshotTo(target: File): JSONObject {
        target.parentFile?.mkdirs()
        target.delete()
        val result = JSONObject()

        try {
            db.execSQL("VACUUM INTO '${target.absolutePath.replace("'", "''")}'")
            result.put("method", "vacuum_into")
        } catch (t: Throwable) {
            result.put("vacuumIntoError", t.message ?: t.javaClass.simpleName)
            synchronized(this) {
                maintenance = true
                try {
                    db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                        if (cursor.moveToFirst()) {
                            val busy = cursor.getInt(0)
                            result.put("checkpointBusy", busy)
                            result.put("checkpointFrames", cursor.getInt(1))
                            result.put("checkpointDone", cursor.getInt(2))
                            if (busy != 0) {
                                throw DomainException("BACKUP_BUSY", "WAL checkpoint did not complete")
                            }
                        }
                    }
                    File(db.path).copyTo(target, overwrite = true)
                } finally {
                    maintenance = false
                }
            }
            result.put("method", "quiesce_copy")
        }

        try {
            SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY).use { copy ->
                val quick = copy.rawQuery("PRAGMA quick_check", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else "no-result"
                }
                result.put("quickCheck", quick)
                result.put("verified", quick.equals("ok", ignoreCase = true))
            }
        } catch (t: Throwable) {
            result.put("verified", false)
            result.put("verifyError", t.message ?: t.javaClass.simpleName)
        }
        return result
    }

    fun schemaPlan(manifest: Manifest): SchemaPlan = SchemaDiff.plan(tableStates(), manifest)

    /**
     * Migrates the database toward [manifest]. Additive changes apply silently; anything that
     * drops or rewrites stored data is refused unless the caller confirms it, and the whole
     * migration runs in one transaction so a failure leaves the previous schema intact.
     */
    fun applySchema(manifest: Manifest, allowDestructive: Boolean = false): JSONObject {
        ensureFileTable()
        ensureRequestTable()

        val plan = schemaPlan(manifest)
        if (plan.destructive.isNotEmpty() && !allowDestructive) {
            throw DomainException(
                "SCHEMA_DESTRUCTIVE",
                "destructive schema changes require confirmDestructive=true: ${plan.summary()}"
            )
        }

        val created = JSONArray()
        val altered = JSONArray()
        val rebuilt = JSONArray()
        val dropped = JSONArray()
        val indexes = JSONArray()
        val now = System.currentTimeMillis()
        val states = tableStates().associateBy { it.name }

        db.beginTransaction()
        try {
            plan.dropTables.forEach { name ->
                db.execSQL("DROP TABLE IF EXISTS \"$name\"")
                dropped.put(name)
            }

            manifest.tables.values.forEach { table ->
                val state = states[table.name]
                when {
                    state == null -> {
                        db.execSQL(createTableSql(table))
                        created.put(table.name)
                    }
                    table.name in plan.rebuild -> {
                        rebuildTable(table, state, now)
                        rebuilt.put(table.name)
                    }
                    else -> {
                        val existing = state.columns.map { it.name }.toSet()
                        table.fields.filter { it.name !in existing }.forEach { field ->
                            db.execSQL("ALTER TABLE \"${table.name}\" ADD COLUMN ${columnSql(field)}")
                            altered.put("${table.name}.${field.name}")
                        }
                    }
                }
            }

            manifest.tables.values.forEach { table ->
                table.indexes.forEach { columns ->
                    val name = "idx_${table.name}_${columns.joinToString("_")}"
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS \"$name\" ON \"${table.name}\" (${columns.joinToString(", ") { "\"$it\"" }})"
                    )
                    indexes.put(name)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return JSONObject()
            .put("created", created)
            .put("altered", altered)
            .put("rebuilt", rebuilt)
            .put("dropped", dropped)
            .put("indexes", indexes)
            .put("destructive", plan.toJson().getJSONArray("destructive"))
    }

    private fun tableStates(): List<TableState> {
        val names = ArrayList<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                if (name !in INTERNAL_TABLES) names += name
            }
        }
        return names.sorted().map { name ->
            val columns = ArrayList<ColumnState>()
            db.rawQuery("PRAGMA table_info(\"$name\")", null).use { cursor ->
                while (cursor.moveToNext()) {
                    columns += ColumnState(
                        name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                        type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                        notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) != 0,
                    )
                }
            }
            val rows = db.rawQuery("SELECT COUNT(*) FROM \"$name\"", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
            TableState(name, columns, rows)
        }
    }

    /**
     * Rewrites one table in place: the rows keep their ids, declared columns are copied,
     * removed columns disappear, and new required columns are filled with their default.
     */
    private fun rebuildTable(table: TableDef, state: TableState, now: Long) {
        val temporary = "${table.name}__new"
        val existing = state.columns.map { it.name }.toSet()
        val previousSequence = sequenceOf(table.name)

        db.execSQL("DROP TABLE IF EXISTS \"$temporary\"")
        db.execSQL(createTableSql(table).replaceFirst("\"${table.name}\"", "\"$temporary\""))

        val targets = ArrayList<String>()
        val sources = ArrayList<String>()
        targets += "\"id\""
        sources += "\"id\""
        table.fields.forEach { field ->
            targets += "\"${field.name}\""
            sources += if (field.name in existing) "\"${field.name}\"" else backfillSql(field, now)
        }

        db.execSQL(
            "INSERT INTO \"$temporary\" (${targets.joinToString(", ")}) " +
                "SELECT ${sources.joinToString(", ")} FROM \"${table.name}\""
        )
        db.execSQL("DROP TABLE \"${table.name}\"")
        db.execSQL("ALTER TABLE \"$temporary\" RENAME TO \"${table.name}\"")
        restoreSequence(table.name, temporary, previousSequence)
    }

    private fun sequenceOf(table: String): Long {
        if (!sqliteSequenceExists()) return 0L
        return db.rawQuery("SELECT seq FROM sqlite_sequence WHERE name = ?", arrayOf(table)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    /** Keeps AUTOINCREMENT from handing out ids that were used before the rewrite. */
    private fun restoreSequence(table: String, temporary: String, previous: Long) {
        if (!sqliteSequenceExists()) return
        db.execSQL("DELETE FROM sqlite_sequence WHERE name IN (?, ?)", arrayOf(table, temporary))
        val maxId = db.rawQuery("SELECT COALESCE(MAX(id), 0) FROM \"$table\"", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
        val next = maxOf(previous, maxId)
        if (next > 0) {
            db.execSQL(
                "INSERT INTO sqlite_sequence(name, seq) VALUES (?, ?)",
                arrayOf(table, next),
            )
        }
    }

    private fun sqliteSequenceExists(): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'sqlite_sequence'", null)
            .use { it.moveToFirst() }

    private fun backfillSql(field: FieldDef, now: Long): String = when (field.type) {
        FieldType.INT, FieldType.BOOLEAN -> when (field.default?.lowercase()) {
            "true" -> "1"
            "false" -> "0"
            else -> (field.default?.toLongOrNull() ?: 0L).toString()
        }
        FieldType.REAL -> (field.default?.toDoubleOrNull() ?: 0.0).toString()
        FieldType.DATETIME -> {
            val parsed = field.default
                ?.takeIf { it != "now" }
                ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            (parsed ?: now).toString()
        }
        FieldType.TEXT, FieldType.JSON -> "'${(field.default ?: "").replace("'", "''")}'"
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
        requireWritable()
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
        requireWritable()
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

    fun delete(table: TableDef, id: Long): Int {
        requireWritable()
        return db.delete(table.name, "id = ?", arrayOf(id.toString()))
    }

    fun isMaintenanceMode(): Boolean = maintenance

    fun close() {
        runCatching { db.close() }
    }

    fun writableDb(): SQLiteDatabase = db

    fun ensureFileTable() {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS _files(
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                mime TEXT NOT NULL,
                size INTEGER NOT NULL,
                sha256 TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    fun ensureRequestTable() {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS _requests(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                at INTEGER NOT NULL,
                method TEXT NOT NULL,
                path TEXT NOT NULL,
                status INTEGER NOT NULL,
                ms INTEGER NOT NULL,
                ip TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_requests_at ON _requests(at)")
    }

    /**
     * Request logging is best effort: it never fails a request, and it stays out of the way
     * while a backup has quiesced the database so a checkpoint cannot report busy.
     */
    fun recordRequest(method: String, path: String, status: Int, ms: Int, ip: String?) {
        if (maintenance) return
        try {
            db.execSQL(
                "INSERT INTO _requests(at, method, path, status, ms, ip) VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf(System.currentTimeMillis(), method, path.take(MAX_PATH_CHARS), status, ms, ip),
            )
            inserts += 1
            if (inserts % TRIM_EVERY == 0) {
                db.execSQL("DELETE FROM _requests WHERE id <= (SELECT COALESCE(MAX(id), 0) FROM _requests) - $MAX_REQUESTS")
            }
        } catch (_: Throwable) {
            // A failed log line must never turn into a failed request.
        }
    }

    fun listRequests(limit: Int, offset: Int, status: String?, path: String?): JSONObject {
        val where = ArrayList<String>()
        val args = ArrayList<String>()

        status?.takeIf { it.isNotEmpty() }?.let { filter ->
            when {
                filter.matches(STATUS_CODE) -> {
                    where += "status = ?"
                    args += filter
                }
                filter.matches(STATUS_CLASS) -> {
                    val hundred = filter.first().digitToInt() * 100
                    where += "status BETWEEN ? AND ?"
                    args += hundred.toString()
                    args += (hundred + 99).toString()
                }
                else -> throw DomainException("INVALID_ARGUMENT", "status must be a code like 404 or a class like 4xx")
            }
        }
        path?.takeIf { it.isNotEmpty() }?.let { filter ->
            where += "path LIKE ? ESCAPE '\\'"
            args += "%${QueryFilters.escapeLike(filter)}%"
        }

        val clause = if (where.isEmpty()) "" else " WHERE ${where.joinToString(" AND ")}"
        val items = JSONArray()
        db.rawQuery(
            "SELECT * FROM _requests$clause ORDER BY id DESC LIMIT ? OFFSET ?",
            (args + limit.toString() + offset.toString()).toTypedArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                items.put(
                    JSONObject()
                        .put("id", cursor.getLong(cursor.getColumnIndexOrThrow("id")))
                        .put("at", cursor.getLong(cursor.getColumnIndexOrThrow("at")))
                        .put("method", cursor.getString(cursor.getColumnIndexOrThrow("method")))
                        .put("path", cursor.getString(cursor.getColumnIndexOrThrow("path")))
                        .put("status", cursor.getInt(cursor.getColumnIndexOrThrow("status")))
                        .put("ms", cursor.getInt(cursor.getColumnIndexOrThrow("ms")))
                        .put(
                            "ip",
                            cursor.getColumnIndexOrThrow("ip").let { index ->
                                if (cursor.isNull(index)) JSONObject.NULL else cursor.getString(index)
                            }
                        )
                )
            }
        }

        val total = db.rawQuery("SELECT COUNT(*) FROM _requests$clause", args.toTypedArray()).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

        return JSONObject()
            .put("items", items)
            .put("total", total)
            .put("retained", MAX_REQUESTS)
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
            FieldType.INT -> when (raw) {
                is Number -> raw.toLong()
                is String -> raw.toLongOrNull()
                    ?: throw DomainException("INVALID_VALUE", "${field.name} must be an integer")
                else -> throw DomainException("INVALID_VALUE", "${field.name} must be an integer")
            }
            FieldType.REAL -> when (raw) {
                is Number -> raw.toDouble()
                is String -> raw.toDoubleOrNull()
                    ?: throw DomainException("INVALID_VALUE", "${field.name} must be a number")
                else -> throw DomainException("INVALID_VALUE", "${field.name} must be a number")
            }
            FieldType.BOOLEAN -> when (raw) {
                is Boolean -> if (raw) 1L else 0L
                is Number -> if (raw.toInt() != 0) 1L else 0L
                is String -> when (raw.lowercase()) {
                    "true", "1" -> 1L
                    "false", "0" -> 0L
                    else -> throw DomainException("INVALID_VALUE", "${field.name} must be a boolean")
                }
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

    companion object {
        const val MAX_REQUESTS = 1000
        const val TRIM_EVERY = 64
        const val MAX_PATH_CHARS = 512
        private val STATUS_CODE = Regex("^[0-9]{3}$")
        private val STATUS_CLASS = Regex("^[1-5]xx$")
        private val INTERNAL_TABLES = setOf("_files", "_requests", "android_metadata", "sqlite_sequence")
    }
}
