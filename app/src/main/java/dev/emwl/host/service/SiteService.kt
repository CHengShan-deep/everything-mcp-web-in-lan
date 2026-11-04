package dev.emwl.host.service

import android.content.Context
import dev.emwl.host.data.SiteDatabase
import dev.emwl.host.data.SiteFileIndex
import dev.emwl.host.data.SiteRecord
import dev.emwl.host.data.SiteRepository
import dev.emwl.host.domain.AuthPolicy
import dev.emwl.host.domain.DomainException
import dev.emwl.host.domain.Manifest
import dev.emwl.host.domain.Tokens
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class SiteService(private val context: Context) {

    private val repository = SiteRepository(context)
    private val databases = ConcurrentHashMap<String, SiteDatabase>()
    private val fileIndexes = ConcurrentHashMap<String, SiteFileIndex>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = ConcurrentHashMap<String, SiteListenerHandle>()
    /** Serializes listener start/stop so two starts cannot race for the same port. */
    private val listenerLock = Any()

    @Volatile var baseDomain: String = "lan"

    fun repository(): SiteRepository = repository

    fun listSites(): List<SiteRecord> = repository.list()

    fun getSite(id: String): SiteRecord? = repository.find(id)

    fun resolveSite(reference: String): SiteRecord =
        repository.find(reference)
            ?: repository.findBySlug(reference)
            ?: throw DomainException("SITE_NOT_FOUND", "site not found: $reference")

    fun manifest(site: SiteRecord): Manifest? =
        site.manifestJson?.let { runCatching { Manifest.parse(it) }.getOrNull() }

    fun database(site: SiteRecord): SiteDatabase =
        databases.getOrPut(site.id) { SiteDatabase(context, site.id) }

    fun fileStore(site: SiteRecord): SiteFileStore =
        SiteFileStore(context, site, fileIndexes.getOrPut(site.id) { SiteFileIndex(database(site)) })

    fun publicDir(site: SiteRecord): File =
        File(File(context.noBackupFilesDir, "sites/${site.id}"), "public").apply { mkdirs() }

    fun createSite(slug: String, name: String): JSONObject {
        dev.emwl.host.domain.Identifiers.requireSlug(slug)
        if (repository.findBySlug(slug) != null) {
            throw DomainException("SLUG_TAKEN", "slug is already used: $slug")
        }
        val port = repository.allocatePort()
            ?: throw DomainException("PORT_EXHAUSTED", "no free site port available")

        val now = System.currentTimeMillis()
        val writeToken = Tokens.generate()
        val record = SiteRecord(
            id = UUID.randomUUID().toString(),
            slug = slug,
            name = name,
            port = port,
            exposure = SiteRecord.EXPOSURE_LOCAL,
            authRead = "none",
            authWrite = "token",
            readTokenHash = null,
            writeTokenHash = Tokens.sha256Hex(writeToken),
            manifestJson = null,
            servedRevision = null,
            createdAt = now,
            updatedAt = now,
        )
        repository.insert(record)
        audit("site_create", record, "port=${record.port}")
        return JSONObject()
            .put("site", siteJson(record))
            .put("writeToken", writeToken)
    }

    fun deleteSite(id: String): Boolean {
        val site = repository.find(id) ?: return false
        stopListenerFor(id)
        databases.remove(id)?.close()
        fileIndexes.remove(id)
        val databaseFile = databaseFileOf(site)
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            File("${databaseFile.path}$suffix").delete()
        }
        databaseFile.delete()
        publicDir(site).parentFile?.deleteRecursively()
        val deleted = repository.delete(id)
        if (deleted) audit("site_delete", site, null)
        return deleted
    }

    fun renameSite(id: String, name: String): SiteRecord {
        val site = requireSite(id)
        val updated = site.copy(name = name, updatedAt = System.currentTimeMillis())
        repository.update(updated)
        audit("site_rename", updated, name)
        return updated
    }

    fun setExposure(id: String, lan: Boolean): SiteRecord {
        val site = requireSite(id)
        val updated = site.copy(
            exposure = if (lan) SiteRecord.EXPOSURE_LAN else SiteRecord.EXPOSURE_LOCAL,
            updatedAt = System.currentTimeMillis(),
        )
        repository.update(updated)
        restartListener(updated)
        audit("exposure_change", updated, updated.exposure)
        return updated
    }

    /**
     * Public writes are a deliberate downgrade: without an explicit confirmation the
     * change is refused so a site cannot drift into anonymous-writable by accident.
     */
    fun setAuth(
        id: String,
        read: String?,
        write: String?,
        confirmPublicWrite: Boolean = false,
    ): JSONObject {
        val site = requireSite(id)
        val policy = AuthPolicy.from(read ?: site.authRead, write ?: site.authWrite)

        val movingToPublicWrite = policy.write == "none" && site.authWrite != "none"
        if (movingToPublicWrite && !confirmPublicWrite) {
            throw DomainException(
                "PUBLIC_WRITE_CONFIRMATION_REQUIRED",
                "setting write auth to none makes the site publicly writable; pass confirmPublicWrite=true"
            )
        }

        var readToken: String? = null
        var readHash = site.readTokenHash
        if (policy.isReadProtected && readHash.isNullOrEmpty()) {
            readToken = Tokens.generate()
            readHash = Tokens.sha256Hex(readToken)
        } else if (!policy.isReadProtected) {
            readHash = null
        }

        val updated = site.copy(
            authRead = policy.read,
            authWrite = policy.write,
            readTokenHash = readHash,
            updatedAt = System.currentTimeMillis(),
        )
        repository.update(updated)
        audit("auth_change", updated, "read=${updated.authRead} write=${updated.authWrite}")
        val result = JSONObject().put("site", siteJson(updated))
        readToken?.let { result.put("readToken", it) }
        if (movingToPublicWrite) result.put("publicWrite", true)
        return result
    }

    fun rotateToken(id: String, which: String): JSONObject {
        val site = requireSite(id)
        val token = Tokens.generate()
        val updated = when (which) {
            "read" -> site.copy(readTokenHash = Tokens.sha256Hex(token), authRead = "token")
            "write" -> site.copy(writeTokenHash = Tokens.sha256Hex(token), authWrite = "token")
            else -> throw DomainException("INVALID_ARGUMENT", "which must be read or write")
        }.let { it.copy(updatedAt = System.currentTimeMillis()) }
        repository.update(updated)
        audit("token_rotate", updated, which)
        return JSONObject().put("which", which).put("token", token)
    }

    fun deploy(
        id: String,
        manifestJson: String,
        files: Map<String, String>,
        confirmDestructive: Boolean = false,
    ): JSONObject {
        val site = requireSite(id)
        val incoming = Manifest.parse(manifestJson)
        val storedManifest = manifestJson(incoming)

        // Refuse data-losing migrations before any content is staged or swapped.
        val database = database(site)
        val plan = database.schemaPlan(incoming)
        if (plan.destructive.isNotEmpty() && !confirmDestructive) {
            throw DomainException(
                "SCHEMA_DESTRUCTIVE",
                "destructive schema changes require confirmDestructive=true: ${plan.summary()}"
            )
        }

        val public = publicDir(site)
        val staging = File(
            public.parentFile,
            "public.stage-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}",
        )
        val written = ArrayList<String>()
        val rejected = ArrayList<String>()
        var backup: File? = null
        var capture: SiteRevisionService.Capture? = null

        try {
            // Build the next tree beside the live one; nothing is visible until the swap.
            FileTrees.copy(public, staging)

            files.forEach { (path, content) ->
                if (!Manifest.isSafeRelativePath(path)) {
                    rejected += path
                    return@forEach
                }
                val canonicalRoot = staging.canonicalFile
                val target = File(staging, path).canonicalFile
                if (!target.path.startsWith(canonicalRoot.path + File.separator)) {
                    rejected += path
                    return@forEach
                }
                target.parentFile?.mkdirs()
                target.writeText(content)
                written += path
            }

            // Capture, swap, migrate and record under the content lock so a concurrent
            // deploy or rollback cannot interleave with this one.
            val result = revisions().contentLocked {
                val captured = revisions().capture(site, storedManifest.toByteArray(Charsets.UTF_8), staging)
                capture = captured
                backup = FileTrees.install(staging, public)
                try {
                    val schemaResult = database.applySchema(incoming, confirmDestructive)
                    val updated = site.copy(
                        manifestJson = storedManifest,
                        servedRevision = captured.id,
                        updatedAt = System.currentTimeMillis(),
                    )
                    repository.update(updated)
                    backup?.deleteRecursively()
                    backup = null
                    audit(
                        "site_deploy",
                        updated,
                        "written=${written.size} rejected=${rejected.size} revision=${captured.id}",
                    )
                    Triple(updated, schemaResult, captured)
                } catch (t: Throwable) {
                    FileTrees.revert(public, backup)
                    backup = null
                    throw t
                }
            }

            return JSONObject()
                .put("site", siteJson(result.first))
                .put("writtenFiles", written.size)
                .put("rejectedFiles", rejected)
                .put("schema", result.second)
                .put(
                    "revision",
                    JSONObject()
                        .put("id", result.third.id)
                        .put("contentHash", result.third.contentHash)
                        .put("created", result.third.created)
                )
        } catch (t: Throwable) {
            capture?.let { revisions().discard(site, it) }
            throw t
        } finally {
            staging.deleteRecursively()
            backup?.deleteRecursively()
        }
    }

    val limiter = dev.emwl.host.domain.WriteLimiter()

    fun audit(action: String, site: SiteRecord?, detail: String?, actor: String = "local") {
        runCatching { repository.recordAudit(actor, action, site?.slug, detail) }
    }

    fun auditLog(siteSlug: String?, limit: Int = 50): org.json.JSONArray =
        org.json.JSONArray(repository.listAudit(siteSlug, limit))

    fun urls(site: SiteRecord, lanIp: String? = dev.emwl.host.Metrics.lanIpv4()): JSONObject {
        val local = "http://127.0.0.1:${site.port}"
        // A LAN address only exists once the site is actually exposed to the LAN.
        val lan = if (site.isLanExposed) lanIp?.let { "http://$it:${site.port}" } else null
        val named = "http://${site.slug}.$baseDomain"
        return JSONObject()
            .put("local", local)
            .put("lan", lan ?: JSONObject.NULL)
            .put("named", named)
            .put("exposure", site.exposure)
            .put("stable", site.exposure == SiteRecord.EXPOSURE_LAN)
            .put(
                "hint",
                "Named URLs need an internal DNS wildcard and, for the bare name on port 80, the accepted gateway."
            )
    }

    fun siteJson(site: SiteRecord): JSONObject = JSONObject()
        .put("id", site.id)
        .put("slug", site.slug)
        .put("name", site.name)
        .put("port", site.port)
        .put("exposure", site.exposure)
        .put("authRead", site.authRead)
        .put("authWrite", site.authWrite)
        .put("hasManifest", site.manifestJson != null)
        .put("servedRevision", site.servedRevision ?: JSONObject.NULL)
        .put("createdAt", site.createdAt)
        .put("updatedAt", site.updatedAt)

    fun requireSite(id: String): SiteRecord =
        repository.find(id) ?: throw DomainException("SITE_NOT_FOUND", "site not found: $id")

    fun registerListener(id: String, handle: SiteListenerHandle) {
        listeners.put(id, handle)?.stop()
    }

    fun startListener(site: SiteRecord) {
        val self = this
        scope.launch {
            synchronized(listenerLock) {
                runCatching {
                    revisions().recover(site)
                    val host = hostFor(site)
                    if (listeners[site.id]?.boundTo(host, site.port) == true) return@synchronized
                    listeners.remove(site.id)?.stop()
                    bindOrThrow(host, site.port)
                    listeners[site.id] = SiteListenerHandle.start(context, site, self)
                    lastListenerError = null
                }.onFailure { lastListenerError = "${it.javaClass.simpleName}: ${it.message}" }
            }
        }
    }

    /** Only needed when the bind address changes: routes read the site record per request. */
    fun restartListener(site: SiteRecord) {
        val self = this
        scope.launch {
            synchronized(listenerLock) {
                runCatching {
                    revisions().recover(site)
                    listeners.remove(site.id)?.stop()
                    val host = hostFor(site)
                    bindOrThrow(host, site.port)
                    listeners[site.id] = SiteListenerHandle.start(context, site, self)
                    lastListenerError = null
                }.onFailure { lastListenerError = "${it.javaClass.simpleName}: ${it.message}" }
            }
        }
    }

    private fun hostFor(site: SiteRecord): String = if (site.isLanExposed) "0.0.0.0" else "127.0.0.1"

    /**
     * Probes the port before Ktor takes it: a bind failure inside the engine's accept job is
     * asynchronous and would take the whole process down instead of being reported.
     */
    private fun bindOrThrow(host: String, port: Int) {
        java.net.ServerSocket().use { socket ->
            socket.reuseAddress = true
            socket.bind(java.net.InetSocketAddress(host, port))
        }
    }

     var lastListenerError: String? = null

    fun stopAllListeners() {
        synchronized(listenerLock) {
            listeners.values.forEach { runCatching { it.stop() } }
            listeners.clear()
        }
    }

    fun close() {
        stopAllListeners()
        databases.values.forEach { runCatching { it.close() } }
        databases.clear()
    }

    fun shutdown() {
        close()
        runCatching { repository.close() }
    }

    fun manifestJson(manifest: Manifest): String = JSONObject()
        .put("schemaVersion", manifest.schemaVersion)
        .put("slug", manifest.slug)
        .put("name", manifest.name)
        .put("entry", manifest.entry)
        .put("spaFallback", manifest.spaFallback)
        .put(
            "auth",
            JSONObject().put("read", manifest.auth.read).put("write", manifest.auth.write)
        )
        .put(
            "files",
            JSONObject()
                .put("maxBytes", manifest.files.maxBytes)
                .put("allow", org.json.JSONArray(manifest.files.allow))
        )
        .put(
            "tables",
            JSONObject().apply {
                manifest.tables.forEach { (name, table) ->
                    put(
                        name,
                        JSONObject().put(
                            "fields",
                            JSONObject().apply {
                                table.fields.forEach { field ->
                                    put(
                                        field.name,
                                        JSONObject()
                                            .put("type", field.type.name.lowercase())
                                            .put("required", field.required)
                                            .apply {
                                                field.maxLength?.let { put("maxLength", it) }
                                                field.default?.let { put("default", it) }
                                            }
                                    )
                                }
                            }
                        ).put(
                            "indexes",
                            org.json.JSONArray().apply {
                                table.indexes.forEach { cols ->
                                    put(JSONObject().put("fields", org.json.JSONArray(cols)))
                                }
                            }
                        )
                    )
                }
            }
        )
        .toString()

    fun closeDatabase(id: String) {
        databases.remove(id)?.close()
        fileIndexes.remove(id)
    }

    fun stopListenerFor(id: String) {
        synchronized(listenerLock) { listeners.remove(id)?.stop() }
    }

    fun siteDirOf(site: SiteRecord): File =
        File(context.noBackupFilesDir, "sites/${site.id}")

    fun databaseFileOf(site: SiteRecord): File =
        File(File(context.noBackupFilesDir, "sites"), "${site.id}.db")

    fun backups(): SiteBackupService = SiteBackupService(context, this)

    private val revisionService: SiteRevisionService by lazy { SiteRevisionService(this) }

    fun revisions(): SiteRevisionService = revisionService
}

