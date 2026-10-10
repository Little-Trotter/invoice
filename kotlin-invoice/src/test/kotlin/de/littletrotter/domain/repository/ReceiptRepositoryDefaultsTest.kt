package de.littletrotter.domain.repository

import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.shared.GridSort
import de.littletrotter.domain.shared.SortDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * [ReceiptRepository.findAllSorted]/[ReceiptRepository.saveAndFlush]/[ReceiptRepository
 * .findFileNames] carry default bodies (compatibility fallbacks for in-memory test doubles, per
 * their own KDoc), same idiom as [ArticleRepositoryDefaultsTest]. [ReceiptRepository.findAll]/
 * [ReceiptRepository.findAssignable]/[ReceiptRepository.countAssignable] additionally declare
 * default parameter values without a body, covered the same way as
 * [B2bProfileRepositoryDefaultsTest].
 */
class ReceiptRepositoryDefaultsTest {

    private fun receipt(id: Long, fileName: String = "beleg-$id.pdf") = Receipt().apply {
        this.id = id
        this.fileName = fileName
    }

    /** Minimal [ReceiptRepository] implementing only the non-default members — every default
     *  body under test here runs as the interface's own implementation. */
    private fun repoWith(vararg receipts: Receipt): ReceiptRepository = object : ReceiptRepository {
        private val data = receipts.toList()
        override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?) = data
        override fun count(filterText: String?) = data.size.toLong()
        override fun findById(id: Long) = data.find { it.id == id }
        override fun findByChecksum(sha256Checksum: String): Receipt? = null
        override fun save(receipt: Receipt): Receipt = receipt
        override fun delete(id: Long) {}
        override fun countForItem(invoiceItemId: Long) = 0L
        override fun findForItem(invoiceItemId: Long, pageIndex: Int, pageSize: Int) = data
        override fun findReceiptUsage(receiptId: Long) = listOf(ReceiptUsage(1L, "RE-2026-00001"))
        override fun findAssignable(pageIndex: Int, pageSize: Int, filterText: String?, excludeItemId: Long?, excludeInvoiceId: Long?, excludeReceiptIds: Set<Long>) = emptyList<Receipt>()
        override fun countAssignable(filterText: String?, excludeItemId: Long?, excludeInvoiceId: Long?, excludeReceiptIds: Set<Long>) = excludeReceiptIds.size.toLong()
        override fun findReceiptUsageBatch(receiptIds: List<Long>) = emptyMap<Long, List<ReceiptUsage>>()
        override fun findForInvoice(invoiceId: Long, pageIndex: Int, pageSize: Int) = data
        override fun countForInvoice(invoiceId: Long) = 0L
        override fun findItemReceiptsForInvoice(invoiceId: Long) = emptyMap<Long, List<Receipt>>()
    }

    @Test
    fun `happy path findAllSorted delegates to findAll, ignoring the sort arguments`() {
        val repo = repoWith(receipt(1))

        assertEquals(listOf(1L), repo.findAllSorted(0, 60, null, GridSort("fileName", SortDirection.DESC)).map { it.id })
    }

    @Test
    fun `boundary saveAndFlush delegates to save`() {
        val repo = repoWith()
        val toSave = receipt(9)

        assertEquals(toSave, repo.saveAndFlush(toSave))
    }

    @Test
    fun `boundary findFileNames folds per-id lookups, skipping ids with no match`() {
        val repo = repoWith(receipt(1, "a.pdf"), receipt(2, "b.pdf"))

        assertEquals(mapOf(1L to "a.pdf", 2L to "b.pdf"), repo.findFileNames(listOf(1L, 2L, 99L)))
    }

    @Test
    fun `boundary findFileNames is empty when no id matches`() {
        val repo = repoWith(receipt(1))

        assertEquals(emptyMap<Long, String>(), repo.findFileNames(listOf(99L)))
    }

    @Test
    fun `boundary findAssignable with every argument omitted uses the documented defaults`() {
        var captured: List<Any?>? = null
        val repo = object : ReceiptRepository {
            override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?) = emptyList<Receipt>()
            override fun count(filterText: String?) = 0L
            override fun findById(id: Long): Receipt? = null
            override fun findByChecksum(sha256Checksum: String): Receipt? = null
            override fun save(receipt: Receipt) = receipt
            override fun delete(id: Long) {}
            override fun countForItem(invoiceItemId: Long) = 0L
            override fun findForItem(invoiceItemId: Long, pageIndex: Int, pageSize: Int) = emptyList<Receipt>()
            override fun findReceiptUsage(receiptId: Long) = emptyList<ReceiptUsage>()
            override fun findAssignable(pageIndex: Int, pageSize: Int, filterText: String?, excludeItemId: Long?, excludeInvoiceId: Long?, excludeReceiptIds: Set<Long>): List<Receipt> {
                captured = listOf(pageIndex, pageSize, filterText, excludeItemId, excludeInvoiceId, excludeReceiptIds)
                return emptyList()
            }
            override fun countAssignable(filterText: String?, excludeItemId: Long?, excludeInvoiceId: Long?, excludeReceiptIds: Set<Long>) = 0L
            override fun findReceiptUsageBatch(receiptIds: List<Long>) = emptyMap<Long, List<ReceiptUsage>>()
            override fun findForInvoice(invoiceId: Long, pageIndex: Int, pageSize: Int) = emptyList<Receipt>()
            override fun countForInvoice(invoiceId: Long) = 0L
            override fun findItemReceiptsForInvoice(invoiceId: Long) = emptyMap<Long, List<Receipt>>()
        }

        repo.findAssignable()

        assertEquals(listOf(0, 60, null, null, null, emptySet<Long>()), captured)
    }

    @Test
    fun `boundary findAll, count, findForItem and findForInvoice with paging omitted use the documented defaults`() {
        val repo = repoWith(receipt(1))

        assertEquals(listOf(1L), repo.findAll().map { it.id })
        assertEquals(1L, repo.count())
        assertEquals(listOf(1L), repo.findForItem(1).map { it.id })
        assertEquals(listOf(1L), repo.findForInvoice(1).map { it.id })
    }

    @Test
    fun `boundary countAssignable with excludeReceiptIds omitted defaults to emptySet`() {
        val repo = repoWith()

        assertEquals(0L, repo.countAssignable())
    }

    @Test
    fun `boundary findReceiptUsage constructs a ReceiptUsage with its documented defaults`() {
        val repo = repoWith()

        val usage = repo.findReceiptUsage(1L).single()

        assertEquals(1L, usage.invoiceId)
        assertEquals("RE-2026-00001", usage.invoiceNumber)
        assertEquals(null, usage.positionId)
        assertEquals(null, usage.invoiceItemId)
        assertEquals(false, usage.invoiceReadOnly)
        assertEquals(false, usage.invoiceIssued)
    }

    @Test
    fun `boundary countAssignable with explicit excludeReceiptIds is forwarded, not overwritten with emptySet`() {
        var captured: Set<Long>? = null
        val repo = object : ReceiptRepository {
            override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?) = emptyList<Receipt>()
            override fun count(filterText: String?) = 0L
            override fun findById(id: Long): Receipt? = null
            override fun findByChecksum(sha256Checksum: String): Receipt? = null
            override fun save(receipt: Receipt) = receipt
            override fun delete(id: Long) {}
            override fun countForItem(invoiceItemId: Long) = 0L
            override fun findForItem(invoiceItemId: Long, pageIndex: Int, pageSize: Int) = emptyList<Receipt>()
            override fun findReceiptUsage(receiptId: Long) = emptyList<ReceiptUsage>()
            override fun findAssignable(pageIndex: Int, pageSize: Int, filterText: String?, excludeItemId: Long?, excludeInvoiceId: Long?, excludeReceiptIds: Set<Long>) = emptyList<Receipt>()
            override fun countAssignable(filterText: String?, excludeItemId: Long?, excludeInvoiceId: Long?, excludeReceiptIds: Set<Long>): Long {
                captured = excludeReceiptIds
                return 0
            }
            override fun findReceiptUsageBatch(receiptIds: List<Long>) = emptyMap<Long, List<ReceiptUsage>>()
            override fun findForInvoice(invoiceId: Long, pageIndex: Int, pageSize: Int) = emptyList<Receipt>()
            override fun countForInvoice(invoiceId: Long) = 0L
            override fun findItemReceiptsForInvoice(invoiceId: Long) = emptyMap<Long, List<Receipt>>()
        }

        repo.countAssignable(excludeReceiptIds = setOf(5L, 6L))

        assertEquals(setOf(5L, 6L), captured)
    }
}
