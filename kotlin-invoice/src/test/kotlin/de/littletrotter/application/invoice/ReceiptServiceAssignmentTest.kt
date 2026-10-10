package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.forceStatusForTest

import de.littletrotter.domain.exception.InvoiceImmutableException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.domain.repository.ReceiptUsage
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.receipt.ReceiptSizePolicy
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * PO 30.08.2026 — unit tests for the receipt lifecycle the pool UI drives: assigning receipts to
 * a position (zuordnen), removing them from it (entfernen), and deleting a receipt from the pool
 * (löschen). Ordered after a live incident where an invoice referenced a receipt object key that
 * no longer existed in the store (S3 404 aborted validation before it started): these tests pin
 * that the *application-side* lifecycle can never produce such a dangling link — deletion always
 * unlinks first and is guarded against finalized proofs, and a failing object-store delete never
 * leaves a half-deleted row behind. (The incident's actual root cause was a datagen key drift,
 * fixed separately in `datagen/generate.py`, DG-3b.)
 */
class ReceiptServiceAssignmentTest {

    private val receiptRepository: ReceiptRepository = mock()
    private val invoiceRepository: InvoiceRepository = mock()
    private val documentStore: DocumentStore = mock()
    private val poolService = ReceiptPoolService(receiptRepository, documentStore)
    private val service = ReceiptLinkService(
        receiptRepository,
        invoiceRepository,
        poolService,
        ReceiptBudgetService(receiptRepository),
    )

    // receipt-mandatory-fields: description/documentDate are mandatory on every Receipt now, so
    // this shared fixture fills both — the tests in this file exercise the lifecycle/assignment
    // guards, not the field-mandatory rule itself (that lives in ReceiptServiceTest).
    private fun receipt(id: Long): Receipt = Receipt().apply {
        this.id = id
        fileName = "receipt-$id.pdf"
        objectKey = "receipt-pool/receipt-$id.pdf"
        sha256Checksum = "checksum-$id"
        sizeBytes = 1024L
        description = "Beleg $id"
        documentDate = java.time.LocalDate.of(2026, 1, 1)
    }

    private fun invoiceWithItem(itemId: Long, status: InvoiceStatus = InvoiceStatus.DRAFT): Pair<Invoice, InvoiceItem> {
        val item = InvoiceItem().apply { this.id = itemId; positionId = "1"; description = "Pos" }
        val invoice = Invoice().apply {
            this.id = 100L
            this.forceStatusForTest(status)
            addItem(item)
        }
        whenever(invoiceRepository.findInvoiceByItemId(itemId)).thenReturn(invoice)
        // removeReceiptsFromItem now resolves the invoice by item id and then re-fetches it
        // by id through detachReceipts (the single removal path), so both lookups must agree.
        whenever(invoiceRepository.findById(invoice.id!!)).thenReturn(invoice)
        return invoice to item
    }

    // ── Zuordnen ─────────────────────────────────────────────────────────────

    @Test
    fun `addReceiptsToItem attaches new receipts and deduplicates against already-attached ones`() {
        val (_, item) = invoiceWithItem(1L)
        val r1 = receipt(1L)
        val r2 = receipt(2L)
        whenever(receiptRepository.findForItem(any(), any(), any())).thenReturn(listOf(r1))
        whenever(receiptRepository.findById(1L)).thenReturn(r1)
        whenever(receiptRepository.findById(2L)).thenReturn(r2)

        service.addReceiptsToItem(1L, listOf(1L, 2L))

        assertEquals(setOf(1L, 2L), item.receipts.mapNotNull { it.id }.toSet())
        assertEquals(2, item.receipts.size, "the already-attached receipt must not be linked twice")
    }

    @Test
    fun `setReceiptsForItem rejects a finalized invoice`() {
        invoiceWithItem(2L, status = InvoiceStatus.SENT)

        assertThrows(InvoiceImmutableException::class.java) {
            service.setReceiptsForItem(2L, listOf(1L))
        }
    }

    // CANCELLED was dead code before ST-3 (isReadOnly() covered it, but no invoice
    // ever produced that status, so this branch had never actually run).
    @Test
    fun `setReceiptsForItem rejects a cancelled invoice`() {
        invoiceWithItem(12L, status = InvoiceStatus.CANCELLED)

        assertThrows(InvoiceImmutableException::class.java) {
            service.setReceiptsForItem(12L, listOf(1L))
        }
    }

    // A storno document (CORRECTED_INVOICE, PO decision 1 2026-09-02; previously
    // CREDIT_NOTE) is read-only by type code alone, independent of its own status; DRAFT is used
    // here specifically because it would otherwise be writable.
    @Test
    fun `setReceiptsForItem rejects a storno document regardless of its own status`() {
        val (invoice, _) = invoiceWithItem(13L, status = InvoiceStatus.DRAFT)
        invoice.typeCode = de.littletrotter.domain.invoice.InvoiceTypeCode.CORRECTED_INVOICE

        assertThrows(InvoiceImmutableException::class.java) {
            service.setReceiptsForItem(13L, listOf(1L))
        }
    }

    @Test
    fun `setReceiptsForItem refuses more receipts than the invoice-wide budget, never truncates`() {
        invoiceWithItem(3L)
        val tooMany = (1L..(ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE + 1L)).toList()
        tooMany.forEach { id -> whenever(receiptRepository.findById(id)).thenReturn(receipt(id)) }

        val ex = assertThrows(ReceiptCountLimitExceededException::class.java) {
            service.setReceiptsForItem(3L, tooMany)
        }

        assertEquals(ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE, ex.limit)
        assertEquals(ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE + 1, ex.attempted)
    }

    @Test
    fun `boundary - setReceiptsForItem refuses a receipt with no known size, naming it by id`() {
        invoiceWithItem(5L)
        val sizeless = receipt(1L).apply { sizeBytes = null }
        whenever(receiptRepository.findById(1L)).thenReturn(sizeless)

        val ex = assertThrows(de.littletrotter.application.error.ApplicationValidationException::class.java) {
            service.setReceiptsForItem(5L, listOf(1L))
        }

        assertTrue(ex.message!!.contains("Receipt 1"), "message names the receipt by id: ${ex.message}")
    }

    @Test
    fun `boundary - setReceiptsForItem refuses a receipt with no known size and no id, falling back to a question mark`() {
        invoiceWithItem(6L)
        val sizeless = receipt(1L).apply { id = null; sizeBytes = null }
        whenever(receiptRepository.findById(1L)).thenReturn(sizeless)

        val ex = assertThrows(de.littletrotter.application.error.ApplicationValidationException::class.java) {
            service.setReceiptsForItem(6L, listOf(1L))
        }

        assertTrue(ex.message!!.contains("Receipt ?"), "no id -> falls back to a literal question mark: ${ex.message}")
    }

    // ── Entfernen ────────────────────────────────────────────────────────────

    @Test
    fun `removeReceiptsFromItem keeps the remaining receipts attached`() {
        val (_, item) = invoiceWithItem(4L)
        val r1 = receipt(1L)
        val r2 = receipt(2L)
        item.receipts.addAll(listOf(r1, r2))
        whenever(receiptRepository.findForItem(any(), any(), any())).thenReturn(listOf(r1, r2))
        whenever(receiptRepository.findById(2L)).thenReturn(r2)

        service.removeReceiptsFromItem(4L, listOf(1L))

        assertEquals(listOf(2L), item.receipts.mapNotNull { it.id })
    }

    @Test
    fun `removeReceiptsFromItem on a finalized invoice is rejected`() {
        val (_, item) = invoiceWithItem(5L, status = InvoiceStatus.SENT)
        item.receipts.add(receipt(1L))
        whenever(receiptRepository.findForItem(any(), any(), any())).thenReturn(listOf(receipt(1L)))

        assertThrows(InvoiceImmutableException::class.java) {
            service.removeReceiptsFromItem(5L, listOf(1L))
        }
    }

    @Test
    fun `removeReceiptsFromItem on an invoice in review is allowed`() {
        val (_, item) = invoiceWithItem(6L, status = InvoiceStatus.IN_REVIEW)
        val receipt = receipt(1L)
        item.receipts.add(receipt)
        whenever(receiptRepository.findForItem(any(), any(), any())).thenReturn(listOf(receipt))
        whenever(invoiceRepository.save(any())).thenAnswer { it.arguments[0] }

        service.removeReceiptsFromItem(6L, listOf(1L))

        assertEquals(emptyList<Long>(), item.receipts.mapNotNull { it.id })
        verify(invoiceRepository).save(any())
    }

    // ── Löschen ──────────────────────────────────────────────────────────────

    @Test
    fun `delete refuses outright when a finalized invoice references the receipt`() {
        whenever(receiptRepository.findReceiptUsage(7L)).thenReturn(
            listOf(ReceiptUsage(invoiceId = 1L, invoiceNumber = "R-1", invoiceReadOnly = true)),
        )

        assertThrows(ReceiptAssignedToInvoiceException::class.java) {
            poolService.delete(7L)
        }
        verify(receiptRepository, never()).delete(any())
        verify(documentStore, never()).delete(any())
    }

    @Test
    fun `delete refuses outright when an open invoice references the receipt`() {
        // receipt-delete-guard — a draft link blocks the delete exactly like a finalized one; the
        // pool has no confirmation path any more, the assignment is released on the invoice.
        whenever(receiptRepository.findReceiptUsage(8L)).thenReturn(
            listOf(ReceiptUsage(invoiceId = 2L, invoiceNumber = "R-2", positionId = "1", invoiceItemId = 5L)),
        )

        val ex = assertThrows(ReceiptAssignedToInvoiceException::class.java) {
            poolService.delete(8L)
        }
        assertEquals(listOf("R-2"), ex.invoiceNumbers)
        verify(receiptRepository, never()).delete(any())
        verify(documentStore, never()).delete(any())
    }

    @Test
    fun `delete of an unreferenced receipt removes the row and the object`() {
        val r = receipt(10L)
        whenever(receiptRepository.findReceiptUsage(10L)).thenReturn(emptyList())
        whenever(receiptRepository.findById(10L)).thenReturn(r)

        assertDoesNotThrow { poolService.delete(10L) }
        verify(receiptRepository).delete(10L)
        verify(documentStore).delete(r.objectKey)
    }

    @Test
    fun `a failing object-store delete never blocks the pool deletion`() {
        val r = receipt(11L)
        whenever(receiptRepository.findReceiptUsage(11L)).thenReturn(emptyList())
        whenever(receiptRepository.findById(11L)).thenReturn(r)
        // A realistic backend failure (runDocumentStoreOp's own catch set), not a bare
        // RuntimeException.
        doThrow(java.io.IOException("store down")).whenever(documentStore).delete(any())

        assertDoesNotThrow { poolService.delete(11L) }
        verify(receiptRepository).delete(11L)
    }
}
