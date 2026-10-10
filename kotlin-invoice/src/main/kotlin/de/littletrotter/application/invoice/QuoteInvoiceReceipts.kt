package de.littletrotter.application.invoice

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Stores the offer's PDFs in the receipt pool and links them to the invoice. */
@ApplicationScoped
class QuoteInvoiceReceipts @Inject constructor(
    private val uploads: ReceiptUploadService,
    private val links: ReceiptLinkService,
) {

    /** Must run in the invoice's transaction: a failing link rolls the draft back instead of leaving it without its files. */
    fun attach(invoiceId: Long, sources: List<QuoteReceiptSource>) {
        if (sources.isEmpty()) return
        val ids = sources.map { uploads.upload(it.fileName, it.description, it.bytes, it.documentDate).id!! }
        links.addReceiptsToInvoice(invoiceId, ids)
    }
}
