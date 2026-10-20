package de.littletrotter.domain.invoice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ST-1 — what is left to test on [InvoiceStatus] itself once [InvoiceStateMachine] owns the edge
 * table (see [InvoiceStateMachineTest] for that exhaustive coverage): the per-constant label/CSS
 * metadata, and that [InvoiceStatus.canTransitionTo] actually delegates rather than re-encoding
 * the table a second time.
 */
class InvoiceStatusTest {

    @Test
    fun `canTransitionTo delegates to InvoiceStateMachine`() {
        assertTrue(InvoiceStatus.DRAFT.canTransitionTo(InvoiceStatus.IN_REVIEW))
        assertEquals(false, InvoiceStatus.DRAFT.canTransitionTo(InvoiceStatus.ISSUED))
    }

    @Test
    fun `declaration order is the lifecycle order`() {
        // ST-2 — VOIDED sits ahead of CANCELLED: a draft is discarded before a sent invoice can
        // ever be cancellation'd. No ordinal is frozen for compatibility (there is no live instance).
        assertEquals(0, InvoiceStatus.DRAFT.ordinal)
        assertEquals(1, InvoiceStatus.IN_REVIEW.ordinal)
        assertEquals(2, InvoiceStatus.ISSUED.ordinal)
        assertEquals(3, InvoiceStatus.VOIDED.ordinal)
        assertEquals(4, InvoiceStatus.CANCELLED.ordinal)
    }

    @Test
    fun `every status carries a distinct, non-blank labelKey and cssClass`() {
        val labelKeys = InvoiceStatus.entries.map { it.labelKey }
        val cssClasses = InvoiceStatus.entries.map { it.cssClass }
        assertTrue(labelKeys.all { it.isNotBlank() })
        assertTrue(cssClasses.all { it.isNotBlank() })
        assertEquals(labelKeys.size, labelKeys.toSet().size, "labelKey must be unique per status")
        assertEquals(cssClasses.size, cssClasses.toSet().size, "cssClass must be unique per status")
    }

    @Test
    fun `VOIDED carries its own labelKey and cssClass`() {
        assertEquals("invoice.status.voided", InvoiceStatus.VOIDED.labelKey)
        assertEquals("voided", InvoiceStatus.VOIDED.cssClass)
    }
}
