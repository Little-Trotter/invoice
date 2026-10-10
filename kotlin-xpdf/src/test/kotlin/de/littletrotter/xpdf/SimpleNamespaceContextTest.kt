package de.littletrotter.xpdf

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import javax.xml.XMLConstants

/**
 * Tests for SimpleNamespaceContext utility class.
 * Ensures all branches of the namespace resolution logic are covered.
 */
class SimpleNamespaceContextTest {

    @Test
    fun `getNamespaceURI returns mapped URI for known prefix`() {
        val context = SimpleNamespaceContext(mapOf(
            "test" to "http://example.com/test",
            "other" to "http://example.com/other"
        ))

        assertEquals("http://example.com/test", context.getNamespaceURI("test"))
        assertEquals("http://example.com/other", context.getNamespaceURI("other"))
    }

    @Test
    fun `getNamespaceURI returns NULL_NS_URI for unknown prefix`() {
        val context = SimpleNamespaceContext(mapOf(
            "known" to "http://example.com/known"
        ))

        assertEquals(XMLConstants.NULL_NS_URI, context.getNamespaceURI("unknown"))
        assertEquals(XMLConstants.NULL_NS_URI, context.getNamespaceURI("missing"))
    }

    @Test
    fun `getNamespaceURI handles empty map`() {
        val context = SimpleNamespaceContext(emptyMap())

        assertEquals(XMLConstants.NULL_NS_URI, context.getNamespaceURI("any"))
    }

    @Test
    fun `getPrefix returns mapped prefix for known namespace URI`() {
        val context = SimpleNamespaceContext(mapOf(
            "test" to "http://example.com/test",
            "other" to "http://example.com/other"
        ))

        val testPrefix = context.getPrefix("http://example.com/test")
        assertNotNull(testPrefix)
        assertEquals("test", testPrefix)
    }

    @Test
    fun `getPrefix returns null for unknown namespace URI`() {
        val context = SimpleNamespaceContext(mapOf(
            "known" to "http://example.com/known"
        ))

        assertNull(context.getPrefix("http://example.com/unknown"))
        assertNull(context.getPrefix("http://example.com/missing"))
    }

    @Test
    fun `getPrefix handles empty map`() {
        val context = SimpleNamespaceContext(emptyMap())

        assertNull(context.getPrefix("any"))
    }

    @Test
    fun `getPrefixes returns all matching prefixes for namespace URI`() {
        val context = SimpleNamespaceContext(mapOf(
            "prefix1" to "http://example.com/shared",
            "prefix2" to "http://example.com/shared",
            "prefix3" to "http://example.com/other"
        ))

        val iterator = context.getPrefixes("http://example.com/shared")
        val prefixes = iterator.asSequence().toSet()
        assertEquals(setOf("prefix1", "prefix2"), prefixes)
    }

    @Test
    fun `getPrefixes returns empty iterator for unknown namespace URI`() {
        val context = SimpleNamespaceContext(mapOf(
            "known" to "http://example.com/known"
        ))

        val iterator = context.getPrefixes("http://example.com/unknown")
        assertFalse(iterator.hasNext())
    }

    @Test
    fun `getPrefixes handles empty map`() {
        val context = SimpleNamespaceContext(emptyMap())

        val iterator = context.getPrefixes("any")
        assertFalse(iterator.hasNext())
    }
}
