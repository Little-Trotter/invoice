package de.littletrotter.application.invoice.port.inbound

import de.littletrotter.application.command.CommandEntryPoint
import de.littletrotter.application.dto.ReceiptDetachSelection
import de.littletrotter.application.dto.ReceiptRowDto
import de.littletrotter.application.invoice.ReceiptUploadMode
import java.time.LocalDate

/**
 * Input port for uploading a receipt into the pool and attaching/detaching it at invoice- or
 * item-level; the write half of the receipt slice (read half: [InvoiceReceiptQueryPort]).
 */
interface AttachReceiptsUseCase : CommandEntryPoint {
    fun upload(
        fileName: String?,
        description: String?,
        pdfData: ByteArray,
        documentDate: LocalDate?,
        mode: ReceiptUploadMode = ReceiptUploadMode.REDUCE,
    ): ReceiptRowDto

    fun addReceiptsToInvoice(invoiceId: Long, receiptIds: List<Long>, expectedVersion: Long? = null)

    fun addReceiptsToItem(invoiceItemId: Long, receiptIds: List<Long>, expectedVersion: Long? = null)

    fun detachReceipts(invoiceId: Long, selection: ReceiptDetachSelection, expectedVersion: Long? = null)

    /** Removes specific receipts from one item, keeping the rest; see
     *  `ReceiptLinkService.removeReceiptsFromItem`'s own KDoc (re-expressed through [detachReceipts]
     *  there, so this and [detachReceipts] never disagree). */
    fun removeReceiptsFromItem(invoiceItemId: Long, receiptIds: List<Long>, expectedVersion: Long? = null)

    /** Removes specific receipts from the invoice's own (not item-level) set, keeping the
     *  rest; see `ReceiptLinkService.removeReceiptsFromInvoice`'s own KDoc. */
    fun removeReceiptsFromInvoice(invoiceId: Long, receiptIds: List<Long>, expectedVersion: Long? = null)
}
