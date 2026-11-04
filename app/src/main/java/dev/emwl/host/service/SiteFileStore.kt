package dev.emwl.host.service

import android.content.Context
import dev.emwl.host.data.FileRecord
import dev.emwl.host.data.SiteFileIndex
import dev.emwl.host.data.SiteRecord
import dev.emwl.host.domain.DomainException
import dev.emwl.host.domain.FilePolicy
import dev.emwl.host.domain.Tokens
import java.io.File
import java.security.MessageDigest

/**
 * Per-site file bytes. Stored under a random identifier; the uploaded name is only
 * metadata. Content is sniffed and checked against the manifest policy before saving.
 */
class SiteFileStore(
    private val context: Context,
    private val site: SiteRecord,
    val index: SiteFileIndex,
) {

    fun dir(): File = File(context.noBackupFilesDir, "sites/${site.id}/files").apply { mkdirs() }

    fun fileOf(id: String): File = File(dir(), id)

    fun save(originalName: String, bytes: ByteArray, policy: FilePolicy): FileRecord {
        val safeName = sanitizeName(originalName)
        if (bytes.size > policy.maxBytes) {
            throw DomainException("FILE_TOO_LARGE", "file exceeds ${policy.maxBytes} bytes")
        }
        val used = index.usedBytes()
        if (used + bytes.size > QUOTA_BYTES) {
            throw DomainException("QUOTA_EXCEEDED", "site file quota of $QUOTA_BYTES bytes reached")
        }

        val mime = MimeTypes.detect(safeName, bytes)
        if (MimeTypes.isExecutable(safeName, mime)) {
            throw DomainException("UNSUPPORTED_MEDIA_TYPE", "executable files are not accepted")
        }
        if (policy.allow.isNotEmpty() && policy.allow.none { mime.startsWith(it) }) {
            throw DomainException("UNSUPPORTED_MEDIA_TYPE", "mime type not allowed by manifest: $mime")
        }

        val id = Tokens.generate(18)
        val target = fileOf(id)
        try {
            target.writeBytes(bytes)
        } catch (t: Throwable) {
            runCatching { target.delete() }
            throw DomainException("STORAGE_FAILED", "could not store file")
        }

        val record = FileRecord(
            id = id,
            name = safeName,
            mime = mime,
            size = bytes.size.toLong(),
            sha256 = sha256(bytes),
            createdAt = System.currentTimeMillis(),
        )
        try {
            index.insert(record)
        } catch (t: Throwable) {
            runCatching { target.delete() }
            throw DomainException("STORAGE_FAILED", "could not index file")
        }
        return record
    }

    fun read(id: String): Pair<FileRecord, ByteArray>? {
        val record = index.find(id) ?: return null
        val file = fileOf(id)
        if (!file.isFile) return null
        return record to file.readBytes()
    }

    fun delete(id: String): Boolean {
        val removed = index.delete(id)
        runCatching { fileOf(id).delete() }
        return removed
    }

    fun usedBytes(): Long = index.usedBytes()

    private fun sanitizeName(name: String): String {
        val trimmed = name.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = trimmed.filter { it.code in 32..126 && it != '"' }.take(120)
        return cleaned.ifEmpty { "upload.bin" }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val QUOTA_BYTES = 100L * 1024 * 1024
    }
}

object MimeTypes {

    private val BLOCKED_EXTENSIONS = setOf(
        "exe", "dll", "so", "dex", "apk", "jar", "class", "bat", "cmd", "com", "scr",
        "msi", "sh", "ps1", "vbs", "js2", "scr", "lnk", "app", "dmg",
    )

    fun isExecutable(name: String, mime: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension in BLOCKED_EXTENSIONS) return true
        return mime in setOf(
            "application/x-msdownload",
            "application/x-elf",
            "application/vnd.android.package-archive",
            "application/x-sharedlib",
            "application/java-archive",
        )
    }

    fun detect(name: String, bytes: ByteArray): String {
        sniff(bytes)?.let { return it }
        return when (name.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js", "mjs" -> "text/javascript"
            "json" -> "application/json"
            "txt", "md" -> "text/plain"
            "svg" -> "image/svg+xml"
            "csv" -> "text/csv"
            else -> if (looksLikeText(bytes)) "text/plain" else "application/octet-stream"
        }
    }

    private fun sniff(bytes: ByteArray): String? {
        fun at(index: Int, vararg expected: Int): Boolean =
            bytes.size > index + expected.size - 1 && expected.withIndex().all { (offset, value) ->
                bytes[index + offset].toInt() and 0xFF == value
            }

        return when {
            at(0, 0x89, 0x50, 0x4E, 0x47) -> "image/png"
            at(0, 0xFF, 0xD8, 0xFF) -> "image/jpeg"
            at(0, 0x47, 0x49, 0x46, 0x38) -> "image/gif"
            at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50) -> "image/webp"
            at(0, 0x25, 0x50, 0x44, 0x46) -> "application/pdf"
            at(0, 0x50, 0x4B, 0x03, 0x04) -> "application/zip"
            else -> null
        }
    }

    private fun looksLikeText(bytes: ByteArray): Boolean {
        val sample = bytes.take(1024)
        if (sample.isEmpty()) return true
        if (sample.any { it == 0.toByte() }) return false
        return sample.all { it.toInt() and 0xFF in 9..13 || it.toInt() and 0xFF in 32..126 }
    }
}
