package de.littletrotter.ui.view.mail

import de.littletrotter.application.invoice.port.inbound.InvoiceIdByNumberQueryPort

/**
 * Resolves a journal row's reference key to an invoice id so the cell can link into the invoice.
 * A reference is an invoice number only for invoice mails; quotes and invoices deleted since the
 * mail was sent resolve to nothing and stay plain text. A page is resolved up front with one exact
 * number lookup, so rendering the cells only reads the remembered answers until the next [load].
 */
internal class MailJournalInvoiceLinks(private val invoiceIdByNumber: InvoiceIdByNumberQueryPort) {

    private var resolved: Map<String, Long> = emptyMap()

    fun load(referenceKeys: Collection<String>) {
        resolved = invoiceIdByNumber.findIdsByNumbers(referenceKeys.toSet())
    }

    fun reset() {
        resolved = emptyMap()
    }

    fun invoiceIdOf(referenceKey: String): Long? = resolved[referenceKey]
}
