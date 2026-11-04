package dev.emwl.host.service

import dev.emwl.host.data.SiteRecord
import dev.emwl.host.domain.ContentHash
import dev.emwl.host.domain.DomainException
import dev.emwl.host.domain.Manifest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Site revisions: immutable copies of what a site served (manifest + public tree).
 *
 * Uploaded files and database rows are data, not code, so they belong to backups, not
 * revisions. A revision is keyed by the content digest, so redeploying identical content
 * reuses the existing revision instead of growing the archive.
 */
class SiteRevisionService(private val service: SiteService) {

    class Capture(
        val id: String,
        val contentHash: String,
        val created: Boolean,
        val files: Int,
        val bytes: Long,
        val createdAt: Long,
    )

    private val lock = Any()

    /** Runs [block] while holding the content lock that serializes deploys and rollbacks. */
    fun <T> contentLocked(block: () -> T): T = synchronized(lock) { block() }

    fun revisionsDir(site: SiteRecord): File =
        File(service.siteDirOf(site), "revisions").apply { mkdirs() }

    fun list(site: SiteRecord): JSONObject = synchronized(lock) {
        val items = JSONArray()
        entriesOf(readIndex(site))
            .sortedByDescending { sequenceOf(it) }
            .forEach { entry ->
                items.put(
                    JSONObject(entry.toString())
                        .put("current", entry.getString("id") == site.servedRevision)
                )
            }
        JSONObject().put("servedRevision", site.servedRevision ?: JSONObject.NULL).put("items", items)
    }

    /** Live content digest versus the recorded pointer: a mismatch means drift. */
    fun current(site: SiteRecord): JSONObject = synchronized(lock) {
        val tree = FileTrees.read(File(service.siteDirOf(site), "public"))
        val manifestBytes = site.manifestJson?.toByteArray(Charsets.UTF_8)
        val liveHash = manifestBytes?.let { ContentHash.compute(it, tree) }
        val entry = site.servedRevision?.let { id ->
            entriesOf(readIndex(site)).firstOrNull { it.getString("id") == id }
        }
        JSONObject()
            .put("servedRevision", site.servedRevision ?: JSONObject.NULL)
            .put("contentHash", liveHash ?: JSONObject.NULL)
            .put("known", entry != null)
            .put(
                "matchesServed",
                entry != null && liveHash != null && entry.getString("contentHash") == liveHash
            )
            .put("files", tree.size)
    }

    fun capture(site: SiteRecord, manifestBytes: ByteArray, sourceDir: File): Capture {
        synchronized(lock) {
            val tree = FileTrees.read(sourceDir)
            val hash = ContentHash.compute(manifestBytes, tree)
            val index = readIndex(site)
            entriesOf(index).firstOrNull { it.getString("contentHash") == hash }?.let { existing ->
                return Capture(
                    id = existing.getString("id"),
                    contentHash = hash,
                    created = false,
                    files = existing.getInt("files"),
                    bytes = existing.getLong("bytes"),
                    createdAt = existing.getLong("createdAt"),
                )
            }

            val sequence = index.next
            val id = "rev-$sequence"
            val createdAt = System.currentTimeMillis()
            val bytes = tree.values.sumOf { it.size.toLong() } + manifestBytes.size
            val dir = File(revisionsDir(site), id)
            dir.deleteRecursively()
            FileTrees.write(File(dir, "public"), tree)
            File(dir, "manifest.json").writeBytes(manifestBytes)

            index.items.put(
                JSONObject()
                    .put("id", id)
                    .put("contentHash", hash)
                    .put("createdAt", createdAt)
                    .put("files", tree.size)
                    .put("bytes", bytes)
            )
            index.next = sequence + 1
            writeIndex(site, index)
            prune(site, index)
            return Capture(id, hash, true, tree.size, bytes, createdAt)
        }
    }

    /** Removes a revision that was recorded for a deploy which never became visible. */
    fun discard(site: SiteRecord, capture: Capture) {
        if (!capture.created) return
        synchronized(lock) {
            val index = readIndex(site)
            val remaining = JSONArray()
            var removed = false
            for (position in 0 until index.items.length()) {
                val entry = index.items.getJSONObject(position)
                if (entry.getString("id") == capture.id) removed = true else remaining.put(entry)
            }
            if (!removed) return
            index.items = remaining
            writeIndex(site, index)
            File(revisionsDir(site), capture.id).deleteRecursively()
        }
    }

    /**
     * Rolls the site back to a stored revision. The manifest pointer is only written after
     * the content swap succeeded, so a failed rollback leaves the previous content served.
     */
    fun rollback(site: SiteRecord, revisionId: String, confirm: Boolean): JSONObject {
        if (!confirm) {
            throw DomainException("CONFIRM_REQUIRED", "rollback replaces the served content; pass confirm=true")
        }
        if (!revisionId.matches(REVISION_ID)) {
            throw DomainException("INVALID_ARGUMENT", "invalid revision id")
        }
        return synchronized(lock) {
            val entry = entriesOf(readIndex(site)).firstOrNull { it.getString("id") == revisionId }
                ?: throw DomainException("NOT_FOUND", "revision not found: $revisionId")

            val source = File(revisionsDir(site), revisionId)
            val manifestBytes = File(source, "manifest.json")
                .takeIf { it.isFile }
                ?.readBytes()
                ?: throw DomainException("REVISION_CORRUPT", "revision has no manifest")
            val tree = FileTrees.read(File(source, "public"))
            if (ContentHash.compute(manifestBytes, tree) != entry.getString("contentHash")) {
                throw DomainException("REVISION_CORRUPT", "revision content no longer matches its digest")
            }
            val manifest = Manifest.parse(String(manifestBytes, Charsets.UTF_8))

            val public = File(service.siteDirOf(site), "public")
            val staged = File(
                public.parentFile,
                "public.stage-${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString().take(8)}",
            )
            FileTrees.write(staged, tree)

            var backup: File? = null
            try {
                backup = FileTrees.install(staged, public)
                val schema = service.database(site).applySchema(manifest)
                val updated = site.copy(
                    manifestJson = String(manifestBytes, Charsets.UTF_8),
                    servedRevision = revisionId,
                    updatedAt = System.currentTimeMillis(),
                )
                try {
                    service.repository().update(updated)
                } catch (t: Throwable) {
                    FileTrees.revert(public, backup)
                    backup = null
                    throw t
                }
                backup?.deleteRecursively()
                backup = null
                service.audit("revision_rollback", updated, revisionId)
                JSONObject()
                    .put("rolledBack", true)
                    .put("revision", revisionId)
                    .put("contentHash", entry.getString("contentHash"))
                    .put("files", tree.size)
                    .put("schema", schema)
                    .put("site", service.siteJson(updated))
            } finally {
                staged.deleteRecursively()
                backup?.deleteRecursively()
            }
        }
    }

    /**
     * A process death between the two rename steps of a swap can leave the live tree missing.
     * Restoring the newest backup keeps the site serving instead of silently emptying it.
     */
    fun recover(site: SiteRecord) = synchronized(lock) {
        val parent = service.siteDirOf(site)
        val public = File(parent, "public")
        val leftovers = parent.listFiles {
            file -> file.isDirectory && (file.name.startsWith("public.previous-") || file.name.startsWith("public.stage-"))
        } ?: emptyArray()

        val backups = leftovers.filter { it.name.startsWith("public.previous-") }
        if (!public.exists() && backups.isNotEmpty()) {
            backups.maxByOrNull { it.name }?.renameTo(public)
            service.audit("content_recovered", site, "restored an interrupted content swap")
        }
        // A staging directory younger than this may belong to a deploy that is still running.
        val staleBefore = System.currentTimeMillis() - STALE_LEFTOVER_MS
        leftovers.filter { it.lastModified() < staleBefore }.forEach { it.deleteRecursively() }
    }

    private class Index {
        var next: Int = 1
        var items: JSONArray = JSONArray()
    }

    private fun entriesOf(index: Index): List<JSONObject> = buildList {
        for (position in 0 until index.items.length()) add(index.items.getJSONObject(position))
    }

    private fun sequenceOf(entry: JSONObject): Int =
        entry.getString("id").substringAfter('-').toIntOrNull() ?: 0

    private fun indexFile(site: SiteRecord): File = File(revisionsDir(site), "index.json")

    private fun readIndex(site: SiteRecord): Index {
        val index = Index()
        val file = indexFile(site)
        if (!file.isFile) return index
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return index
        index.next = json.optInt("next", 1).coerceAtLeast(1)
        index.items = json.optJSONArray("items") ?: JSONArray()
        return index
    }

    private fun writeIndex(site: SiteRecord, index: Index) {
        val file = indexFile(site)
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(JSONObject().put("next", index.next).put("items", index.items).toString())
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) {
                throw DomainException("REVISION_FAILED", "cannot persist the revision index")
            }
        }
    }

    private fun prune(site: SiteRecord, index: Index) {
        val ordered = entriesOf(index).sortedBy { sequenceOf(it) }
        if (ordered.size <= RETAIN) return

        val dropped = ordered.take(ordered.size - RETAIN)
            .filter { it.getString("id") != site.servedRevision }
        if (dropped.isEmpty()) return

        val kept = JSONArray()
        val droppedIds = dropped.map { it.getString("id") }.toSet()
        for (position in 0 until index.items.length()) {
            val entry = index.items.getJSONObject(position)
            if (entry.getString("id") in droppedIds) {
                File(revisionsDir(site), entry.getString("id")).deleteRecursively()
            } else {
                kept.put(entry)
            }
        }
        index.items = kept
        writeIndex(site, index)
    }

    companion object {
        const val RETAIN = 20
        const val STALE_LEFTOVER_MS = 10L * 60 * 1000
        val REVISION_ID = Regex("^rev-[0-9]+$")
    }
}
