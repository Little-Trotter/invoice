package de.littletrotter.application.masterdata

import de.littletrotter.shared.time.ClockTimeSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/** The article tax-variant section's country set; the rows themselves are covered with the
 *  article mapping. */
class ArticleTaxVariantsModuleTest {

    private val module = ArticleTaxVariantsModule(ClockTimeSource.fixedAt(Instant.parse("2026-06-15T10:00:00Z")))

    @Test
    fun `exposes exactly the curated country set, sorted`() {
        assertEquals(
            listOf(
                "AT", "BE", "BG", "CY", "CZ", "DK", "EE", "ES", "FI", "FR", "GR", "HR", "HU",
                "IE", "IT", "LT", "LU", "LV", "MT", "NL", "PL", "PT", "RO", "SE", "SI", "SK",
            ),
            module.supportedCountries,
        )
    }

    @Test
    fun `identifies itself with a stable id`() {
        assertEquals("articleTaxVariants", module.id)
    }
}
