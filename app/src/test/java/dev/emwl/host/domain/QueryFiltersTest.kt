package dev.emwl.host.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryFiltersTest {

    @Test
    fun `parses a filter into operator and value`() {
        val filter = QueryFilters.parseFilter("title", "like.abc")
        assertEquals("like", filter.op)
        assertEquals("abc", filter.value)
    }

    @Test(expected = DomainException::class)
    fun `rejects an unknown operator`() {
        QueryFilters.parseFilter("title", "regex.abc")
    }

    @Test
    fun `escapes like metacharacters instead of dropping them`() {
        assertEquals("abc\\_def", QueryFilters.escapeLike("abc_def"))
        assertEquals("abcXdef", QueryFilters.escapeLike("abcXdef"))
        assertEquals("50\\%", QueryFilters.escapeLike("50%"))
        assertEquals("a\\\\b", QueryFilters.escapeLike("a\\b"))
    }

    @Test
    fun `like filters bind escaped values`() {
        val (sql, value) = QueryFilters.sqlFor(QueryFilters.Filter("title", "like", "abc_def"))
        assertTrue(sql.contains("ESCAPE"))
        assertEquals("abc\\_def", value)
    }

    @Test
    fun `eq filters bind the raw value`() {
        val (_, value) = QueryFilters.sqlFor(QueryFilters.Filter("title", "eq", "abc_def"))
        assertEquals("abc_def", value)
    }

    @Test
    fun `order accepts known fields and directions`() {
        val order = QueryFilters.parseOrder("created_at desc", setOf("created_at"))
        assertEquals("created_at" to "DESC", order)
    }

    @Test(expected = DomainException::class)
    fun `order rejects unknown fields`() {
        QueryFilters.parseOrder("secret asc", setOf("created_at"))
    }

    @Test
    fun `limit is clamped to the maximum`() {
        assertEquals(100, QueryFilters.parseLimit("5000"))
        assertEquals(20, QueryFilters.parseLimit(null))
        assertEquals(7, QueryFilters.parseLimit("7"))
    }

    @Test
    fun `offset defaults to zero`() {
        assertEquals(0, QueryFilters.parseOffset(null))
        assertEquals(40, QueryFilters.parseOffset("40"))
    }

    @Test
    fun `no order means insertion order`() {
        assertNull(QueryFilters.parseOrder(null, setOf("a")))
        assertNull(QueryFilters.parseOrder("  ", setOf("a")))
    }

    @Test
    fun `identifiers follow the documented shape`() {
        assertTrue(Identifiers.isIdentifier("posts_2"))
        assertFalse(Identifiers.isIdentifier("Posts"))
        assertFalse(Identifiers.isIdentifier("2posts"))
        assertFalse(Identifiers.isIdentifier("posts-x"))
        assertTrue(Identifiers.isSlug("basis-website"))
        assertFalse(Identifiers.isSlug("-leading"))
    }
}
