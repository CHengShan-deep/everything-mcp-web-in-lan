package dev.emwl.host.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokensTest {

    @Test
    fun `generated tokens are url safe and unique`() {
        val first = Tokens.generate()
        val second = Tokens.generate()
        assertNotEquals(first, second)
        assertTrue(first.matches(Regex("^[A-Za-z0-9_-]+$")))
        assertTrue(first.length >= 40)
    }

    @Test
    fun `hash matching accepts the same secret only`() {
        val token = Tokens.generate()
        val hash = Tokens.sha256Hex(token)
        assertTrue(Tokens.matches(token, hash))
        assertFalse(Tokens.matches(token + "x", hash))
        assertFalse(Tokens.matches(null, hash))
        assertFalse(Tokens.matches(token, null))
        assertFalse(Tokens.matches("", ""))
    }

    @Test
    fun `hash is stable and hex encoded`() {
        val hash = Tokens.sha256Hex("hello")
        assertTrue(hash.matches(Regex("^[0-9a-f]{64}$")))
        assertTrue(hash == Tokens.sha256Hex("hello"))
    }
}
