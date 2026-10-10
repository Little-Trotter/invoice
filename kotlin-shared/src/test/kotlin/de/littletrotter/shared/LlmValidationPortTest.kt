package de.littletrotter.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LlmValidationPortTest {

    @Test
    fun `equals compares all fields`() {
        val a = LlmModelResult(model = "m", markdownReport = "r", success = true, durationMs = 10)
        val b = LlmModelResult(model = "m", markdownReport = "r", success = true, durationMs = 10)

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `equals treats two null error fields as equal`() {
        val a = LlmModelResult(model = "m", markdownReport = "r", success = false, durationMs = 5, error = "boom")
        val b = LlmModelResult(model = "m", markdownReport = "r", success = false, durationMs = 5, error = "boom")

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `equals is reflexive`() {
        val a = LlmModelResult(model = "m", markdownReport = "r", success = true, durationMs = 1)
        assertEquals(a, a)
    }

    @Test
    fun `not equal to a different type`() {
        val a = LlmModelResult(model = "m", markdownReport = "r", success = true, durationMs = 1)
        assertFalse(a.equals("not a LlmModelResult"))
    }

    @Test
    fun `differs when model, report, success, error or duration differ`() {
        val base = LlmModelResult(model = "m", markdownReport = "r", success = true, durationMs = 1)

        assertNotEquals(base, base.copy(model = "other"))
        assertNotEquals(base, base.copy(markdownReport = "other"))
        assertNotEquals(base, base.copy(success = false))
        assertNotEquals(base, base.copy(error = "oops"))
        assertNotEquals(base, base.copy(durationMs = 2))
    }

    @Test
    fun `disabled factory marks failure with omLX note`() {
        val disabled = LlmModelResult.disabled()
        assertFalse(disabled.success)
        assertEquals("", disabled.markdownReport)
        assertEquals("oMLX not configured", disabled.error)
    }

    @Test
    fun `boundary - error factory marks failure with the given reason and zero duration`() {
        val failure = LlmModelResult.error("timeout after 30s")

        assertFalse(failure.success)
        assertEquals("oMLX", failure.model)
        assertEquals("", failure.markdownReport)
        assertEquals("timeout after 30s", failure.error)
        assertEquals(0L, failure.durationMs)
    }

    @Test
    fun `boundary - toString includes every field for diagnostics`() {
        val result = LlmModelResult(model = "m", markdownReport = "r", success = true, durationMs = 7)

        assertEquals(
            "LlmModelResult(model=m, markdownReport=r, success=true, durationMs=7, error=null)",
            result.toString(),
        )
    }

    @Test
    fun `boundary - a caller through the interface type may omit layoutReport, defaulting it to blank`() {
        var seenLayoutReport: String? = null
        val port = object : LlmValidationPort {
            override fun isAvailable(): Boolean = true

            override fun analyze(
                xmlBytes: ByteArray,
                invoice: InvoiceDto,
                kositReport: String,
                layoutReport: String,
            ): LlmModelResult {
                seenLayoutReport = layoutReport
                return LlmModelResult(model = "m", markdownReport = "", success = true, durationMs = 0)
            }
        }

        val result = port.analyze(ByteArray(0), InvoiceDto(), "{}")

        assertEquals("", seenLayoutReport)
        assertTrue(result.success)
    }
}
