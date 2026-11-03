package dev.emwl.host.service

import android.content.Context
import dev.emwl.host.data.SiteDatabase
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = ConcurrentHashMap<String, SiteListenerHandle>()

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

    fun publicDir(site: SiteRecord): File =
        File(File(context.noBackupFilesDir, "sites/${site.id}"), "public").apply { mkdirs() }

    fun createSite(slug: String, name: String): JSONObject {
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
            createdAt = now,
            updatedAt = now,
        )
        repository.insert(record)
        return JSONObject()
            .put("site", siteJson(record))
            .put("writeToken", writeToken)
    }

    fun deleteSite(id: String): Boolean {
        val site = repository.find(id) ?: return false
        listeners.remove(id)?.stop()
        databases.remove(id)?.close()
        publicDir(site).parentFile?.deleteRecursively()
        return repository.delete(id)
    }

    fun renameSite(id: String, name: String): SiteRecord {
        val site = requireSite(id)
        val updated = site.copy(name = name, updatedAt = System.currentTimeMillis())
        repository.update(updated)
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
        return updated
    }

    fun setAuth(id: String, read: String?, write: String?): JSONObject {
        val site = requireSite(id)
        val policy = AuthPolicy.from(read ?: site.authRead, write ?: site.authWrite)

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
        val result = JSONObject().put("site", siteJson(updated))
        readToken?.let { result.put("readToken", it) }
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
        return JSONObject().put("which", which).put("token", token)
    }

    fun deploy(id: String, manifestJson: String, files: Map<String, String>): JSONObject {
        val site = requireSite(id)
        val incoming = Manifest.parse(manifestJson)

        val publicDir = publicDir(site)
        val written = ArrayList<String>()
        val rejected = ArrayList<String>()

        files.forEach { (path, content) ->
            if (!Manifest.isSafeRelativePath(path)) {
                rejected += path
                return@forEach
            }
            val target = File(publicDir, path)
            val canonicalRoot = publicDir.canonicalFile
            val canonicalTarget = target.canonicalFile
            if (!canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)) {
                rejected += path
                return@forEach
            }
            target.parentFile?.mkdirs()
            target.writeText(content)
            written += path
        }

        val schemaResult = database(site).applySchema(incoming)

        val storedManifest = toJson(incoming)
        val updated = site.copy(
            manifestJson = storedManifest,
            updatedAt = System.currentTimeMillis(),
        )
        repository.update(updated)

        return JSONObject()
            .put("site", siteJson(updated))
            .put("writtenFiles", written.size)
            .put("rejectedFiles", rejected)
            .put("schema", schemaResult)
    }

    fun urls(site: SiteRecord, lanIp: String? = dev.emwl.host.Metrics.lanIpv4()): JSONObject {
        val local = "http://127.0.0.1:${site.port}"
        val lan = lanIp?.let { "http://$it:${site.port}" }
        val named = "http://${site.slug}.$baseDomain"
        return JSONObject()
            .put("local", local)
            .put("lan", lan)
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
        .put("createdAt", site.createdAt)
        .put("updatedAt", site.updatedAt)

    fun requireSite(id: String): SiteRecord =
        repository.find(id) ?: throw DomainException("SITE_NOT_FOUND", "site not found: $id")

    fun registerListener(id: String, handle: SiteListenerHandle) {
        listeners.put(id, handle)?.stop()
    }

    fun restartListener(site: SiteRecord) {
        listeners.remove(site.id)?.stop()
        startListener(site)
    }

    fun startListener(site: SiteRecord) {
        val self = this
        scope.launch {
            runCatching {
                listeners.remove(site.id)?.stop()
                listeners[site.id] = SiteListenerHandle.start(context, site, self)
            }.onFailure { lastListenerError = "${it.javaClass.simpleName}: ${it.message}" }
        }
    }

     var lastListenerError: String? = null

    fun stopAllListeners() {
        listeners.values.forEach { runCatching { it.stop() } }
        listeners.clear()
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

    private fun toJson(manifest: Manifest): String = JSONObject()
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
}
