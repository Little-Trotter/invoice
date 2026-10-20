package de.littletrotter.domain.repository

import de.littletrotter.domain.masterdata.Receipt

interface ReceiptRepository {
    fun findAll(pageIndex: Int = 0, pageSize: Int = 60, filterText: String? = null): List<Receipt>
    /** Sort the complete result before pagination. */
    fun findAllSorted(pageIndex: Int, pageSize: Int, filterText: String?, sort: de.littletrotter.domain.shared.GridSort): List<Receipt> =
        findAll(pageIndex, pageSize, filterText)
    fun count(filterText: String? = null): Long
    fun findById(id: Long): Receipt?
    fun findByChecksum(sha256Checksum: String): Receipt?
    fun save(receipt: Receipt): Receipt

    /** Like [save], but flushing immediately so a violation of
     *  `uq_receipt_sha256_checksum` (V002) surfaces as an exception from this call instead of
     *  at commit time, after the duplicate-recovery in `ReceiptUploadService.upload` has already been
     *  passed. Default delegates to [save] — in-memory fakes without a real index don't need
     *  the distinction (same pattern as [InvoiceRepository.saveAndFlush]). */
    fun saveAndFlush(receipt: Receipt): Receipt = save(receipt)

    /** Takes a transaction-scoped lock keyed by the content hash, so that storing and deleting
     *  receipts of the same bytes (which share one content-addressed object) run one after the
     *  other. Must be called inside the transaction that then writes row and object. In-memory
     *  fakes have no concurrency and keep the no-op default. */
    fun lockChecksum(sha256Checksum: String) = Unit
    fun delete(id: Long)
    fun countForItem(invoiceItemId: Long): Long
    fun findForItem(invoiceItemId: Long, pageIndex: Int = 0, pageSize: Int = 60): List<Receipt>
    /** Where a receipt is currently used: the invoice item id + the owning invoice's id and
     *  number, per item (a receipt may hang on several items across several invoices, n:m). */
    fun findReceiptUsage(receiptId: Long): List<ReceiptUsage>

    /** Pool page for the "assign" picker: same filter as [findAll], but the
     *  receipts already attached to [excludeItemId] are removed **in the query**. Filtering them
     *  out client-side would corrupt paging (a page could come back short or empty while further
     *  matches exist) and would still offer rows that do nothing when clicked. */
    fun findAssignable(
        pageIndex: Int = 0,
        pageSize: Int = 60,
        filterText: String? = null,
        excludeItemId: Long? = null,
        /** When set, receipts already attached to this invoice (via
         *  `invoice_receipt`) are excluded in the query. A receipt may hang on the invoice AND on
         *  a position, so this is independent of [excludeItemId]. */
        excludeInvoiceId: Long? = null,
        /** Explicit receipt ids to exclude verbatim, applied in the query itself. Used by
         *  the position picker to remove the "this invoice's own receipts" group (shown separately,
         *  see `ReceiptDialog.findInvoiceOwnReceipts`) from the remaining pool so paging through
         *  that pool can never repeat a row already shown above it. */
        excludeReceiptIds: Set<Long> = emptySet(),
    ): List<Receipt>

    /** Row count matching [findAssignable] — required for correct paging in the picker. */
    fun countAssignable(
        filterText: String? = null,
        excludeItemId: Long? = null,
        excludeInvoiceId: Long? = null,
        excludeReceiptIds: Set<Long> = emptySet(),
    ): Long

    /** Usage for many receipts in ONE query. The per-row lookup the pool
     *  column used before issued one statement per rendered row (N+1) and re-ran on every scroll
     *  and every repaint. Now includes invoice-level usage. */
    fun findReceiptUsageBatch(receiptIds: List<Long>): Map<Long, List<ReceiptUsage>>

    /** Receipts attached to an invoice as a whole, paged. */
    fun findForInvoice(invoiceId: Long, pageIndex: Int = 0, pageSize: Int = 60): List<Receipt>

    /** Row count matching [findForInvoice]. */
    fun countForInvoice(invoiceId: Long): Long

    /** All item-level receipts of one invoice in ONE query, keyed by item id.
     *  The export path (DocumentJobPublisher) needs real receipt data per item for the worker;
     *  a per-item lookup would be an N+1. */
    fun findItemReceiptsForInvoice(invoiceId: Long): Map<Long, List<Receipt>>

    /** File names for many receipts in ONE query, keyed by id. The journal's own
     *  `invoice_receipt`/`invoice_item_receipt` link tables carry only `receipt_id` (no file
     *  name — see their own baseline column comments), so
     *  [de.littletrotter.ui.view.journal.composer.ReceiptFamilyComposer]'s "Beleg X gelöst/erfasst"
     *  sentence needs this lookup; [de.littletrotter.infrastructure.persistence
     *  .PanacheReceiptRepository] batches it in ONE query, same idiom as [findReceiptUsageBatch].
     *  Defaults to a per-id [findById] fold so the small hand-written test fakes of this interface
     *  (in-memory maps, never at journal scale) keep compiling without implementing it themselves. */
    fun findFileNames(receiptIds: Collection<Long>): Map<Long, String> =
        receiptIds.mapNotNull { id -> findById(id)?.let { id to it.fileName } }.toMap()
}

/** One place a receipt is attached. [invoiceReadOnly] mirrors [de.littletrotter.domain.invoice.Invoice.isReadOnly]
 *  for the delete guard. [invoiceIssued] is the narrower `ISSUED`/`CANCELLED` predicate the
 *  receipt edit-lock guard uses — a draft/in-review
 *  invoice must not lock the receipt's description/documentDate, only an issued one does.
 *
 *  [positionId]/[invoiceItemId] are `null` when the receipt hangs on the invoice
 *  as a whole (`invoice_receipt`) rather than on one position. */
data class ReceiptUsage(
    val invoiceId: Long,
    val invoiceNumber: String,
    /** null = Verwendung an der Rechnung als Ganzes, sonst die Positions-Id. */
    val positionId: String? = null,
    /** null = Verwendung an der Rechnung als Ganzes. */
    val invoiceItemId: Long? = null,
    val invoiceReadOnly: Boolean = false,
    val invoiceIssued: Boolean = false,
)
