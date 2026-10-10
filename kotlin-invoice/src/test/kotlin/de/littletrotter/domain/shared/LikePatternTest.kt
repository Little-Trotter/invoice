package de.littletrotter.domain.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LikePatternTest {

    @Test
    fun `filterTokens returns empty list for null input`() {
        val tokens = filterTokens(null)
        assertEquals(emptyList<String>(), tokens)
    }

    @Test
    fun `filterTokens returns empty list for blank input`() {
        val tokens = filterTokens("   ")
        assertEquals(emptyList<String>(), tokens)
    }

    @Test
    fun `filterTokens returns single token for non-whitespace input`() {
        val tokens = filterTokens("müller")
        assertEquals(listOf("müller"), tokens)
    }

    @Test
    fun `filterTokens splits multiple tokens on whitespace`() {
        val tokens = filterTokens("müller köln")
        assertEquals(listOf("müller", "köln"), tokens)
    }

    @Test
    fun `filterTokens handles multiple consecutive whitespaces`() {
        val tokens = filterTokens("müller  \t  köln")
        assertEquals(listOf("müller", "köln"), tokens)
    }

    @Test
    fun `filterTokens trims leading and trailing whitespace`() {
        val tokens = filterTokens("  müller köln  ")
        assertEquals(listOf("müller", "köln"), tokens)
    }

    @Test
    fun `filterTokens splits on whitespace only, punctuation stays inside a token`() {
        val tokens = filterTokens("müller,,köln")
        assertEquals(listOf("müller,,köln"), tokens)
    }

    @Test
    fun `likeFilterIndexed generates clause with correct index parameter`() {
        val clause = likeFilterIndexed("r.name", 0)
        assertEquals("lower(r.name) LIKE :f0 ESCAPE '\\'", clause)
    }

    @Test
    fun `likeFilterIndexed generates clause with different indices`() {
        val clause0 = likeFilterIndexed("r.name", 0)
        val clause1 = likeFilterIndexed("r.customerNumber", 1)
        val clause2 = likeFilterIndexed("r.shortHash", 2)

        assertEquals("lower(r.name) LIKE :f0 ESCAPE '\\'", clause0)
        assertEquals("lower(r.customerNumber) LIKE :f1 ESCAPE '\\'", clause1)
        assertEquals("lower(r.shortHash) LIKE :f2 ESCAPE '\\'", clause2)
    }

    @Test
    fun `likeFilterIndexed keeps LIKE and ESCAPE on same line`() {
        val clause = likeFilterIndexed("a.city", 5)
        // Verify LIKE and ESCAPE are on the same source line (no newlines in the string)
        assertFalse(clause.contains("\n"), "LIKE and ESCAPE must be on the same source line")
        assertTrue(clause.contains("LIKE :f5 ESCAPE"), "clause must contain complete LIKE...ESCAPE phrase")
    }

    @Test
    fun `likePattern with literal percent is neutralised`() {
        val pattern = likePattern("50%")
        assertEquals("%50\\%%", pattern)
    }

    @Test
    fun `likePattern with literal underscore is neutralised`() {
        val pattern = likePattern("50_000")
        assertEquals("%50\\_000%", pattern)
    }

    @Test
    fun `likePattern with both percent and underscore is neutralised`() {
        val pattern = likePattern("50%_0")
        assertEquals("%50\\%\\_0%", pattern)
    }
}
