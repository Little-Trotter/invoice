package de.littletrotter.shared.pdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NormalizeWebsiteForDisplayTest {

    @Test
    fun `strips the https scheme and a trailing slash`() {
        assertEquals("www.example.de", normalizeWebsiteForDisplay("https://www.example.de/"))
    }

    @Test
    fun `strips the http scheme without requiring a trailing slash`() {
        assertEquals("example.de", normalizeWebsiteForDisplay("http://example.de"))
    }

    @Test
    fun `is case-insensitive on the scheme`() {
        assertEquals("example.de", normalizeWebsiteForDisplay("HTTPS://example.de"))
    }

    @Test
    fun `leaves a value with no scheme unchanged`() {
        assertEquals("www.example.de", normalizeWebsiteForDisplay("www.example.de"))
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals("example.de", normalizeWebsiteForDisplay("  https://example.de  "))
    }
}
