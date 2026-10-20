package de.littletrotter.application.invoice.port.inbound

/** Input port for reading the receipt pool: filtered, optionally sorted pages, single receipts,
 *  usage labels, edit locks and the stored PDF bytes. */
interface ReceiptPoolQueryPort {
    fun findReceiptViews(pageIndex: Int, pageSize: Int, filterText: String?): List<ReceiptView>

    fun findReceiptViewsSorted(pageIndex: Int, pageSize: Int, filterText: String?, sort: ReceiptSort): List<ReceiptView>

    /** Throws the application-level "not found" error for an unknown id. */
    fun findReceiptView(id: Long): ReceiptView

    fun countAll(filterText: String?): Long

    fun getBytes(receiptId: Long): ByteArray

    /** Usage labels for a whole page of receipts in one query; receipts without usage map to [unusedText]. */
    fun findUsageSummaries(receiptIds: List<Long>, unusedText: String, invoiceLevelLabel: String): Map<Long, String>

    /** The subset of [receiptIds] whose edit is locked by an issued invoice. */
    fun findLockedReceiptIds(receiptIds: List<Long>): Set<Long>
}
