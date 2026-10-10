package de.littletrotter.shared

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull

/**
 * Tests for PdfVisionResult data class and its disabled() factory method.
 */
class PdfVisionResultTest {

    @Test
    fun `disabled() factory creates result with expected failure state`() {
        val result = PdfVisionResult.disabled()

        assertEquals("oMLX-vision", result.model)
        assertEquals("", result.markdownReport)
        assertFalse(result.success)
        assertEquals(0L, result.durationMs)
        assertEquals("vision model not configured", result.error)
    }

    @Test
    fun `successful result can be constructed with actual values`() {
        val result = PdfVisionResult(
            model = "gpt-4-vision",
            markdownReport = "# Visual Review\n\nLayout looks good.",
            success = true,
            durationMs = 1234,
            error = null,
        )

        assertEquals("gpt-4-vision", result.model)
        assertEquals("# Visual Review\n\nLayout looks good.", result.markdownReport)
        assertEquals(true, result.success)
        assertEquals(1234L, result.durationMs)
        assertNull(result.error)
    }

    @Test
    fun `failure result with error message`() {
        val result = PdfVisionResult(
            model = "claude-vision",
            markdownReport = "",
            success = false,
            durationMs = 5000,
            error = "API timeout after 5 seconds",
        )

        assertEquals("claude-vision", result.model)
        assertEquals("", result.markdownReport)
        assertFalse(result.success)
        assertEquals(5000L, result.durationMs)
        assertEquals("API timeout after 5 seconds", result.error)
    }

    @Test
    fun `result with empty model name`() {
        val result = PdfVisionResult(
            model = "",
            markdownReport = "content",
            success = true,
            durationMs = 100,
            error = null,
        )

        assertEquals("", result.model)
    }

    @Test
    fun `result with empty markdown report`() {
        val result = PdfVisionResult(
            model = "test-model",
            markdownReport = "",
            success = true,
            durationMs = 100,
            error = null,
        )

        assertEquals("", result.markdownReport)
    }

    @Test
    fun `result with multi-line markdown report`() {
        val markdown = """# Visual Analysis

## Color Scheme
The color scheme is consistent and professional.

## Layout
- Good spacing
- Clear hierarchy
- No overlap issues
""".trimIndent()

        val result = PdfVisionResult(
            model = "vision-model",
            markdownReport = markdown,
            success = true,
            durationMs = 2000,
            error = null,
        )

        assertEquals(markdown, result.markdownReport)
    }

    @Test
    fun `data class equality works correctly`() {
        val result1 = PdfVisionResult.disabled()
        val result2 = PdfVisionResult(
            model = "oMLX-vision",
            markdownReport = "",
            success = false,
            durationMs = 0,
            error = "vision model not configured",
        )

        assertEquals(result1, result2)
    }

    @Test
    fun `data class hash code works correctly`() {
        val result1 = PdfVisionResult.disabled()
        val result2 = PdfVisionResult.disabled()

        assertEquals(result1.hashCode(), result2.hashCode())
    }
}
