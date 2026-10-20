package de.littletrotter.ui.view.journal.composer

import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StatusFamilyComposerTest {

    @Test
    fun `a forward status transition names old then new`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_UPDATED, detail = """{"status": {"old": "0", "new": "1"}}""")
        val line = testLine(action = InvoiceAuditAction.STATUS_CHANGED, rows = listOf(row))

        val result = StatusFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.statusChanged(invoice.status.draft,invoice.status.review)", result)
    }

    /** A backwards transition (IN_REVIEW → DRAFT) renders exactly like a forward one. */
    @Test
    fun `a backwards status transition (IN_REVIEW to DRAFT) renders correctly`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_UPDATED, detail = """{"status": {"old": "1", "new": "0"}}""")
        val line = testLine(action = InvoiceAuditAction.STATUS_CHANGED, rows = listOf(row))

        val result = StatusFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.statusChanged(invoice.status.review,invoice.status.draft)", result)
    }

    /** Override reason appended when present in the diff (defensive-only today; see this
     *  composer's own KDoc on why no application write currently populates it). */
    @Test
    fun `an override reason column, when present, is appended to the sentence`() {
        val row = testRow(
            event = InvoiceAuditEvent.INVOICE_UPDATED,
            detail = """{"status": {"old": "1", "new": "2"}, "override_reason": {"old": null, "new": "Kunde bestätigt telefonisch"}}""",
        )
        val line = testLine(action = InvoiceAuditAction.STATUS_CHANGED, rows = listOf(row))

        val result = StatusFamilyComposer.compose(line, testCtx())

        assertEquals(
            "journal.what.overrideReason(journal.what.statusChanged(invoice.status.review,invoice.status.issued),Kunde bestätigt telefonisch)",
            result,
        )
    }

    @Test
    fun `no override reason column means no override sentence`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_UPDATED, detail = """{"status": {"old": "0", "new": "1"}}""")
        val line = testLine(action = InvoiceAuditAction.STATUS_CHANGED, rows = listOf(row))

        val result = StatusFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.statusChanged(invoice.status.draft,invoice.status.review)", result)
    }

    @Test
    fun `invoice creation names the initial status from the snapshot`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_CREATED, detail = """{"status": 0}""")
        val line = testLine(action = InvoiceAuditAction.INVOICE_CREATED, rows = listOf(row))

        val result = StatusFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.statusSet(invoice.status.draft)", result)
    }

    @Test
    fun `invoice deletion has its own fixed sentence, independent of any status diff`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_DELETED, detail = """{"status": 0}""")
        val line = testLine(action = InvoiceAuditAction.INVOICE_DELETED, rows = listOf(row))

        val result = StatusFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.invoiceDeleted", result)
    }

    @Test
    fun `an out-of-range status ordinal in a diff falls back to its own raw text`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_UPDATED, detail = """{"status": {"old": "99", "new": "1"}}""")
        val line = testLine(action = InvoiceAuditAction.STATUS_CHANGED, rows = listOf(row))

        val result = StatusFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.statusChanged(99,invoice.status.review)", result)
    }

    @Test
    fun `an out-of-range status ordinal in the creation snapshot falls back to its own raw text`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_CREATED, detail = """{"status": 99}""")
        val line = testLine(action = InvoiceAuditAction.INVOICE_CREATED, rows = listOf(row))

        val result = StatusFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.statusSet(99)", result)
    }

    /** An override reason may also arrive as a flat INSERT-shaped snapshot value, not only a diff —
     *  the [de.littletrotter.ui.view.journal.composer.JournalComposerSupport.firstSnapshot] fallback
     *  half of the elvis chain. */
    @Test
    fun `an override reason present only as a snapshot value is still appended`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_CREATED, detail = """{"status": 0, "override_reason": "Sonderfall"}""")
        val line = testLine(action = InvoiceAuditAction.INVOICE_CREATED, rows = listOf(row))

        val result = StatusFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.overrideReason(journal.what.statusSet(invoice.status.draft),Sonderfall)", result)
    }

    @Test
    fun `a blank override reason is treated the same as absent, no override sentence`() {
        val row = testRow(
            event = InvoiceAuditEvent.INVOICE_UPDATED,
            detail = """{"status": {"old": "0", "new": "1"}, "override_reason": {"old": null, "new": "   "}}""",
        )
        val line = testLine(action = InvoiceAuditAction.STATUS_CHANGED, rows = listOf(row))

        val result = StatusFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.statusChanged(invoice.status.draft,invoice.status.review)", result)
    }

    @Test
    fun `a null old status value in a diff falls back to a blank on that side only`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_UPDATED, detail = """{"status": {"old": null, "new": "1"}}""")
        val line = testLine(action = InvoiceAuditAction.STATUS_CHANGED, rows = listOf(row))

        val result = StatusFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.statusChanged(,invoice.status.review)", result)
    }

    @Test
    fun `a null new status value in a diff falls back to a blank on that side only`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_UPDATED, detail = """{"status": {"old": "1", "new": null}}""")
        val line = testLine(action = InvoiceAuditAction.STATUS_CHANGED, rows = listOf(row))

        val result = StatusFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.statusChanged(invoice.status.review,)", result)
    }

    @Test
    fun `an out-of-range status ordinal on the new side of a diff falls back to its own raw text`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_UPDATED, detail = """{"status": {"old": "1", "new": "99"}}""")
        val line = testLine(action = InvoiceAuditAction.STATUS_CHANGED, rows = listOf(row))

        val result = StatusFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.statusChanged(invoice.status.review,99)", result)
    }

    @Test
    fun `no status field anywhere yields null, letting the fallback take over`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_UPDATED, detail = """{"due_date": {"old": "2026-01-01", "new": "2026-02-01"}}""")
        val line = testLine(action = InvoiceAuditAction.SEND_REQUESTED, rows = listOf(row))

        assertNull(StatusFamilyComposer.compose(line, testCtx()))
    }
}
