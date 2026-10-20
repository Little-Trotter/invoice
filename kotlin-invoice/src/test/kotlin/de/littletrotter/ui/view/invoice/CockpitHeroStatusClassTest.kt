package de.littletrotter.ui.view.invoice

import de.littletrotter.domain.invoice.InvoiceStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * AP-IMP-5 — [cockpitHeroStatusClass] (InvoiceDetailViewCockpit.kt) had no dedicated test before
 * this file: each of its five branches picks a distinct hero-card CSS modifier.
 */
class CockpitHeroStatusClassTest {

    @Test
    fun `every status maps to its own distinct hero class`() {
        assertEquals("cockpit-hero--sent", cockpitHeroStatusClass(InvoiceStatus.ISSUED))
        assertEquals("cockpit-hero--cancelled", cockpitHeroStatusClass(InvoiceStatus.CANCELLED))
        assertEquals("cockpit-hero--voided", cockpitHeroStatusClass(InvoiceStatus.VOIDED))
        assertEquals("cockpit-hero--review", cockpitHeroStatusClass(InvoiceStatus.IN_REVIEW))
        assertEquals("cockpit-hero--draft", cockpitHeroStatusClass(InvoiceStatus.DRAFT))
    }

    @Test
    fun `every status yields a unique class, none collapse together`() {
        val classes = InvoiceStatus.entries.map { cockpitHeroStatusClass(it) }
        assertEquals(InvoiceStatus.entries.size, classes.toSet().size)
    }
}
