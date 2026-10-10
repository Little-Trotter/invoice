package de.littletrotter.application

import de.littletrotter.application.error.ApplicationValidationException
import de.littletrotter.application.dto.ReceiptDetachSelection
import de.littletrotter.application.invoice.ReceiptLinkService
import de.littletrotter.application.invoice.ReceiptPoolService
import de.littletrotter.application.invoice.ReceiptBudgetService
import de.littletrotter.domain.exception.InvoiceImmutableException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.shared.DocumentStore
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * [ReceiptLinkService.detachReceipts], the single atomic bulk-removal command a bulk
 * selection in the receipts tab drives: it can span several positions and the invoice level in
 * one call, must never be blocked by the IR-2 budget guard (that guard is for growing the
 * document, not shrinking it), and must treat an id that is not attached as a silent no-op rather
 * than an error. [removeReceiptsFromItem]/[removeReceiptsFromInvoice] are re-expressed through it
 * (see [ReceiptServiceAssignmentTest]/[ReceiptServiceInvoiceLevelTest], whose existing tests keep
 * covering that public surface); this file exercises the new command directly plus the guarantees
 * that public surface did not have before (idempotence, cross-position atomicity, the budget
 * bypass).
 */
class ReceiptServiceDetachTest {

    private val receiptRepository: ReceiptRepository = mock()
    private val invoiceRepository: InvoiceRepository = mock()
    private val documentStore: DocumentStore = mock()
    private val service = ReceiptLinkService(
        receiptRepository,
        invoiceRepository,
        ReceiptPoolService(receiptRepository, documentStore),
        ReceiptBudgetService(receiptRepository),
    )

    private fun receipt(id: Long): Receipt = Receipt().apply {
        this.id = id
        fileName = "receipt-$id.pdf"
        objectKey = "receipt-pool/receipt-$id.pdf"
        sha256Checksum = "checksum-$id"
        sizeBytes = 1024L
        description = "Beleg $id"
        documentDate = java.time.LocalDate.of(2026, 1, 1)
    }

    /** An invoice with two items, each carrying its own receipt, plus one invoice-level receipt —
     *  the shape a bulk selection across the tab's two tables plus the invoice level would see. */
    private fun invoiceWithTwoItems(
        status: InvoiceStatus = InvoiceStatus.DRAFT,
        version: Long? = null,
    ): Triple<Invoice, InvoiceItem, InvoiceItem> {
        val item1 = InvoiceItem().apply { id = 11L; positionId = "1"; description = "Pos 1" }
        val item2 = InvoiceItem().apply { id = 12L; positionId = "2"; description = "Pos 2" }
        val invoice = Invoice.withInitialStatus(status).apply {
            id = 1L
            invoiceNumber = InvoiceNumber("RE-2026-09-00001")
            if (version != null) this.version = version
            addItem(item1)
            addItem(item2)
        }
        whenever(invoiceRepository.findById(1L)).thenReturn(invoice)
        return Triple(invoice, item1, item2)
    }

    // ── Happy path — atomic across invoice level and several positions ─────────

    @Test
    fun `detachReceipts removes invoice-level and multi-position receipts in one save`() {
        val (invoice, item1, item2) = invoiceWithTwoItems()
        invoice.receipts.addAll(listOf(receipt(1L), receipt(2L)))
        item1.receipts.addAll(listOf(receipt(10L), receipt(11L)))
        item2.receipts.addAll(listOf(receipt(20L)))
        whenever(invoiceRepository.save(any())).thenAnswer { it.arguments[0] }

        service.detachReceipts(
            1L,
            ReceiptDetachSelection(
                invoiceLevelReceiptIds = setOf(1L),
                itemReceiptIds = mapOf(11L to setOf(10L), 12L to setOf(20L)),
            ),
        )

        assertEquals(listOf(2L), invoice.receipts.mapNotNull { it.id })
        assertEquals(listOf(11L), item1.receipts.mapNotNull { it.id })
        assertEquals(emptyList<Long>(), item2.receipts.mapNotNull { it.id })
        verify(invoiceRepository, times(1)).save(invoice)
    }

    // ── B1 — status gate ─────────────────────────────────────────────────────

    @Test
    fun `detachReceipts rejects a finalized invoice`() {
        val (_, item1, _) = invoiceWithTwoItems(status = InvoiceStatus.SENT)
        item1.receipts.add(receipt(10L))

        assertThrows(InvoiceImmutableException::class.java) {
            service.detachReceipts(1L, ReceiptDetachSelection(itemReceiptIds = mapOf(11L to setOf(10L))))
        }
        assertEquals(listOf(10L), item1.receipts.mapNotNull { it.id }, "a refused call must change nothing")
        verify(invoiceRepository, never()).save(any())
    }

    @Test
    fun `detachReceipts rejects a cancelled invoice`() {
        invoiceWithTwoItems(status = InvoiceStatus.CANCELLED)

        assertThrows(InvoiceImmutableException::class.java) {
            service.detachReceipts(1L, ReceiptDetachSelection(invoiceLevelReceiptIds = setOf(1L)))
        }
    }

    @Test
    fun `detachReceipts rejects a storno document regardless of its own status`() {
        val (invoice, _, _) = invoiceWithTwoItems(status = InvoiceStatus.DRAFT)
        invoice.typeCode = de.littletrotter.domain.invoice.InvoiceTypeCode.CORRECTED_INVOICE

        assertThrows(InvoiceImmutableException::class.java) {
            service.detachReceipts(1L, ReceiptDetachSelection(invoiceLevelReceiptIds = setOf(1L)))
        }
    }

    @Test
    fun `detachReceipts allows an invoice in review`() {
        val (_, item1, _) = invoiceWithTwoItems(status = InvoiceStatus.IN_REVIEW)
        item1.receipts.add(receipt(10L))
        whenever(invoiceRepository.save(any())).thenAnswer { it.arguments[0] }

        assertDoesNotThrow {
            service.detachReceipts(1L, ReceiptDetachSelection(itemReceiptIds = mapOf(11L to setOf(10L))))
        }
        assertEquals(emptyList<Long>(), item1.receipts.mapNotNull { it.id })
    }

    // ── B2 — atomic: a foreign item id refuses the whole call, nothing detached ─

    @Test
    fun `detachReceipts is atomic - a foreign item id rolls back the whole selection`() {
        val (invoice, item1, item2) = invoiceWithTwoItems()
        item1.receipts.add(receipt(10L))
        item2.receipts.add(receipt(20L))
        invoice.receipts.add(receipt(1L))
        val foreignItemId = 999L

        assertThrows(ApplicationValidationException::class.java) {
            service.detachReceipts(
                1L,
                ReceiptDetachSelection(
                    invoiceLevelReceiptIds = setOf(1L),
                    itemReceiptIds = mapOf(11L to setOf(10L), foreignItemId to setOf(30L)),
                ),
            )
        }

        // Nothing must have been touched, including the valid parts of the same selection.
        assertEquals(listOf(1L), invoice.receipts.mapNotNull { it.id })
        assertEquals(listOf(10L), item1.receipts.mapNotNull { it.id })
        assertEquals(listOf(20L), item2.receipts.mapNotNull { it.id })
        verify(invoiceRepository, never()).save(any())
    }

    // ── B3 — stale version refuses before any mutation ──────────────────────────

    @Test
    fun `detachReceipts rejects a stale expected version before touching any collection`() {
        val (_, item1, _) = invoiceWithTwoItems(version = 5L)
        item1.receipts.add(receipt(10L))

        assertThrows(jakarta.persistence.OptimisticLockException::class.java) {
            service.detachReceipts(
                1L,
                ReceiptDetachSelection(itemReceiptIds = mapOf(11L to setOf(10L))),
                expectedVersion = 4L,
            )
        }
        assertEquals(listOf(10L), item1.receipts.mapNotNull { it.id })
        verify(invoiceRepository, never()).save(any())
    }

    @Test
    fun `detachReceipts succeeds with a matching expected version`() {
        val (_, item1, _) = invoiceWithTwoItems(version = 5L)
        item1.receipts.add(receipt(10L))
        whenever(invoiceRepository.save(any())).thenAnswer { it.arguments[0] }

        assertDoesNotThrow {
            service.detachReceipts(
                1L,
                ReceiptDetachSelection(itemReceiptIds = mapOf(11L to setOf(10L))),
                expectedVersion = 5L,
            )
        }
        assertEquals(emptyList<Long>(), item1.receipts.mapNotNull { it.id })
    }

    // ── B4 — never refused by the budget guard, even starting over budget ──────

    @Test
    fun `detachReceipts never runs the budget guard - shrinking an already over-budget item succeeds`() {
        val (_, item1, _) = invoiceWithTwoItems()
        // 25 receipts on one position alone already exceeds the 19-receipt invoice-wide budget —
        // requireWithinBudget would refuse this if it ran; detachReceipts must never call it.
        val overBudget = (1L..25L).map { receipt(it) }
        item1.receipts.addAll(overBudget)
        whenever(invoiceRepository.save(any())).thenAnswer { it.arguments[0] }

        assertDoesNotThrow {
            service.detachReceipts(1L, ReceiptDetachSelection(itemReceiptIds = mapOf(11L to setOf(1L))))
        }

        assertEquals(24, item1.receipts.size, "the remaining, still over-budget set must be accepted")
        // detachReceipts must resolve nothing through the receipt pool at all — it only filters
        // the invoice's own already-attached Receipt entities by id.
        verify(receiptRepository, never()).findById(any())
    }

    // ── B8 (idempotence, B3 in the backend section) — an id that is not attached is a no-op ────

    @Test
    fun `detachReceipts on an id that is not attached is a silent no-op, no save`() {
        val (_, item1, _) = invoiceWithTwoItems()
        item1.receipts.add(receipt(10L))

        assertDoesNotThrow {
            service.detachReceipts(1L, ReceiptDetachSelection(itemReceiptIds = mapOf(11L to setOf(999L))))
        }

        assertEquals(listOf(10L), item1.receipts.mapNotNull { it.id }, "the unrelated id must be untouched")
        verify(invoiceRepository, never()).save(any())
    }

    @Test
    fun `detachReceipts with an entirely unattached selection across both levels saves nothing`() {
        val (invoice, item1, item2) = invoiceWithTwoItems()
        item1.receipts.add(receipt(10L))
        invoice.receipts.add(receipt(1L))

        service.detachReceipts(
            1L,
            ReceiptDetachSelection(
                invoiceLevelReceiptIds = setOf(999L),
                itemReceiptIds = mapOf(11L to setOf(998L), 12L to emptySet()),
            ),
        )

        assertEquals(listOf(10L), item1.receipts.mapNotNull { it.id })
        assertEquals(listOf(1L), invoice.receipts.mapNotNull { it.id })
        assertEquals(emptyList<Long>(), item2.receipts.mapNotNull { it.id })
        verify(invoiceRepository, never()).save(any())
    }

    @Test
    fun `detachReceipts a mix of attached and unattached ids in one call detaches only the attached ones`() {
        val (invoice, item1, _) = invoiceWithTwoItems()
        item1.receipts.addAll(listOf(receipt(10L), receipt(11L)))
        whenever(invoiceRepository.save(any())).thenAnswer { it.arguments[0] }

        // 10L is attached, 999L never was — both in the same request.
        service.detachReceipts(1L, ReceiptDetachSelection(itemReceiptIds = mapOf(11L to setOf(10L, 999L))))

        assertEquals(listOf(11L), item1.receipts.mapNotNull { it.id })
        verify(invoiceRepository).save(invoice)
    }

    // ── invoice / item existence ─────────────────────────────────────────────

    @Test
    fun `detachReceipts throws when the invoice does not exist`() {
        whenever(invoiceRepository.findById(404L)).thenReturn(null)

        assertThrows(ApplicationValidationException::class.java) {
            service.detachReceipts(404L, ReceiptDetachSelection(invoiceLevelReceiptIds = setOf(1L)))
        }
    }

    // ── removeReceiptsFromItem/removeReceiptsFromInvoice — re-expressed, single path ───────────

    @Test
    fun `removeReceiptsFromItem going through detachReceipts is idempotent - no save when nothing was attached`() {
        val (invoice, item1, _) = invoiceWithTwoItems()
        item1.receipts.add(receipt(10L))
        whenever(invoiceRepository.findInvoiceByItemId(11L)).thenReturn(invoice)

        service.removeReceiptsFromItem(11L, listOf(999L))

        assertEquals(listOf(10L), item1.receipts.mapNotNull { it.id })
        verify(invoiceRepository, never()).save(any())
    }

    @Test
    fun `removeReceiptsFromInvoice going through detachReceipts is idempotent - no save when nothing was attached`() {
        val (invoice, _, _) = invoiceWithTwoItems()
        invoice.receipts.add(receipt(1L))

        service.removeReceiptsFromInvoice(1L, listOf(999L))

        assertEquals(listOf(1L), invoice.receipts.mapNotNull { it.id })
        verify(invoiceRepository, never()).save(any())
    }

    @Test
    fun `removeReceiptsFromItem never runs the budget guard on an already over-budget position`() {
        val (invoice, item1, _) = invoiceWithTwoItems()
        val overBudget = (1L..25L).map { receipt(it) }
        item1.receipts.addAll(overBudget)
        whenever(invoiceRepository.findInvoiceByItemId(11L)).thenReturn(invoice)
        whenever(invoiceRepository.save(any())).thenAnswer { it.arguments[0] }

        assertDoesNotThrow { service.removeReceiptsFromItem(11L, listOf(1L)) }

        assertEquals(24, item1.receipts.size)
    }

    @Test
    fun `boundary - a later position's no-op detach does not clear the already-true changed flag from an earlier position`() {
        val (invoice, item1, item2) = invoiceWithTwoItems()
        item1.receipts.add(receipt(10L))
        item2.receipts.add(receipt(20L))
        whenever(invoiceRepository.save(any())).thenAnswer { it.arguments[0] }

        service.detachReceipts(
            1L,
            ReceiptDetachSelection(itemReceiptIds = mapOf(11L to setOf(10L), 12L to setOf(999L))),
        )

        assertEquals(emptyList<Long>(), item1.receipts.mapNotNull { it.id })
        assertEquals(listOf(20L), item2.receipts.mapNotNull { it.id }, "the unmatched id on the second position must be untouched")
        verify(invoiceRepository, times(1)).save(invoice)
    }

    @Test
    fun `boundary - removeReceiptsFromItem throws when the invoice item does not exist`() {
        whenever(invoiceRepository.findInvoiceByItemId(999L)).thenReturn(null)

        val ex = assertThrows(ApplicationValidationException::class.java) {
            service.removeReceiptsFromItem(999L, listOf(1L))
        }
        assertEquals("Invoice item not found: 999", ex.message)
    }
}
