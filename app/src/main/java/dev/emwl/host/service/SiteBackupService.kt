package dev.emwl.host.service

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dev.emwl.host.data.FileRecord
import dev.emwl.host.data.SiteRecord
import dev.emwl.host.domain.DomainException
import dev.emwl.host.domain.Manifest
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Site backups: manifest + public files + uploaded files + a WAL-safe database snapshot,
 * packed into one archive. Restores validate every entry before touching live data.
 */
class SiteBackupService(private val context: Context, private val service: SiteService) {

    private val lock = Any()

    fun backupDir(site: SiteRecord): File =
        File(service.siteDirOf(site), "backups").apply { mkdirs() }

    fun list(site: SiteRecord): JSONArray = JSONArray().apply {
        backupDir(site).listFiles { file -> file.isFile && file.name.endsWith(".zip") }
            ?.sortedByDescending { it.name }
            ?.forEach { file ->
                put(
                    JSONObject()
                        .put("id", file.name.removeSuffix(".zip"))
                        .put("bytes", file.length())
                        .put("createdAt", file.lastModified())
                )
            }
    }

    fun create(site: SiteRecord): JSONObject = synchronized(lock) {
        val stamp = System.currentTimeMillis()
        val id = "backup-$stamp"
        val dir = backupDir(site)
        val zipFile = File(dir, "$id.zip")
        val dbSnapshot = File(dir, "$id.db.tmp")

        val snapshot = service.database(site).snapshotTo(dbSnapshot)
        if (snapshot.optBoolean("verified") != true) {
            dbSnapshot.delete()
            throw DomainException("BACKUP_FAILED", "database snapshot failed verification")
        }

        try {
            val meta = JSONObject()
                .put("schemaVersion", 1)
                .put("slug", site.slug)
                .put("name", site.name)
                .put("createdAt", stamp)
                .put("snapshot", snapshot)

            ZipOutputStream(BufferedOutputStream(zipFile.outputStream())).use { zip ->
                addEntry(zip, "backup.json", meta.toString().toByteArray())
                site.manifestJson?.let { addEntry(zip, "manifest.json", it.toByteArray()) }
                addEntry(zip, "data.db", dbSnapshot.readBytes())
                addEntry(zip, "files.json", service.fileStore(site).index.list().toString().toByteArray())
                addTree(zip, "public", File(service.siteDirOf(site), "public"))
                addTree(zip, "files", File(service.siteDirOf(site), "files"))
            }
        } finally {
            dbSnapshot.delete()
        }

        val record = JSONObject()
            .put("id", id)
            .put("bytes", zipFile.length())
            .put("sha256", sha256(zipFile))
            .put("createdAt", stamp)
            .put("snapshot", snapshot)
        prune(site)
        service.audit("backup_create", site, id)
        record
    }

    fun restore(site: SiteRecord, backupId: String, confirm: Boolean): JSONObject = synchronized(lock) {
        if (!confirm) {
            throw DomainException("CONFIRM_REQUIRED", "restore replaces site data; pass confirm=true")
        }
        if (!backupId.matches(Regex("^backup-[0-9]+$"))) {
            throw DomainException("INVALID_ARGUMENT", "invalid backup id")
        }
        val zipFile = File(backupDir(site), "$backupId.zip")
        if (!zipFile.isFile) throw DomainException("NOT_FOUND", "backup not found")

        val staging = File(context.cacheDir, "restore-${site.id}-${System.currentTimeMillis()}")
        try {
            staging.mkdirs()
            extractSafely(zipFile, staging)

            val snapshot = File(staging, "data.db")
            if (!snapshot.isFile) throw DomainException("BUNDLE_REJECTED", "backup has no database")
            verifyQuickCheck(snapshot)

            val manifest = File(staging, "manifest.json")
                .takeIf { it.isFile }
                ?.let { Manifest.parse(it.readText()) }

            service.stopListenerFor(site.id)
            service.closeDatabase(site.id)

            val dbFile = service.databaseFileOf(site)
            File("${dbFile.path}-wal").delete()
            File("${dbFile.path}-shm").delete()
            dbFile.parentFile?.mkdirs()
            snapshot.copyTo(dbFile, overwrite = true)

            val publicDir = File(service.siteDirOf(site), "public")
            publicDir.deleteRecursively()
            publicDir.mkdirs()
            copyTree(File(staging, "public"), publicDir)

            val filesDir = File(service.siteDirOf(site), "files")
            filesDir.deleteRecursively()
            filesDir.mkdirs()
            copyTree(File(staging, "files"), filesDir)

            val fresh = service.database(site)
            fresh.ensureFileTable()
            fresh.writableDb().delete("_files", null, null)

            var fileCount = 0
            val metaFile = File(staging, "files.json")
            if (metaFile.isFile) {
                val items = JSONArray(metaFile.readText())
                val index = service.fileStore(site).index
                for (position in 0 until items.length()) {
                    val item = items.getJSONObject(position)
                    index.insert(
                        FileRecord(
                            id = item.getString("id"),
                            name = item.getString("name"),
                            mime = item.getString("mime"),
                            size = item.getLong("size"),
                            sha256 = item.getString("sha256"),
                            createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                        )
                    )
                    fileCount += 1
                }
            }

            val updated = site.copy(
                manifestJson = manifest?.let { service.manifestJson(it) } ?: site.manifestJson,
                updatedAt = System.currentTimeMillis(),
            )
            service.repository().update(updated)
            service.startListener(updated)
            service.audit("backup_restore", updated, backupId)

            JSONObject()
                .put("restored", true)
                .put("backup", backupId)
                .put("tables", org.json.JSONArray(manifest?.tables?.keys?.toList() ?: emptyList<String>()))
                .put("files", fileCount)
                .put("site", service.siteJson(updated))
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun prune(site: SiteRecord) {
        val backups = backupDir(site).listFiles { file -> file.isFile && file.name.endsWith(".zip") }
            ?.sortedBy { it.name }
            ?: return
        if (backups.size <= RETAIN) return
        backups.dropLast(RETAIN).forEach { it.delete() }
    }

    private fun addEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name)
        entry.time = System.currentTimeMillis()
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun addTree(zip: ZipOutputStream, prefix: String, dir: File) {
        if (!dir.isDirectory) return
        dir.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(dir).invariantSeparatorsPath
            addEntry(zip, "$prefix/$relative", file.readBytes())
        }
    }

    private fun copyTree(from: File, to: File) {
        if (!from.isDirectory) return
        from.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(from).invariantSeparatorsPath
            val target = File(to, relative)
            target.parentFile?.mkdirs()
            file.copyTo(target, overwrite = true)
        }
    }

    private fun extractSafely(zip: File, destination: File) {
        var entries = 0
        var total = 0L
        ZipInputStream(BufferedInputStream(zip.inputStream())).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val name = entry.name
                if (!Manifest.isSafeRelativePath(name.removeSuffix("/"))) {
                    throw DomainException("BUNDLE_REJECTED", "unsafe archive entry: $name")
                }
                if (entry.isDirectory) {
                    File(destination, name).mkdirs()
                    continue
                }
                entries += 1
                if (entries > MAX_ENTRIES) {
                    throw DomainException("BUNDLE_REJECTED", "archive has too many entries")
                }
                val bytes = input.readBytes()
                if (bytes.size > MAX_ENTRY_BYTES) {
                    throw DomainException("BUNDLE_REJECTED", "archive entry too large: $name")
                }
                total += bytes.size
                if (total > MAX_TOTAL_BYTES) {
                    throw DomainException("BUNDLE_REJECTED", "archive is too large")
                }
                val target = File(destination, name)
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            }
        }
    }

    private fun verifyQuickCheck(file: File) {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            val result = database.rawQuery("PRAGMA quick_check", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else "no-result"
            }
            if (!result.equals("ok", ignoreCase = true)) {
                throw DomainException("BACKUP_CORRUPT", "snapshot failed quick_check: $result")
            }
        }
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    companion object {
        const val RETAIN = 20
        const val MAX_ENTRIES = 10_000
        const val MAX_ENTRY_BYTES = 100L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 500L * 1024 * 1024
    }
}
