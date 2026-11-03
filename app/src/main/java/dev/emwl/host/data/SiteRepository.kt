package dev.emwl.host.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class SiteRecord(
    val id: String,
    val slug: String,
    val name: String,
    val port: Int,
    val exposure: String,
    val authRead: String,
    val authWrite: String,
    val readTokenHash: String?,
    val writeTokenHash: String?,
    val manifestJson: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val isLanExposed: Boolean get() = exposure == EXPOSURE_LAN

    companion object {
        const val EXPOSURE_LOCAL = "local"
        const val EXPOSURE_LAN = "lan"
    }
}

class SiteRepository(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE sites(
                id TEXT PRIMARY KEY NOT NULL,
                slug TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,
                port INTEGER NOT NULL UNIQUE,
                exposure TEXT NOT NULL DEFAULT 'local',
                auth_read TEXT NOT NULL DEFAULT 'none',
                auth_write TEXT NOT NULL DEFAULT 'token',
                read_token_hash TEXT,
                write_token_hash TEXT,
                manifest_json TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE port_cooldowns(
                port INTEGER PRIMARY KEY NOT NULL,
                released_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    /** Directed migrations: each version step is applied in order, never assumed. */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        var version = oldVersion
        while (version < newVersion) {
            when (version) {
                1 -> Unit
                else -> error("no migration from version $version")
            }
            version++
        }
    }

    fun list(): List<SiteRecord> =
        readableDatabase.rawQuery("SELECT * FROM sites ORDER BY created_at", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toRecord()) }
        }

    fun find(id: String): SiteRecord? =
        readableDatabase.rawQuery("SELECT * FROM sites WHERE id = ?", arrayOf(id)).use { cursor ->
            if (cursor.moveToFirst()) cursor.toRecord() else null
        }

    fun findBySlug(slug: String): SiteRecord? =
        readableDatabase.rawQuery("SELECT * FROM sites WHERE slug = ?", arrayOf(slug)).use { cursor ->
            if (cursor.moveToFirst()) cursor.toRecord() else null
        }

    fun insert(record: SiteRecord) {
        val values = ContentValues().apply {
            put("id", record.id)
            put("slug", record.slug)
            put("name", record.name)
            put("port", record.port)
            put("exposure", record.exposure)
            put("auth_read", record.authRead)
            put("auth_write", record.authWrite)
            put("read_token_hash", record.readTokenHash)
            put("write_token_hash", record.writeTokenHash)
            put("manifest_json", record.manifestJson)
            put("created_at", record.createdAt)
            put("updated_at", record.updatedAt)
        }
        writableDatabase.insertOrThrow("sites", null, values)
        writableDatabase.delete("port_cooldowns", "port = ?", arrayOf(record.port.toString()))
    }

    fun update(record: SiteRecord) {
        val values = ContentValues().apply {
            put("slug", record.slug)
            put("name", record.name)
            put("exposure", record.exposure)
            put("auth_read", record.authRead)
            put("auth_write", record.authWrite)
            put("read_token_hash", record.readTokenHash)
            put("write_token_hash", record.writeTokenHash)
            put("manifest_json", record.manifestJson)
            put("updated_at", record.updatedAt)
        }
        val rows = writableDatabase.update("sites", values, "id = ?", arrayOf(record.id))
        check(rows == 1) { "site update affected $rows rows" }
    }

    fun delete(id: String): Boolean {
        val record = find(id) ?: return false
        writableDatabase.beginTransaction()
        try {
            val rows = writableDatabase.delete("sites", "id = ?", arrayOf(id))
            if (rows != 1) {
                throw IllegalStateException("site delete affected $rows rows")
            }
            writableDatabase.insertWithOnConflict(
                "port_cooldowns",
                null,
                ContentValues().apply {
                    put("port", record.port)
                    put("released_at", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
            writableDatabase.setTransactionSuccessful()
            return true
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun allocatePort(now: Long = System.currentTimeMillis()): Int? {
        val used = HashSet<Int>()
        readableDatabase.rawQuery("SELECT port FROM sites", null).use { cursor ->
            while (cursor.moveToNext()) used += cursor.getInt(0)
        }
        val cooling = HashMap<Int, Long>()
        readableDatabase.rawQuery("SELECT port, released_at FROM port_cooldowns", null).use { cursor ->
            while (cursor.moveToNext()) cooling[cursor.getInt(0)] = cursor.getLong(1)
        }
        for (index in 0 until PORTS) {
            val port = PORT_BASE + index
            if (port in used) continue
            val releasedAt = cooling[port]
            if (releasedAt != null && now - releasedAt < COOLDOWN_MS) continue
            return port
        }
        return null
    }

    fun count(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM sites", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    private fun Cursor.toRecord(): SiteRecord = SiteRecord(
        id = getString(getColumnIndexOrThrow("id")),
        slug = getString(getColumnIndexOrThrow("slug")),
        name = getString(getColumnIndexOrThrow("name")),
        port = getInt(getColumnIndexOrThrow("port")),
        exposure = getString(getColumnIndexOrThrow("exposure")),
        authRead = getString(getColumnIndexOrThrow("auth_read")),
        authWrite = getString(getColumnIndexOrThrow("auth_write")),
        readTokenHash = getStringOrNull("read_token_hash"),
        writeTokenHash = getStringOrNull("write_token_hash"),
        manifestJson = getStringOrNull("manifest_json"),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
    )

    private fun Cursor.getStringOrNull(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    companion object {
        const val DATABASE_NAME = "registry.db"
        const val VERSION = 1
        const val PORT_BASE = 47200
        const val PORTS = 32
        const val COOLDOWN_MS = 7L * 24 * 60 * 60 * 1000
    }
}
