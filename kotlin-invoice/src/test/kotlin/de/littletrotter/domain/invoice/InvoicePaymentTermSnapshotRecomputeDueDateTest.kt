package de.littletrotter.domain.invoice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * finding-fixes (2026-08-31, PO decision) — [Invoice.recomputeDueDateForSend]'s three cases: no
 * previous due date (stays null), a frozen payment-term snapshot day-count (re-derive from it),
 * and no payment term at all (preserve the draft's own day-gap) — plus the defensive no-previous-
 * invoiceDate edge case within the third branch. See that function's own KDoc for the full
 * reasoning (`NumberJournalReportServiceIT` reproduced the production gap this closes).
 */
class InvoicePaymentTermSnapshotRecomputeDueDateTest {

    private fun invoiceWithSnapshot(dueInDays: Int?): Invoice = Invoice().apply {
        if (dueInDays != null) {
            paymentTermSnapshot = InvoicePaymentTermSnapshot().apply { this.dueInDays = dueInDays }
        }
    }

    @Test
    fun `case 1 - never had a due date - stays null regardless of everything else`() {
        val invoice = invoiceWithSnapshot(dueInDays = 14).apply {
            invoiceDate = LocalDate.of(2026, 8, 31)
        }

        invoice.recomputeDueDateForSend(previousInvoiceDate = LocalDate.of(2026, 3, 1), previousDueDate = null)

        assertNull(invoice.dueDate)
    }

    @Test
    fun `case 2 - a frozen payment-term snapshot re-derives dueDate from the new invoiceDate`() {
        val invoice = invoiceWithSnapshot(dueInDays = 14).apply {
            invoiceDate = LocalDate.of(2026, 8, 31)
        }

        // The draft's own values are deliberately very different from the snapshot's day-count —
        // proves the snapshot wins over the preserved-delta fallback whenever it is present.
        invoice.recomputeDueDateForSend(
            previousInvoiceDate = LocalDate.of(2026, 3, 1),
            previousDueDate = LocalDate.of(2026, 3, 3),
        )

        assertEquals(LocalDate.of(2026, 9, 14), invoice.dueDate, "2026-08-31 + 14 days")
    }

    @Test
    fun `case 3 - no payment term at all - preserves the draft's own day-gap onto the new invoiceDate`() {
        val invoice = invoiceWithSnapshot(dueInDays = null).apply {
            invoiceDate = LocalDate.of(2026, 8, 31)
        }

        // Draft: invoiceDate 2026-03-01, dueDate 2026-03-15 -> a 14-day gap, preserved.
        invoice.recomputeDueDateForSend(
            previousInvoiceDate = LocalDate.of(2026, 3, 1),
            previousDueDate = LocalDate.of(2026, 3, 15),
        )

        assertEquals(LocalDate.of(2026, 9, 14), invoice.dueDate, "2026-08-31 + the draft's own 14-day gap")
    }

    @Test
    fun `case 3b - no payment term and no previous invoiceDate - defensive no-op, previous dueDate kept as-is`() {
        val invoice = invoiceWithSnapshot(dueInDays = null).apply {
            invoiceDate = LocalDate.of(2026, 8, 31)
        }

        invoice.recomputeDueDateForSend(previousInvoiceDate = null, previousDueDate = LocalDate.of(2026, 3, 15))

        assertEquals(LocalDate.of(2026, 3, 15), invoice.dueDate)
    }

    // date invariants (PO 2026-09-02) — Invoice.invoiceDate is no longer nullable, so the
    // "this invoice never had an invoiceDate at all" defensive no-op branch this used to pin
    // (`invoiceDate ?: return`, InvoicePaymentTermSnapshot.kt) was removed as unreachable; case 3b
    // above already covers the remaining nullable input, [previousInvoiceDate].
}
