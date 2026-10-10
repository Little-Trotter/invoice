package de.littletrotter.application

import de.littletrotter.domain.invoice.forceStatusForTest

import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.repository.InvoiceRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Unit tests for [InMemoryOverviewScan] — the free-text overview scan that used to live as a
 * default method on [InvoiceRepository] itself (arc-improvements AR7). [StubInvoiceRepository]
 * delegates its `findOverviewPage`/`countForOverview` to it explicitly rather than inheriting a
 * port default, so these tests now validate the extracted algorithm directly.
 */
class InvoiceRepositoryCountFallbackTest {

    // Minimal stub that stores invoices in memory and implements only the methods used below.
    private class StubInvoiceRepository(private val data: List<Invoice>) : InvoiceRepository {
        override fun findById(id: Long): Invoice? = data.find { it.id == id }
        override fun findInvoiceByItemId(itemId: Long): Invoice? = null
        override fun findByIdWithItems(id: Long): Invoice? = findById(id)
        override fun findByInvoiceNumber(number: String): Invoice? = data.find { it.invoiceNumber.value == number }
        override fun findBySourceQuoteId(quoteId: java.util.UUID): Invoice? = null
        override fun findAll(pageIndex: Int, pageSize: Int): List<Invoice> =
            data.drop(pageIndex * pageSize).take(pageSize)
        override fun findOverviewPage(pageIndex: Int, pageSize: Int, filterText: String?, status: InvoiceStatus?): List<Invoice> =
            InMemoryOverviewScan.findOverviewPage(data, pageIndex, pageSize, filterText, status)
        override fun countForOverview(filterText: String?, status: InvoiceStatus?): Long =
            InMemoryOverviewScan.countForOverview(data, filterText, status)
        override fun count(): Long = data.size.toLong()
        override fun countByStatus(status: InvoiceStatus): Long = data.count { it.status == status }.toLong()
        override fun findByRecipientId(recipientId: Long, pageIndex: Int, pageSize: Int): List<Invoice> = emptyList()
        override fun findBySenderProfileId(senderProfileId: Long, pageIndex: Int, pageSize: Int): List<Invoice> = emptyList()
        override fun existsByBillingAddressId(addressId: Long): Boolean = data.any { it.billingAddress?.id == addressId }
        override fun existsByDeliveryAddressId(addressId: Long): Boolean = data.any { it.deliveryAddress?.id == addressId }
        override fun findAllForRecalculation(): List<Invoice> = data
        override fun save(invoice: Invoice): Invoice = invoice
        override fun forceVersionIncrement(invoice: Invoice) { invoice.version = (invoice.version ?: 0L) + 1L }
    }

    private fun invoice(number: String, recipientName: String? = null, sellerName: String? = null,
                        senderName: String? = null, status: InvoiceStatus = InvoiceStatus.DRAFT): Invoice =
        Invoice().apply {
            invoiceNumber = InvoiceNumber(number)
            this.forceStatusForTest(status)
            recipient = recipientName?.let { Recipient().apply { name = it } }
            selectedSeller = sellerName?.let { SenderProfile().apply { name = it } }
            senderProfile = senderName?.let { SenderProfile().apply { name = it } }
        }

    // ── countForOverview ──────────────────────────────────────────────────────

    // Valid format: PREFIX-YYYY-MM-NNNNN
    private val num1 = "DS-2025-01-00001"
    private val num2 = "DS-2025-01-00002"
    private val num3 = "RC-2025-01-00001"

    @Test
    fun `countForOverview with no filter and no status delegates to count()`() {
        val repo = StubInvoiceRepository(listOf(invoice(num1), invoice(num2)))
        assertEquals(2L, repo.countForOverview(null, null))
    }

    @Test
    fun `countForOverview with blank filter and no status delegates to count()`() {
        val repo = StubInvoiceRepository(listOf(invoice(num1), invoice(num2)))
        assertEquals(2L, repo.countForOverview("   ", null))
    }

    @Test
    fun `countForOverview with no filter and status delegates to countByStatus()`() {
        val repo = StubInvoiceRepository(listOf(
            invoice(num1, status = InvoiceStatus.DRAFT),
            invoice(num2, status = InvoiceStatus.SENT),
        ))
        assertEquals(1L, repo.countForOverview(null, InvoiceStatus.DRAFT))
        assertEquals(1L, repo.countForOverview(null, InvoiceStatus.SENT))
    }

    @Test
    fun `countForOverview with filter matches invoiceNumber`() {
        val repo = StubInvoiceRepository(listOf(
            invoice(num1),
            invoice(num2),
            invoice(num3),
        ))
        assertEquals(2L, repo.countForOverview("DS-2025", null))
        assertEquals(1L, repo.countForOverview("RC-2025", null))
    }

    @Test
    fun `countForOverview with filter matches recipient name (case-insensitive)`() {
        val repo = StubInvoiceRepository(listOf(
            invoice(num1, recipientName = "Acme Corp"),
            invoice(num2, recipientName = "Beta GmbH"),
        ))
        assertEquals(1L, repo.countForOverview("acme", null))
        assertEquals(1L, repo.countForOverview("BETA", null))
        assertEquals(0L, repo.countForOverview("gamma", null))
    }

    @Test
    fun `countForOverview with filter matches selectedSeller name`() {
        val repo = StubInvoiceRepository(listOf(
            invoice(num1, sellerName = "Seller One"),
            invoice(num2, senderName = "Sender Two"),
        ))
        assertEquals(1L, repo.countForOverview("seller", null))
        assertEquals(1L, repo.countForOverview("sender two", null))
    }

    @Test
    fun `countForOverview with filter and status scans only matching status`() {
        val repo = StubInvoiceRepository(listOf(
            invoice(num1, recipientName = "Acme", status = InvoiceStatus.DRAFT),
            invoice(num2, recipientName = "Acme", status = InvoiceStatus.SENT),
        ))
        assertEquals(1L, repo.countForOverview("acme", InvoiceStatus.DRAFT))
        assertEquals(1L, repo.countForOverview("acme", InvoiceStatus.SENT))
        assertEquals(0L, repo.countForOverview("acme", InvoiceStatus.CANCELLED))
    }

    @Test
    fun `countForOverview returns zero when no data matches filter`() {
        val repo = StubInvoiceRepository(listOf(invoice(num1)))
        assertEquals(0L, repo.countForOverview("nomatch", null))
    }

    @Test
    fun `countForOverview returns zero when repository is empty`() {
        val repo = StubInvoiceRepository(emptyList())
        assertEquals(0L, repo.countForOverview("anything", null))
    }

    // ── findOverviewPage default fallback ─────────────────────────────────────

    @Test
    fun `findOverviewPage with no filter returns plain paged results`() {
        val invoices = (1..5).map { i -> invoice("DS-2025-01-%05d".format(i)) }
        val repo = StubInvoiceRepository(invoices)
        val page = repo.findOverviewPage(0, 3, null, null)
        assertEquals(3, page.size)
        assertEquals("DS-2025-01-00001", page[0].invoiceNumber.value)
    }

    @Test
    fun `findOverviewPage with filter and paging returns correct slice`() {
        // 10 invoices matching "DS", 5 matching "RC"
        val invoices = (1..10).map { i -> invoice("DS-2025-01-%05d".format(i)) } +
                       (1..5).map  { i -> invoice("RC-2025-01-%05d".format(i)) }
        val repo = StubInvoiceRepository(invoices)
        val page0 = repo.findOverviewPage(0, 3, "DS-2025", null)
        assertEquals(3, page0.size)
        val page1 = repo.findOverviewPage(1, 3, "DS-2025", null)
        assertEquals(3, page1.size)
        val page3 = repo.findOverviewPage(3, 3, "DS-2025", null)
        assertEquals(1, page3.size) // 10 matches, page 3 (0-indexed) = rows 9..11, only 1 left
    }

    @Test
    fun `findOverviewPage with out-of-bounds pageIndex returns empty`() {
        val repo = StubInvoiceRepository(listOf(invoice(num1)))
        val page = repo.findOverviewPage(99, 10, "DS-2025", null)
        assertEquals(0, page.size)
    }
}
