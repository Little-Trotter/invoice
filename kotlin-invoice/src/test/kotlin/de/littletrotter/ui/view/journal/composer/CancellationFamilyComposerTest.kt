package de.littletrotter.ui.view.journal.composer

import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** A `CANCELLATION` business_tx lives on two invoices; the global panel's line names both,
 *  the per-invoice panel (rows filtered to one side) renders that side's own generic sentence. */
class CancellationFamilyComposerTest {

    private fun originalRow() = testRow(seq = 1L, businessTx = 900L, invoiceId = 1L, invoiceNumber = "RE-2026-0001", event = InvoiceAuditEvent.INVOICE_UPDATED, action = InvoiceAuditAction.CANCELLATION, detail = """{"status": {"old": "2", "new": "4"}}""")
    private fun creditNoteRow() = testRow(seq = 2L, businessTx = 900L, invoiceId = 2L, invoiceNumber = "RE-2026-0002", event = InvoiceAuditEvent.INVOICE_CREATED, action = InvoiceAuditAction.CANCELLATION, detail = """{"type_code": 2, "original_invoice_number": "RE-2026-0001"}""")

    @Test
    fun `the global panel names both the original and the credit note`() {
        val line = testLine(businessTx = 900L, action = InvoiceAuditAction.CANCELLATION, rows = listOf(originalRow(), creditNoteRow()))

        val result = CancellationFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.stornoBoth(RE-2026-0001,RE-2026-0002)", result)
    }

    @Test
    fun `the original invoice's own tab renders its generic side of the cancellation`() {
        val line = testLine(businessTx = 900L, action = InvoiceAuditAction.CANCELLATION, rows = listOf(originalRow()))

        val result = CancellationFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.stornoOriginal", result)
    }

    @Test
    fun `the credit note's own tab renders its generic side of the cancellation`() {
        val line = testLine(businessTx = 900L, action = InvoiceAuditAction.CANCELLATION, rows = listOf(creditNoteRow()))

        val result = CancellationFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.stornoCreditNote", result)
    }

    @Test
    fun `an unnumbered original falls back to the unnumbered placeholder`() {
        val original = originalRow().copy(invoiceNumber = null)
        val line = testLine(businessTx = 900L, action = InvoiceAuditAction.CANCELLATION, rows = listOf(original, creditNoteRow()))

        val result = CancellationFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.stornoBoth(journal.invoice.unnumbered,RE-2026-0002)", result)
    }

    @Test
    fun `an unnumbered credit note falls back to the unnumbered placeholder`() {
        val creditNote = creditNoteRow().copy(invoiceNumber = null)
        val line = testLine(businessTx = 900L, action = InvoiceAuditAction.CANCELLATION, rows = listOf(originalRow(), creditNote))

        val result = CancellationFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.stornoBoth(RE-2026-0001,journal.invoice.unnumbered)", result)
    }

    /** Defensive edge case: two invoice ids on the line but neither row is the credit note's own
     *  `INVOICE_CREATED` insert (never expected from `InvoiceCancellationService` in practice) — the
     *  credit-note side still falls back to the unnumbered placeholder instead of throwing. */
    @Test
    fun `two invoices with no INVOICE_CREATED row among them still falls back cleanly`() {
        val first = testRow(seq = 1L, businessTx = 900L, invoiceId = 1L, invoiceNumber = "RE-2026-0001", event = InvoiceAuditEvent.INVOICE_UPDATED, action = InvoiceAuditAction.CANCELLATION)
        val second = testRow(seq = 2L, businessTx = 900L, invoiceId = 2L, invoiceNumber = "RE-2026-0002", event = InvoiceAuditEvent.INVOICE_UPDATED, action = InvoiceAuditAction.CANCELLATION)
        val line = testLine(businessTx = 900L, action = InvoiceAuditAction.CANCELLATION, rows = listOf(first, second))

        val result = CancellationFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.stornoBoth(RE-2026-0001,journal.invoice.unnumbered)", result)
    }
}
