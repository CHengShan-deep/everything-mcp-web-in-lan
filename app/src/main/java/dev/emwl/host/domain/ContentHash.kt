package dev.emwl.host.domain

import java.security.MessageDigest

/**
 * A revision is identified by the exact bytes it serves: the stored manifest plus every
 * file under `public/`. Sorting by path keeps the digest stable across filesystems, so
 * two deploys with identical content dedupe to one revision.
 */
object ContentHash {

    fun compute(manifest: ByteArray, files: Map<String, ByteArray>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun feed(path: String, bytes: ByteArray) {
            digest.update(path.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(sha256(bytes))
        }
        feed("manifest.json", manifest)
        files.toSortedMap().forEach { (path, bytes) -> feed(path, bytes) }
        return digest.digest().toHex()
    }

    fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
