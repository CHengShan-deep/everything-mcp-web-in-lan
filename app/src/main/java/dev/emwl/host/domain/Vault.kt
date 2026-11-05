package dev.emwl.host.domain

import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase-encrypted container for a backup archive. The whole archive is encrypted, so
 * upload names and site paths stay hidden; AES-GCM also authenticates the contents, which
 * means a wrong passphrase and a tampered file both fail instead of decrypting garbage.
 */
object Vault {

    private const val MAGIC = "PocketServe-vault-1"
    private const val ITERATIONS = 120_000
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    const val MIN_PASSPHRASE = 8

    fun isVault(bytes: ByteArray): Boolean =
        bytes.size > MAGIC.length + 2 && String(bytes, 0, MAGIC.length, Charsets.UTF_8) == MAGIC

    fun encrypt(plain: ByteArray, passphrase: CharArray): ByteArray {
        require(passphrase.size >= MIN_PASSPHRASE) { "passphrase is too short" }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            secret(passphrase, salt, ITERATIONS),
            GCMParameterSpec(TAG_BITS, nonce),
        )
        val cipherText = cipher.doFinal(plain)

        val header = JSONObject()
            .put("kdf", "pbkdf2-sha256")
            .put("iterations", ITERATIONS)
            .put("salt", Base64.getEncoder().encodeToString(salt))
            .put("nonce", Base64.getEncoder().encodeToString(nonce))
            .toString()
            .toByteArray(Charsets.UTF_8)

        return MAGIC.toByteArray(Charsets.UTF_8) + '\n'.code.toByte() + header +
            '\n'.code.toByte() + cipherText
    }

    fun decrypt(container: ByteArray, passphrase: CharArray): ByteArray {
        if (!isVault(container)) throw DomainException("NOT_A_VAULT", "this file is not an encrypted archive")

        val magicEnd = MAGIC.length + 1
        val headerEnd = container.indexOfFrom('\n'.code.toByte(), magicEnd)
        if (headerEnd < 0) throw DomainException("VAULT_CORRUPT", "encrypted archive header is truncated")

        val header = runCatching {
            JSONObject(String(container, magicEnd, headerEnd - magicEnd, Charsets.UTF_8))
        }.getOrElse { throw DomainException("VAULT_CORRUPT", "encrypted archive header is not valid") }

        if (header.optString("kdf") != "pbkdf2-sha256") {
            throw DomainException("VAULT_UNSUPPORTED", "unsupported key derivation: ${header.optString("kdf")}")
        }
        val iterations = header.optInt("iterations", 0)
        if (iterations !in 10_000..2_000_000) {
            throw DomainException("VAULT_UNSUPPORTED", "unsupported iteration count: $iterations")
        }

        val salt = runCatching { Base64.getDecoder().decode(header.getString("salt")) }
            .getOrElse { throw DomainException("VAULT_CORRUPT", "encrypted archive salt is invalid") }
        val nonce = runCatching { Base64.getDecoder().decode(header.getString("nonce")) }
            .getOrElse { throw DomainException("VAULT_CORRUPT", "encrypted archive nonce is invalid") }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            secret(passphrase, salt, iterations),
            GCMParameterSpec(TAG_BITS, nonce),
        )
        return try {
            cipher.doFinal(container, headerEnd + 1, container.size - headerEnd - 1)
        } catch (t: Throwable) {
            throw DomainException("VAULT_DECRYPT_FAILED", "wrong passphrase or damaged archive")
        }
    }

    private fun ByteArray.indexOfFrom(element: Byte, from: Int): Int {
        for (index in from until size) if (this[index] == element) return index
        return -1
    }

    private fun secret(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        return try {
            SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
