package de.littletrotter.domain.compliance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * [InvoiceAuditAction.fromOrdinal] is the read-side inverse of the SMALLINT
 * `capture_invoice_audit_event()` writes into `invoice_audit_journal.action`
 * (`V1__baseline_schema.sql`, section 13, `chk_invoice_audit_journal_action`). Pins the exact
 * 0..22 literal mapping the SQL comment documents — a drift here is exactly the drift the
 * baseline file's own enum-CHECK convention says must never happen. Same shape as
 * [InvoiceAuditEventTest].
 */
class InvoiceAuditActionTest {

    @Test
    fun `II4-A - fromOrdinal maps every SQL-side action code to its Kotlin enum entry in order`() {
        assertEquals(InvoiceAuditAction.UNSPECIFIED, InvoiceAuditAction.fromOrdinal(0))
        assertEquals(InvoiceAuditAction.INVOICE_CREATED, InvoiceAuditAction.fromOrdinal(1))
        assertEquals(InvoiceAuditAction.HEADER_CHANGED, InvoiceAuditAction.fromOrdinal(2))
        assertEquals(InvoiceAuditAction.RECIPIENT_CHANGED, InvoiceAuditAction.fromOrdinal(3))
        assertEquals(InvoiceAuditAction.ITEM_ADDED, InvoiceAuditAction.fromOrdinal(4))
        assertEquals(InvoiceAuditAction.ITEM_CHANGED, InvoiceAuditAction.fromOrdinal(5))
        assertEquals(InvoiceAuditAction.ITEM_DELETED, InvoiceAuditAction.fromOrdinal(6))
        assertEquals(InvoiceAuditAction.RECALCULATED, InvoiceAuditAction.fromOrdinal(7))
        assertEquals(InvoiceAuditAction.STATUS_CHANGED, InvoiceAuditAction.fromOrdinal(8))
        assertEquals(InvoiceAuditAction.SEND_REQUESTED, InvoiceAuditAction.fromOrdinal(9))
        assertEquals(InvoiceAuditAction.DOCUMENT_GENERATED, InvoiceAuditAction.fromOrdinal(10))
        assertEquals(InvoiceAuditAction.VALIDATION_RECORDED, InvoiceAuditAction.fromOrdinal(11))
        assertEquals(InvoiceAuditAction.STORNO, InvoiceAuditAction.fromOrdinal(12))
        assertEquals(InvoiceAuditAction.RECEIPT_ASSIGNED, InvoiceAuditAction.fromOrdinal(13))
        assertEquals(InvoiceAuditAction.RECEIPT_DETACHED, InvoiceAuditAction.fromOrdinal(14))
        assertEquals(InvoiceAuditAction.TRIP_SAVED, InvoiceAuditAction.fromOrdinal(15))
        assertEquals(InvoiceAuditAction.REVERSE_CHARGE_DECIDED, InvoiceAuditAction.fromOrdinal(16))
        assertEquals(InvoiceAuditAction.INVOICE_DELETED, InvoiceAuditAction.fromOrdinal(17))
        assertEquals(InvoiceAuditAction.DOCUMENT_REGENERATED, InvoiceAuditAction.fromOrdinal(18))
        assertEquals(InvoiceAuditAction.DOCUMENT_RESTORED, InvoiceAuditAction.fromOrdinal(19))
        assertEquals(InvoiceAuditAction.DOCUMENT_REPRODUCED, InvoiceAuditAction.fromOrdinal(20))
        assertEquals(InvoiceAuditAction.DOCUMENT_REPRODUCTION_SEND_BLOCKED, InvoiceAuditAction.fromOrdinal(21))
        assertEquals(InvoiceAuditAction.CREATED_FROM_QUOTE, InvoiceAuditAction.fromOrdinal(22))
    }

    @Test
    fun `II4-A - every entry's own ordinal round-trips through fromOrdinal`() {
        InvoiceAuditAction.entries.forEach { action ->
            assertEquals(action, InvoiceAuditAction.fromOrdinal(action.ordinal))
        }
    }

    @Test
    fun `II4-A - fromOrdinal rejects an out-of-range ordinal instead of silently defaulting`() {
        assertThrows(IllegalArgumentException::class.java) { InvoiceAuditAction.fromOrdinal(23) }
        assertThrows(IllegalArgumentException::class.java) { InvoiceAuditAction.fromOrdinal(-1) }
    }

    @Test
    fun `II4-A - exactly twenty-three action codes exist, 0 (UNSPECIFIED) through 22 (CREATED_FROM_QUOTE)`() {
        assertEquals(23, InvoiceAuditAction.entries.size)
    }

    @Test
    fun `II4-A - labelKey follows the journal-action i18n key convention for every entry`() {
        InvoiceAuditAction.entries.forEach { action ->
            assertEquals("journal.action.${action.name}", action.labelKey)
        }
    }
}
