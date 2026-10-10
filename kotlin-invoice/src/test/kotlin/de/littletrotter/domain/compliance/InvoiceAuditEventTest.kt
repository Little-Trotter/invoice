package de.littletrotter.domain.compliance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * [InvoiceAuditEvent.fromOrdinal] is the read-side inverse of the SMALLINT
 * `capture_invoice_audit_event()` writes (`V1__baseline_schema.sql`, section 13,
 * `chk_invoice_audit_journal_event`). Pins the exact 0..15 literal mapping the SQL comment
 * documents (extended to 0..11 for `trip_expense`) — a drift here is exactly the drift
 * the baseline file's own enum-CHECK convention says must never happen.
 */
class InvoiceAuditEventTest {

    @Test
    fun `RS-8 - fromOrdinal maps every SQL-side event code to its Kotlin enum entry in order`() {
        assertEquals(InvoiceAuditEvent.INVOICE_CREATED, InvoiceAuditEvent.fromOrdinal(0))
        assertEquals(InvoiceAuditEvent.INVOICE_UPDATED, InvoiceAuditEvent.fromOrdinal(1))
        assertEquals(InvoiceAuditEvent.INVOICE_DELETED, InvoiceAuditEvent.fromOrdinal(2))
        assertEquals(InvoiceAuditEvent.INVOICE_ITEM_CREATED, InvoiceAuditEvent.fromOrdinal(3))
        assertEquals(InvoiceAuditEvent.INVOICE_ITEM_UPDATED, InvoiceAuditEvent.fromOrdinal(4))
        assertEquals(InvoiceAuditEvent.INVOICE_ITEM_DELETED, InvoiceAuditEvent.fromOrdinal(5))
        assertEquals(InvoiceAuditEvent.INVOICE_DOCUMENT_CREATED, InvoiceAuditEvent.fromOrdinal(6))
        assertEquals(InvoiceAuditEvent.INVOICE_DOCUMENT_UPDATED, InvoiceAuditEvent.fromOrdinal(7))
        assertEquals(InvoiceAuditEvent.INVOICE_DOCUMENT_DELETED, InvoiceAuditEvent.fromOrdinal(8))
        assertEquals(InvoiceAuditEvent.TRIP_EXPENSE_CREATED, InvoiceAuditEvent.fromOrdinal(9))
        assertEquals(InvoiceAuditEvent.TRIP_EXPENSE_UPDATED, InvoiceAuditEvent.fromOrdinal(10))
        assertEquals(InvoiceAuditEvent.TRIP_EXPENSE_DELETED, InvoiceAuditEvent.fromOrdinal(11))
        assertEquals(InvoiceAuditEvent.INVOICE_RECEIPT_ASSIGNED, InvoiceAuditEvent.fromOrdinal(12))
        assertEquals(InvoiceAuditEvent.INVOICE_RECEIPT_UNASSIGNED, InvoiceAuditEvent.fromOrdinal(13))
        assertEquals(InvoiceAuditEvent.ITEM_RECEIPT_ASSIGNED, InvoiceAuditEvent.fromOrdinal(14))
        assertEquals(InvoiceAuditEvent.ITEM_RECEIPT_UNASSIGNED, InvoiceAuditEvent.fromOrdinal(15))
    }

    @Test
    fun `RS-8 - every entry's own ordinal round-trips through fromOrdinal`() {
        InvoiceAuditEvent.entries.forEach { event ->
            assertEquals(event, InvoiceAuditEvent.fromOrdinal(event.ordinal))
        }
    }

    @Test
    fun `RS-8 - fromOrdinal rejects an out-of-range ordinal instead of silently defaulting`() {
        assertThrows(IllegalArgumentException::class.java) { InvoiceAuditEvent.fromOrdinal(16) }
        assertThrows(IllegalArgumentException::class.java) { InvoiceAuditEvent.fromOrdinal(-1) }
    }

    @Test
    fun `RS-8 - exactly sixteen event codes exist, including receipt assignment events`() {
        assertEquals(16, InvoiceAuditEvent.entries.size)
    }

    @Test
    fun `XLS-4 - labelKey follows the journal-event i18n key convention for every entry`() {
        InvoiceAuditEvent.entries.forEach { event ->
            assertEquals("journal.event.${event.name}", event.labelKey)
        }
    }
}
