package de.littletrotter.shared

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class KositValidationResultTest {

    @Test
    fun `build and inspect validation result with messages`() {
        val msg1 = ValidationMessage("ERROR", "missing element", "/invoice[1]")
        val msg2 = ValidationMessage("WARNING", "deprecated field", "/invoice[1]/old")
        val result = KositValidationResult(
            status = KositOutcome.INVALID,
            summary = "2 issues",
            details = listOf(msg1, msg2),
            validatorVersion = "1.2.3",
            rawReportRef = "s3://bucket/report.xml"
        )

        assertEquals(KositOutcome.INVALID, result.status)
        assertEquals("2 issues", result.summary)
        assertEquals(2, result.details.size)
        assertEquals("1.2.3", result.validatorVersion)
        assertEquals("s3://bucket/report.xml", result.rawReportRef)

        // equality / data class behavior
        val other = result.copy(summary = "2 issues")
        assertEquals(result, other)
    }

    @Test
    fun `empty details default works`() {
        val r = KositValidationResult(status = KositOutcome.VALID)
        assertTrue(r.details.isEmpty())
        assertNull(r.summary)
    }
}
