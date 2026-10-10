package de.littletrotter.shared

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse

/**
 * Tests for VeraPdfResult data class and its summary() method,
 * covering all branches of the when statement.
 */
class VeraPdfResultTest {

    @Test
    fun `summary() returns konform message when outcome is VALID`() {
        val result = VeraPdfResult(
            outcome = KositOutcome.VALID,
            profile = "PDF/A-3b",
            validator = "veraPDF 1.25",
            failedRuleCount = 0,
            failedCheckCount = 0,
            findings = emptyList(),
        )

        val summary = result.summary()
        assertEquals("konform (PDF/A-3b, veraPDF 1.25)", summary)
        assertTrue(result.conformant)
    }

    @Test
    fun `summary() returns nicht konform message when outcome is INVALID`() {
        val result = VeraPdfResult(
            outcome = KositOutcome.INVALID,
            profile = "PDF/A-3b",
            validator = "veraPDF 1.25",
            failedRuleCount = 3,
            failedCheckCount = 5,
            findings = emptyList(),
        )

        val summary = result.summary()
        assertEquals(
            "nicht konform (PDF/A-3b): 3 Regel(n), 5 Prüfung(en) fehlgeschlagen",
            summary
        )
        assertFalse(result.conformant)
    }

    @Test
    fun `summary() returns nicht geprüft message with engine error when outcome is ERROR`() {
        val result = VeraPdfResult(
            outcome = KositOutcome.ERROR,
            profile = "PDF/A-3b",
            validator = "veraPDF 1.25",
            failedRuleCount = 0,
            failedCheckCount = 0,
            findings = emptyList(),
            engineError = "PDF parsing failed: corrupted stream",
        )

        val summary = result.summary()
        assertEquals(
            "nicht geprüft: PDF parsing failed: corrupted stream",
            summary
        )
        assertFalse(result.conformant)
    }

    @Test
    fun `summary() returns default engine error message when engineError is null`() {
        val result = VeraPdfResult(
            outcome = KositOutcome.ERROR,
            profile = "PDF/A-3b",
            validator = "veraPDF 1.25",
            failedRuleCount = 0,
            failedCheckCount = 0,
            findings = emptyList(),
            engineError = null,
        )

        val summary = result.summary()
        assertEquals(
            "nicht geprüft: unbekannter Engine-Fehler",
            summary
        )
        assertFalse(result.conformant)
    }

    @Test
    fun `conformant property is true only for VALID outcome`() {
        val valid = VeraPdfResult(
            outcome = KositOutcome.VALID,
            profile = "PDF/A-3b",
            validator = "veraPDF",
            failedRuleCount = 0,
            failedCheckCount = 0,
            findings = emptyList(),
        )
        val invalid = VeraPdfResult(
            outcome = KositOutcome.INVALID,
            profile = "PDF/A-3b",
            validator = "veraPDF",
            failedRuleCount = 1,
            failedCheckCount = 1,
            findings = emptyList(),
        )
        val error = VeraPdfResult(
            outcome = KositOutcome.ERROR,
            profile = "PDF/A-3b",
            validator = "veraPDF",
            failedRuleCount = 0,
            failedCheckCount = 0,
            findings = emptyList(),
        )

        assertTrue(valid.conformant)
        assertFalse(invalid.conformant)
        assertFalse(error.conformant)
    }

    @Test
    fun `summary with zero failed rules and checks formats correctly`() {
        val result = VeraPdfResult(
            outcome = KositOutcome.INVALID,
            profile = "PDF/A-3",
            validator = "veraPDF",
            failedRuleCount = 0,
            failedCheckCount = 0,
            findings = emptyList(),
        )

        val summary = result.summary()
        assertEquals("nicht konform (PDF/A-3): 0 Regel(n), 0 Prüfung(en) fehlgeschlagen", summary)
    }

    @Test
    fun `summary with large numbers formats correctly`() {
        val result = VeraPdfResult(
            outcome = KositOutcome.INVALID,
            profile = "PDF/A-3",
            validator = "veraPDF",
            failedRuleCount = 999,
            failedCheckCount = 9999,
            findings = emptyList(),
        )

        val summary = result.summary()
        assertEquals("nicht konform (PDF/A-3): 999 Regel(n), 9999 Prüfung(en) fehlgeschlagen", summary)
    }
}
