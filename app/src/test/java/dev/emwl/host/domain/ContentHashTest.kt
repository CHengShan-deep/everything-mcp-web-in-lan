package dev.emwl.host.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ContentHashTest {

    private val manifest = """{"schemaVersion":1}""".toByteArray()

    @Test
    fun `identical content hashes equally regardless of insertion order`() {
        val a = linkedMapOf("index.html" to "hello".toByteArray(), "app.js" to "x".toByteArray())
        val b = linkedMapOf("app.js" to "x".toByteArray(), "index.html" to "hello".toByteArray())
        assertEquals(ContentHash.compute(manifest, a), ContentHash.compute(manifest, b))
    }

    @Test
    fun `changing one file changes the digest`() {
        val before = mapOf("index.html" to "hello".toByteArray())
        val after = mapOf("index.html" to "hello!".toByteArray())
        assertNotEquals(ContentHash.compute(manifest, before), ContentHash.compute(manifest, after))
    }

    @Test
    fun `renaming a file changes the digest`() {
        val before = mapOf("a.html" to "same".toByteArray())
        val after = mapOf("b.html" to "same".toByteArray())
        assertNotEquals(ContentHash.compute(manifest, before), ContentHash.compute(manifest, after))
    }

    @Test
    fun `the manifest takes part in the digest`() {
        val files = mapOf("index.html" to "hello".toByteArray())
        assertNotEquals(
            ContentHash.compute(manifest, files),
            ContentHash.compute("""{"schemaVersion":1,"entry":"other.html"}""".toByteArray(), files)
        )
    }

    @Test
    fun `path boundaries cannot be forged by content`() {
        val nested = mapOf("a/b" to "c".toByteArray())
        val flat = mapOf("a" to "bc".toByteArray())
        assertNotEquals(ContentHash.compute(manifest, nested), ContentHash.compute(manifest, flat))
    }
}
