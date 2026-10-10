package de.littletrotter.application.invoice

import de.littletrotter.application.quote.port.inbound.QuoteAttachment
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentKind
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteDetailQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteDocument
import de.littletrotter.application.quote.port.inbound.QuoteDocumentFileQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteDocumentMark
import de.littletrotter.application.quote.port.inbound.QuoteDocumentQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteDocumentStatus
import de.littletrotter.shared.GermanTime
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Reads an accepted offer from the offer service and sorts its files into what becomes a receipt
 * and what stays at the offer.
 *
 * The offer document and the customer's PDF attachments become receipts, because receipts are what
 * is sent to the customer together with the e-invoice. Internal attachments are for the offer's
 * own staff and never leave the offer; customer attachments that are not PDFs stay there too,
 * because the receipt pool only takes PDFs. Both groups are named in the message-center note so
 * the clerk knows what was not taken over.
 *
 * All calls are HTTP and run before any database transaction: a slow or failing offer service
 * must never hold a transaction open.
 */
@ApplicationScoped
class QuoteOfferReader @Inject constructor(
    private val details: QuoteDetailQueryPort,
    private val documents: QuoteDocumentQueryPort,
    private val documentFiles: QuoteDocumentFileQueryPort,
    private val attachments: QuoteAttachmentQueryPort,
) {

    /** @throws QuoteInvoiceRejectedException when the offer has no finished PDF document. */
    fun read(command: CreateInvoiceFromQuoteCommand): PreparedQuoteInvoice {
        val quoteId = command.quoteId.toString()
        val detail = details.find(quoteId)
        val offerPdf = offerDocument(quoteId)
            ?: throw QuoteInvoiceRejectedException("Das Angebotsdokument (PDF) liegt nicht vor.")
        val receipts = mutableListOf(
            QuoteReceiptSource(
                fileName = offerPdf.fileName,
                description = description(command, "Angebot", offerPdf.fileName),
                documentDate = detail.quoteDate,
                bytes = offerPdf.bytes,
            ),
        )
        val skipped = mutableListOf<String>()
        attachments.attachments(quoteId).forEach { attachment ->
            if (attachment.kind == QuoteAttachmentKind.INTERNAL || !isPdf(attachment.mimeType)) {
                skipped += attachment.fileName
            } else {
                receipts += QuoteReceiptSource(
                    fileName = attachment.fileName,
                    description = attachmentDescription(command, attachment),
                    documentDate = GermanTime.dayOf(attachment.uploadedAt),
                    bytes = attachments.content(quoteId, attachment.id).bytes,
                )
            }
        }
        return PreparedQuoteInvoice(command, detail, receipts, skipped)
    }

    /** The document the customer actually received (dispatch), else the one that passed review. */
    private fun offerDocument(quoteId: String): OfferPdf? {
        val completed = documents.documents(quoteId).filter { it.status == QuoteDocumentStatus.COMPLETED }
        val chosen = newest(completed, QuoteDocumentMark.DISPATCH) ?: newest(completed, QuoteDocumentMark.REVIEW) ?: return null
        val file = documentFiles.pdf(quoteId, chosen.id)
        return OfferPdf(file.fileName, file.bytes)
    }

    private fun newest(documents: List<QuoteDocument>, mark: QuoteDocumentMark): QuoteDocument? =
        documents.filter { it.mark == mark }.maxByOrNull { it.completedAt ?: it.requestedAt }

    private fun description(command: CreateInvoiceFromQuoteCommand, kind: String, fileName: String) =
        "Angebot ${command.quoteNumber}: $kind $fileName"

    /** A library file is named by its title and version, an uploaded one by its file name. */
    private fun attachmentDescription(command: CreateInvoiceFromQuoteCommand, attachment: QuoteAttachment) =
        if (attachment.libraryTitle != null && attachment.versionNo != null) {
            "Angebot ${command.quoteNumber}: ${attachment.libraryTitle} (Fassung ${attachment.versionNo})"
        } else {
            description(command, "Anhang", attachment.fileName)
        }

    private fun isPdf(mimeType: String) = mimeType.substringBefore(';').trim().equals("application/pdf", ignoreCase = true)

    private class OfferPdf(val fileName: String, val bytes: ByteArray)
}
