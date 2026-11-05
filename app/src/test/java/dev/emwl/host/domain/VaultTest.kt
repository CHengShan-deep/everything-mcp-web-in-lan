package dev.emwl.host.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultTest {

    private val payload = "archive-bytes".toByteArray()
    private val passphrase = "correct horse battery".toCharArray()

    @Test
    fun `round trip returns the original bytes`() {
        val sealed = Vault.encrypt(payload, passphrase)
        assertTrue(Vault.isVault(sealed))
        assertArrayEquals(payload, Vault.decrypt(sealed, passphrase))
    }

    @Test
    fun `the same payload encrypts differently each time`() {
        val first = Vault.encrypt(payload, passphrase)
        val second = Vault.encrypt(payload, passphrase)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(payload, Vault.decrypt(second, passphrase))
    }

    @Test(expected = DomainException::class)
    fun `a wrong passphrase fails instead of returning garbage`() {
        Vault.decrypt(Vault.encrypt(payload, passphrase), "wrong passphrase".toCharArray())
    }

    @Test(expected = DomainException::class)
    fun `a tampered body is rejected`() {
        val sealed = Vault.encrypt(payload, passphrase)
        sealed[sealed.size - 2] = (sealed[sealed.size - 2] + 1).toByte()
        Vault.decrypt(sealed, passphrase)
    }

    @Test(expected = DomainException::class)
    fun `a plain file is not treated as an archive`() {
        Vault.decrypt("PKnot-a-vault".toByteArray(), passphrase)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `short passphrases are refused`() {
        Vault.encrypt(payload, "short".toCharArray())
    }
}
