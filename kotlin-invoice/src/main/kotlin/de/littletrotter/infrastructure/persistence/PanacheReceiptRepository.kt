package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.isIssued
import de.littletrotter.domain.invoice.isReadOnly
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.domain.repository.ReceiptUsage
import de.littletrotter.domain.shared.likePattern
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.TypedQuery
import de.littletrotter.domain.shared.FILTER_PARAM
import de.littletrotter.domain.shared.likeFilter

@ApplicationScoped
class PanacheReceiptRepository : BasePanacheRepository(), ReceiptRepository {

    companion object {
        /** Newest document first, everywhere a picker or list reads receipts: the (mandatory)
         *  document date leads, upload time breaks ties, and id keeps paging stable when both
         *  match. One constant so [findAll], [findAssignable], [findForItem] and
         *  [findForInvoice] can never drift apart — the UI's in-memory sort
         *  (`ReceiptDialog.findInvoiceOwnReceipts`) mirrors exactly this order. */
        private const val NEWEST_FIRST_ORDER = "ORDER BY r.documentDate DESC, r.uploadedAt DESC, r.id DESC"

        private const val PARAM_INVOICE_ID = "invoiceId"

        // findReceiptUsageBatch — item-level projection column order: r.id, invoice.id,
        // invoiceNumber, positionId, i.id, status.
        private const val ITEM_LINK_POSITION_ID_COLUMN = 3
        private const val ITEM_LINK_INVOICE_ITEM_ID_COLUMN = 4
        private const val ITEM_LINK_STATUS_COLUMN = 5

        // findReceiptUsageBatch — invoice-level projection column order: r.id, i.id,
        // invoiceNumber, status.
        private const val INVOICE_LINK_STATUS_COLUMN = 3

        // findReceiptUsage — item-level projection column order: invoiceId, invoiceNumber,
        // positionId, itemId, status.
        private const val SINGLE_ITEM_LINK_ITEM_ID_COLUMN = 3
        private const val SINGLE_ITEM_LINK_STATUS_COLUMN = 4
    }

    /** QOD-3 — shared WHERE-building for [findAll]/[count] so the two can never drift apart (same
     *  "clause builder + param applier" idiom [PanacheRecipientRepository]'s token search uses). */
    private fun searchWhere(filterText: String?): String =
        if (filterText.isNullOrBlank()) "" else " WHERE ${likeFilter("r.fileName")} OR ${likeFilter("r.description")}"

    private fun <T> TypedQuery<T>.applySearchParam(filterText: String?): TypedQuery<T> {
        if (!filterText.isNullOrBlank()) setParameter(FILTER_PARAM, likePattern(filterText))
        return this
    }

    override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?): List<Receipt> =
        findAllSorted(pageIndex, pageSize, filterText, de.littletrotter.domain.shared.GridSort("", de.littletrotter.domain.shared.SortDirection.ASC))

    override fun findAllSorted(pageIndex: Int, pageSize: Int, filterText: String?, sort: de.littletrotter.domain.shared.GridSort): List<Receipt> {
        // SqlSourceToSinkFlow — false positive, same shape as PanacheSenderProfileRepository's own
        // findAllSorted: searchWhere/likeFilter only ever contribute hardcoded column-path
        // literals, ListSortOrder.receipts is a closed `when` allowlist, and filterText's own
        // value only ever reaches the query as a bound FILTER_PARAM parameter (applySearchParam),
        // never concatenated into the JPQL text.
        val query = "SELECT r FROM Receipt r${searchWhere(filterText)} ${ListSortOrder.receipts(sort.field, sort.dir)}"
        return em.createQuery(query, Receipt::class.java)
            .applySearchParam(filterText)
            .setFirstResult(pageIndex * pageSize).setMaxResults(pageSize).resultList
    }

    /** [findAssignable]/[countAssignable]'s own exclusion filter, bundled so [assignableWhere]/
     *  [bindAssignableParams] take one parameter for it instead of four (LongParameterList). */
    private data class AssignableReceiptFilter(
        val filterText: String?,
        val excludeItemId: Long?,
        val excludeInvoiceId: Long?,
        val excludeReceiptIds: Set<Long>,
    )

    /** Shared WHERE-building for [findAssignable]/[countAssignable] so page and count can never
     *  drift apart — a picker whose count says 12 but whose pages yield 9 is worse than no paging. */
    private fun assignableWhere(filter: AssignableReceiptFilter): String {
        val clauses = mutableListOf<String>()
        if (!filter.filterText.isNullOrBlank()) clauses.add("(${likeFilter("r.fileName")} OR ${likeFilter("r.description")})")
        if (filter.excludeItemId != null) {
            clauses.add("r.id NOT IN (SELECT r2.id FROM InvoiceItem i2 JOIN i2.receipts r2 WHERE i2.id = :excludeItemId)")
        }
        if (filter.excludeInvoiceId != null) {
            clauses.add("r.id NOT IN (SELECT r2.id FROM Invoice i2 JOIN i2.receipts r2 WHERE i2.id = :excludeInvoiceId)")
        }
        // Id-based exclusion, applied server-side like the two above, so a page of the
        // remaining pool can never repeat a row the caller already rendered in a separate group.
        if (filter.excludeReceiptIds.isNotEmpty()) clauses.add("r.id NOT IN :excludeReceiptIds")
        return if (clauses.isEmpty()) "" else " WHERE " + clauses.joinToString(" AND ")
    }

    private fun bindAssignableParams(q: TypedQuery<*>, filter: AssignableReceiptFilter) {
        if (!filter.filterText.isNullOrBlank()) q.setParameter(FILTER_PARAM, likePattern(filter.filterText))
        if (filter.excludeItemId != null) q.setParameter("excludeItemId", filter.excludeItemId)
        if (filter.excludeInvoiceId != null) q.setParameter("excludeInvoiceId", filter.excludeInvoiceId)
        if (filter.excludeReceiptIds.isNotEmpty()) q.setParameter("excludeReceiptIds", filter.excludeReceiptIds)
    }

    override fun findAssignable(
        pageIndex: Int,
        pageSize: Int,
        filterText: String?,
        excludeItemId: Long?,
        excludeInvoiceId: Long?,
        excludeReceiptIds: Set<Long>,
    ): List<Receipt> {
        val filter = AssignableReceiptFilter(filterText, excludeItemId, excludeInvoiceId, excludeReceiptIds)
        val q = em.createQuery(
            "SELECT r FROM Receipt r" +
                assignableWhere(filter) +
                " $NEWEST_FIRST_ORDER",
            Receipt::class.java,
        )
        bindAssignableParams(q, filter)
        return q.setFirstResult(pageIndex * pageSize).setMaxResults(pageSize).resultList
    }

    override fun countAssignable(
        filterText: String?,
        excludeItemId: Long?,
        excludeInvoiceId: Long?,
        excludeReceiptIds: Set<Long>,
    ): Long {
        val filter = AssignableReceiptFilter(filterText, excludeItemId, excludeInvoiceId, excludeReceiptIds)
        val q = em.createQuery(
            "SELECT COUNT(r) FROM Receipt r" +
                assignableWhere(filter),
            Long::class.java,
        )
        bindAssignableParams(q, filter)
        return q.singleResult
    }

    override fun findReceiptUsageBatch(receiptIds: List<Long>): Map<Long, List<ReceiptUsage>> {
        if (receiptIds.isEmpty()) return emptyMap()
        val rows = mutableListOf<Pair<Long, ReceiptUsage>>()
        // Item-level links (existing shape: one row per item link).
        em.createQuery(
            "SELECT r.id, i.invoice.id, i.invoice.invoiceNumber, i.positionId, i.id, i.invoice.status " +
                "FROM InvoiceItem i JOIN i.receipts r WHERE r.id IN :receiptIds",
        ).setParameter("receiptIds", receiptIds).resultList.forEach { row ->
            val arr = row as Array<*>
            val status = arr[ITEM_LINK_STATUS_COLUMN] as? InvoiceStatus
            rows += (arr[0] as Number).toLong() to ReceiptUsage(
                invoiceItemId = (arr[ITEM_LINK_INVOICE_ITEM_ID_COLUMN] as Number).toLong(),
                invoiceId = (arr[1] as Number).toLong(),
                invoiceNumber = invoiceNumberOf(arr[2]),
                positionId = arr[ITEM_LINK_POSITION_ID_COLUMN] as String,
                invoiceReadOnly = status?.isReadOnly == true,
                invoiceIssued = status?.isIssued == true,
            )
        }
        // Invoice-level links (positionId/invoiceItemId = null).
        em.createQuery(
            "SELECT r.id, i.id, i.invoiceNumber, i.status " +
                "FROM Invoice i JOIN i.receipts r WHERE r.id IN :receiptIds",
        ).setParameter("receiptIds", receiptIds).resultList.forEach { row ->
            val arr = row as Array<*>
            val status = arr[INVOICE_LINK_STATUS_COLUMN] as? InvoiceStatus
            rows += (arr[0] as Number).toLong() to ReceiptUsage(
                invoiceItemId = null,
                invoiceId = (arr[1] as Number).toLong(),
                invoiceNumber = invoiceNumberOf(arr[2]),
                positionId = null,
                invoiceReadOnly = status?.isReadOnly == true,
                invoiceIssued = status?.isIssued == true,
            )
        }
        return rows.groupBy({ it.first }, { it.second })
    }

    /** `Invoice.invoiceNumber` is mapped through an AttributeConverter, so a projection yields an
     *  [de.littletrotter.domain.invoice.InvoiceNumber], not a String — and the JPQL path is
     *  `i.invoice.invoiceNumber` without `.value` (Hibernate rejects a sub-path on a basic type
     *  with UnknownPathException, which is what an integration test against a real database
     *  surfaced here). */
    private fun invoiceNumberOf(raw: Any?): String = when (raw) {
        null -> ""
        is de.littletrotter.domain.invoice.InvoiceNumber -> raw.value
        else -> raw.toString()
    }

    override fun findById(id: Long): Receipt? = findByIdInternal(id, Receipt::class.java)

    override fun findByChecksum(sha256Checksum: String): Receipt? =
        em.createQuery(
            "SELECT r FROM Receipt r WHERE r.sha256Checksum = :checksum ORDER BY r.uploadedAt DESC",
            Receipt::class.java,
        ).setParameter("checksum", sha256Checksum)
            .setMaxResults(1)
            .resultList.firstOrNull()

    override fun count(filterText: String?): Long {
        val query = "SELECT COUNT(r) FROM Receipt r${searchWhere(filterText)}"
        return em.createQuery(query, Long::class.java)
            .applySearchParam(filterText)
            .singleResult
    }

    override fun countForItem(invoiceItemId: Long): Long =
        em.createQuery(
            "SELECT COUNT(r) FROM InvoiceItem i JOIN i.receipts r WHERE i.id = :itemId",
            Long::class.java,
        ).setParameter("itemId", invoiceItemId).singleResult

    /** Ordered explicitly: without an ORDER BY the database may return the rows of a set in a
     *  different order per call, which makes a paged read drop or duplicate rows between pages. */
    override fun findForItem(invoiceItemId: Long, pageIndex: Int, pageSize: Int): List<Receipt> =
        em.createQuery(
            "SELECT r FROM InvoiceItem i JOIN i.receipts r WHERE i.id = :itemId " +
                NEWEST_FIRST_ORDER,
            Receipt::class.java,
        ).setParameter("itemId", invoiceItemId)
            .setFirstResult(pageIndex * pageSize)
            .setMaxResults(pageSize)
            .resultList

    /** Receipts the invoice carries as a whole (`invoice_receipt`). Ordered
     *  for the same reason as [findForItem]. */
    override fun findForInvoice(invoiceId: Long, pageIndex: Int, pageSize: Int): List<Receipt> =
        em.createQuery(
            "SELECT r FROM Invoice i JOIN i.receipts r WHERE i.id = :invoiceId " +
                NEWEST_FIRST_ORDER,
            Receipt::class.java,
        ).setParameter(PARAM_INVOICE_ID, invoiceId)
            .setFirstResult(pageIndex * pageSize)
            .setMaxResults(pageSize)
            .resultList

    override fun countForInvoice(invoiceId: Long): Long =
        em.createQuery(
            "SELECT COUNT(r) FROM Invoice i JOIN i.receipts r WHERE i.id = :invoiceId",
            Long::class.java,
        ).setParameter(PARAM_INVOICE_ID, invoiceId).singleResult

    /** One query for all item-level links of an invoice (export path). */
    override fun findItemReceiptsForInvoice(invoiceId: Long): Map<Long, List<Receipt>> {
        val rows = em.createQuery(
            "SELECT i.id, r FROM InvoiceItem i JOIN i.receipts r WHERE i.invoice.id = :invoiceId",
        ).setParameter(PARAM_INVOICE_ID, invoiceId).resultList
        return rows.map { row ->
            val arr = row as Array<*>
            (arr[0] as Number).toLong() to (arr[1] as Receipt)
        }.groupBy({ it.first }, { it.second })
    }

    /** See the interface KDoc: one query for every id in [receiptIds], never per row. */
    override fun findFileNames(receiptIds: Collection<Long>): Map<Long, String> {
        if (receiptIds.isEmpty()) return emptyMap()
        return em.createQuery("SELECT r.id, r.fileName FROM Receipt r WHERE r.id IN :ids")
            .setParameter("ids", receiptIds).resultList.associate { row ->
                val arr = row as Array<*>
                (arr[0] as Number).toLong() to (arr[1] as String)
            }
    }

    override fun save(receipt: Receipt): Receipt = saveEntity(receipt) { receipt.id }

    /** Same-transaction flush (IMP-6 pattern, [PanacheInvoiceRepository.saveAndFlush]) — NOT
     *  `saveEntityFlushing`/`REQUIRES_NEW`: a suspended outer transaction holding locks on the
     *  receipt table (e.g. a transactional caller that deleted rows) would deadlock against the
     *  inner one, and inner commits would leak past a test's rollback. The loser of a checksum
     *  race rolls back this transaction entirely; recovery happens in [de.littletrotter.application.invoice.ReceiptUploadService.upload]
     *  outside of it. */
    @jakarta.transaction.Transactional
    override fun saveAndFlush(receipt: Receipt): Receipt {
        val saved = save(receipt)
        em.flush()
        return saved
    }

    override fun lockChecksum(sha256Checksum: String) {
        em.createNativeQuery("SELECT pg_advisory_xact_lock(hashtextextended(:sha, 0))")
            .setParameter("sha", sha256Checksum)
            .singleResult
    }

    /** n:m usage lookup. Projection returns one row per (item, invoice) pair, so a receipt
     *  linked to several positions surfaces once per position — the caller (pool) uses this to
     *  name where the receipt is used and to refuse deleting it from a SENT/CANCELLED
     *  invoice. It additionally returns one row per invoice-level link
     *  ([ReceiptUsage.positionId] = null) so a receipt attached only to an invoice is still
     *  protected. */
    override fun findReceiptUsage(receiptId: Long): List<ReceiptUsage> {
        val usage = mutableListOf<ReceiptUsage>()
        em.createQuery(
            "SELECT i.invoice.id, i.invoice.invoiceNumber, i.positionId, i.id, i.invoice.status " +
                "FROM InvoiceItem i JOIN i.receipts r WHERE r.id = :receiptId",
        ).setParameter("receiptId", receiptId).resultList.forEach { row ->
            val arr = row as Array<*>
            val invoiceId = (arr[0] as Number).toLong()
            val invoiceNumber = invoiceNumberOf(arr[1])
            val positionId = arr[2] as String
            val itemId = (arr[SINGLE_ITEM_LINK_ITEM_ID_COLUMN] as Number).toLong()
            val status = arr[SINGLE_ITEM_LINK_STATUS_COLUMN] as? InvoiceStatus
            usage += ReceiptUsage(
                invoiceItemId = itemId,
                invoiceId = invoiceId,
                invoiceNumber = invoiceNumber,
                positionId = positionId,
                invoiceReadOnly = status?.isReadOnly == true,
                invoiceIssued = status?.isIssued == true,
            )
        }
        em.createQuery(
            "SELECT i.id, i.invoiceNumber, i.status FROM Invoice i JOIN i.receipts r WHERE r.id = :receiptId",
        ).setParameter("receiptId", receiptId).resultList.forEach { row ->
            val arr = row as Array<*>
            val status = arr[2] as? InvoiceStatus
            usage += ReceiptUsage(
                invoiceItemId = null,
                invoiceId = (arr[0] as Number).toLong(),
                invoiceNumber = invoiceNumberOf(arr[1]),
                positionId = null,
                invoiceReadOnly = status?.isReadOnly == true,
                invoiceIssued = status?.isIssued == true,
            )
        }
        return usage
    }

    // Flushed so a failing row delete surfaces here, before the caller removes the stored object.
    override fun delete(id: Long) {
        deleteById(id, Receipt::class.java)
        em.flush()
    }
}
