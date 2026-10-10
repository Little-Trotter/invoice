package de.littletrotter.ui.view.journal.composer

import de.littletrotter.application.invoice.InvoiceAuditActionLine
import de.littletrotter.domain.compliance.InvoiceAuditEvent

/**
 * The "storno" family:
 * [STORNO][de.littletrotter.domain.compliance.InvoiceAuditAction.STORNO], the one action whose
 * rows live on TWO invoices in a single transaction: `InvoiceStornoService.createCreditNote`
 * inserts the new correction invoice AND updates the original in the same business_tx (see the
 * spec's own "facts to design around").
 *
 * The GLOBAL panel's group carries both invoices ([InvoiceAuditActionLine.invoiceIds] has two
 * entries there — see [InvoiceAuditActionLine.of]'s own KDoc on how that list is built), so its
 * line names both documents: "Storniert: <original> → Korrekturrechnung <credit note>", the credit
 * note identified by its own [InvoiceAuditEvent.INVOICE_CREATED] row (the only row of the two sides
 * that is an INSERT, never an UPDATE). The PER-INVOICE panel filters to one invoice's own rows
 * (`AuditJournalQueryService.findActionsByInvoice` scopes findByBusinessTx by `invoiceId`), so
 * only one side's rows ever reach this composer there — it renders that side's own, generic
 * sentence rather than naming a document it was never handed.
 */
val StornoFamilyComposer = JournalWhatComposer { line, ctx ->
    val tr = ctx.tr
    val unnumbered = tr("journal.invoice.unnumbered", emptyList())
    val creditNoteRow = line.rows.firstOrNull { it.event == InvoiceAuditEvent.INVOICE_CREATED }

    if (line.invoiceIds.size >= 2) {
        val creditNoteNumber = creditNoteRow?.invoiceNumber ?: unnumbered
        val originalNumber = line.rows.firstOrNull { it.invoiceId != creditNoteRow?.invoiceId }?.invoiceNumber ?: unnumbered
        tr("journal.what.stornoBoth", listOf(originalNumber, creditNoteNumber))
    } else if (creditNoteRow != null) {
        tr("journal.what.stornoCreditNote", emptyList())
    } else {
        tr("journal.what.stornoOriginal", emptyList())
    }
}
