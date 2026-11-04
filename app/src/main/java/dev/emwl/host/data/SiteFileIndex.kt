package dev.emwl.host.data

import android.content.ContentValues
import org.json.JSONArray
import org.json.JSONObject

data class FileRecord(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val sha256: String,
    val createdAt: Long,
)

/**
 * File metadata lives in the site database; bytes live on disk under a random name.
 * The original file name is metadata only and never becomes a path.
 */
class SiteFileIndex(private val db: SiteDatabase) {

    init {
        db.ensureFileTable()
    }

    fun insert(record: FileRecord) {
        val values = ContentValues().apply {
            put("id", record.id)
            put("name", record.name)
            put("mime", record.mime)
            put("size", record.size)
            put("sha256", record.sha256)
            put("created_at", record.createdAt)
        }
        db.writableDb().insertOrThrow("_files", null, values)
    }

    fun find(id: String): FileRecord? =
        db.writableDb().rawQuery("SELECT * FROM _files WHERE id = ?", arrayOf(id)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            FileRecord(
                id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                mime = cursor.getString(cursor.getColumnIndexOrThrow("mime")),
                size = cursor.getLong(cursor.getColumnIndexOrThrow("size")),
                sha256 = cursor.getString(cursor.getColumnIndexOrThrow("sha256")),
                createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
            )
        }

    fun list(): JSONArray = JSONArray().apply {
        db.writableDb().rawQuery("SELECT * FROM _files ORDER BY created_at DESC", null).use { cursor ->
            while (cursor.moveToNext()) {
                put(
                    JSONObject()
                        .put("id", cursor.getString(cursor.getColumnIndexOrThrow("id")))
                        .put("name", cursor.getString(cursor.getColumnIndexOrThrow("name")))
                        .put("mime", cursor.getString(cursor.getColumnIndexOrThrow("mime")))
                        .put("size", cursor.getLong(cursor.getColumnIndexOrThrow("size")))
                        .put("sha256", cursor.getString(cursor.getColumnIndexOrThrow("sha256")))
                        .put("createdAt", cursor.getLong(cursor.getColumnIndexOrThrow("created_at")))
                )
            }
        }
    }

    fun delete(id: String): Boolean =
        db.writableDb().delete("_files", "id = ?", arrayOf(id)) == 1

    fun usedBytes(): Long =
        db.writableDb().rawQuery("SELECT COALESCE(SUM(size), 0) FROM _files", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
}
