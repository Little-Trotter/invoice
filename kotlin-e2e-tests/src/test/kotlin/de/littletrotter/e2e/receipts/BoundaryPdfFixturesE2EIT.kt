package de.littletrotter.e2e.receipts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Self-check for [BoundaryPdfFixtures]: no Testcontainers, no Quarkus context — a plain check
 * that the generator itself is correct before [ReceiptBudgetE2EIT] RB-4 relies on it. Named
 * `*E2EIT` (not `*Test`) only because [E2eModuleStructureTest] requires every `@Test`-bearing
 * file in this module to carry that suffix; the check underneath runs at unit speed.
 */
class BoundaryPdfFixturesE2EIT {

    @Test
    fun `exactSizePdf produces exactly the requested byte count and a valid PDF header`() {
        listOf(1024, 500_000, 1_048_576, 1_572_864).forEach { size ->
            val pdf = BoundaryPdfFixtures.exactSizePdf(size)
            assertEquals(size, pdf.size, "requested $size bytes")
            assertTrue(
                String(pdf, 0, 5, Charsets.US_ASCII) == "%PDF-",
                "must start with the PDF magic header, was: ${String(pdf, 0, 5, Charsets.US_ASCII)}",
            )
        }
    }

    @Test
    fun `exactSizePdf refuses a target smaller than the fixed PDF skeleton`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            BoundaryPdfFixtures.exactSizePdf(1)
        }
        assertTrue(ex.message?.contains("skeleton") == true, "must name the reason, was: ${ex.message}")
    }
}
