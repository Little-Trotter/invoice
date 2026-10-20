package de.littletrotter.application.invoice.port.inbound

import de.littletrotter.application.dto.ReceiptRowDto
import de.littletrotter.shared.receipt.InvoiceDocumentBudget

/**
 * Input port for reading receipts attached to an invoice or one of its items, the IR-2
 * document budget, and a receipt's raw PDF bytes; the read half of the receipt slice
 * (write half: [AttachReceiptsUseCase]).
 *
 * Extended by the "pick a receipt to assign" search/count (both targets) and the pool's
 * usage-summary lookup for [de.littletrotter.ui.view.invoice.ReceiptDialog]/
 * [de.littletrotter.ui.view.invoice.TripDialog]. Kept on this read-only port rather than
 * [AttachReceiptsUseCase]: searching/counting the assignable pool mutates nothing, same reasoning
 * as every other method here.
 */
interface InvoiceReceiptQueryPort {
    fun findReceiptsForInvoice(invoiceId: Long): List<ReceiptRowDto>
    fun findItemReceiptsForInvoice(invoiceId: Long): Map<Long, List<ReceiptRowDto>>
    fun findReceiptsForItem(invoiceItemId: Long): List<ReceiptRowDto>
    fun countReceiptsForItem(invoiceItemId: Long): Long
    fun invoiceDocumentBudget(invoiceId: Long): InvoiceDocumentBudget
    fun getBytes(receiptId: Long): ByteArray

    /** One page of receipts NOT already attached to [invoiceItemId], for the item
     *  target's "assign from pool" picker; see `ReceiptLinkService.findAssignableForItem`. */
    fun findAssignableForItem(
        invoiceItemId: Long?,
        pageIndex: Int,
        pageSize: Int,
        filterText: String? = null,
        excludeReceiptIds: Set<Long> = emptySet(),
    ): List<ReceiptRowDto>

    /** Row count matching [findAssignableForItem]. */
    fun countAssignableForItem(invoiceItemId: Long?, filterText: String? = null, excludeReceiptIds: Set<Long> = emptySet()): Long

    /** One page of receipts NOT already attached to [invoiceId] at invoice level, for
     *  the invoice target's "assign from pool" picker; see `ReceiptLinkService.findAssignableForInvoice`. */
    fun findAssignableForInvoice(invoiceId: Long?, pageIndex: Int, pageSize: Int, filterText: String? = null): List<ReceiptRowDto>

    /** Row count matching [findAssignableForInvoice]. */
    fun countAssignableForInvoice(invoiceId: Long?, filterText: String? = null): Long

    /** Readable usage summary per receipt id, for the pool picker's usage column; see
     *  `ReceiptPoolService.findUsageSummaries`. */
    fun findUsageSummaries(receiptIds: List<Long>, unusedText: String, invoiceLevelLabel: String): Map<Long, String>
}
