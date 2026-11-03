package dev.emwl.host.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestTest {

    private val valid = """
        {
          "schemaVersion": 1,
          "slug": "basiswebsite",
          "name": "Basis Website",
          "entry": "index.html",
          "spaFallback": true,
          "auth": { "read": "none", "write": "token" },
          "tables": {
            "posts": {
              "fields": {
                "title": { "type": "text", "required": true, "maxLength": 200 },
                "body": { "type": "text" },
                "published": { "type": "boolean", "default": false },
                "created_at": { "type": "datetime", "default": "now" }
              },
              "indexes": [{ "fields": ["created_at"] }]
            }
          }
        }
    """.trimIndent()

    @Test
    fun `parses a valid manifest`() {
        val manifest = Manifest.parse(valid)
        assertEquals("basiswebsite", manifest.slug)
        assertEquals("none", manifest.auth.read)
        assertEquals("token", manifest.auth.write)
        val posts = manifest.table("posts")
        assertEquals(4, posts.fields.size)
        assertEquals(FieldType.DATETIME, posts.fields.first { it.name == "created_at" }.type)
        assertEquals(1, posts.indexes.size)
    }

    @Test(expected = DomainException::class)
    fun `rejects a missing schema version`() {
        Manifest.parse("""{"slug":"a","name":"A"}""")
    }

    @Test(expected = DomainException::class)
    fun `rejects an invalid slug`() {
        Manifest.parse(valid.replace("\"basiswebsite\"", "\"Basis Website\""))
    }

    @Test(expected = DomainException::class)
    fun `rejects an unknown field type`() {
        Manifest.parse(valid.replace("\"body\": { \"type\": \"text\" }", "\"body\": { \"type\": \"blob\" }"))
    }

    @Test(expected = DomainException::class)
    fun `rejects maxLength on a non text field`() {
        Manifest.parse(valid.replace("\"type\": \"boolean\", \"default\": false", "\"type\": \"boolean\", \"maxLength\": 10"))
    }

    @Test(expected = DomainException::class)
    fun `rejects now default on a non datetime field`() {
        Manifest.parse(valid.replace("\"body\": { \"type\": \"text\" }", "\"body\": { \"type\": \"text\", \"default\": \"now\" }"))
    }

    @Test(expected = DomainException::class)
    fun `rejects an unsafe entry path`() {
        Manifest.parse(valid.replace("\"index.html\"", "\"../secret.html\""))
    }

    @Test(expected = DomainException::class)
    fun `rejects an index over an unknown field`() {
        Manifest.parse(valid.replace("\"fields\": [\"created_at\"]", "\"fields\": [\"missing_at\"]"))
    }

    @Test
    fun `entry defaults to index html`() {
        val manifest = Manifest.parse(valid.replace("\"entry\": \"index.html\",", ""))
        assertEquals("index.html", manifest.entry)
    }

    @Test
    fun `relative path guard covers traversal and absolute paths`() {
        assertTrue(Manifest.isSafeRelativePath("assets/app.js"))
        assertFalse(Manifest.isSafeRelativePath("/etc/passwd"))
        assertFalse(Manifest.isSafeRelativePath("../x"))
        assertFalse(Manifest.isSafeRelativePath("a/../b"))
        assertFalse(Manifest.isSafeRelativePath("a//b"))
        assertFalse(Manifest.isSafeRelativePath(""))
    }
}
