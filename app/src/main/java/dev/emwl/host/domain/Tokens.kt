package dev.emwl.host.domain

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object Tokens {

    private val random = SecureRandom()

    fun generate(bytes: Int = 32): String {
        val buffer = ByteArray(bytes)
        random.nextBytes(buffer)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }

    fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun matches(candidate: String?, expectedHash: String?): Boolean {
        if (candidate.isNullOrEmpty() || expectedHash.isNullOrEmpty()) return false
        return MessageDigest.isEqual(
            sha256Hex(candidate).toByteArray(Charsets.UTF_8),
            expectedHash.toByteArray(Charsets.UTF_8)
        )
    }
}
