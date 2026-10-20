package de.littletrotter.application

import de.littletrotter.application.invoice.receiptUploadService
import de.littletrotter.domain.invoice.replaceReceipts
import de.littletrotter.domain.invoice.linkReceipt
import de.littletrotter.application.invoice.port.outbound.InvoiceLookupRepository
import de.littletrotter.application.cache.port.outbound.InvoiceCachePort
import de.littletrotter.application.error.ApplicationValidationException
import de.littletrotter.application.invoice.ReceiptBudgetService
import de.littletrotter.application.invoice.ReceiptCountLimitExceededException
import de.littletrotter.application.invoice.ReceiptLinkService
import de.littletrotter.application.invoice.ReceiptPoolService
import de.littletrotter.domain.exception.InvoiceImmutableException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.asMutableForTest
import de.littletrotter.domain.invoice.forceStatusForTest
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.domain.repository.ReceiptUsage
import de.littletrotter.fixture.aReceipt
import de.littletrotter.fixture.edit
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.receipt.ReceiptSizePolicy
import de.littletrotter.test.FIXED_TIME_SOURCE
import jakarta.persistence.PersistenceException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate

/**
 * belege-improve A4 — [ReceiptLinkService]'s invoice-level (whole-invoice, not per-position) receipt
 * assignment methods: `setReceiptsForInvoice`/`addReceiptsToInvoice`/`removeReceiptsFromInvoice`.
 * These mirror the item-level methods [ReceiptServiceTest] already covers thoroughly, but exercise
 * their own code path (`invoiceRepository.findById`, not `findInvoiceByItemId`) and were entirely
 * untested before this addition — none of the three existing `ReceiptService*Test` files call them.
 * Also covers the invoice-wide receipt budget guard (previously the per-target cap).
 */
class ReceiptServiceInvoiceLevelTest {

    private val receiptRepository = mock<ReceiptRepository>()
    private val invoiceRepository = mock<InvoiceRepository>()
    private val invoiceLookup = mock<InvoiceLookupRepository>()
    private val documentStore = mock<DocumentStore>()
    private val poolService = ReceiptPoolService(receiptRepository, documentStore)
    private val budgetService = ReceiptBudgetService(receiptRepository)
    private val service = ReceiptLinkService(receiptRepository, invoiceRepository, invoiceLookup, poolService, budgetService)

    // receipt-mandatory-fields: description/documentDate are mandatory on every Receipt now, so
    // this shared fixture fills both; tests that target other guards (unknown size, oversized
    // legacy receipts) build their own Receipt() literals below and set both fields explicitly
    // too, so only the guard actually under test fires.
    private fun receipt(id: Long) = aReceipt {
        this.id = id
        fileName = "r$id.pdf"
        objectKey = "receipt-pool/$id.pdf"
        sizeBytes = 1024L
        description = "Beleg $id"
        documentDate = java.time.LocalDate.of(2026, 1, 1)
    }

    private fun openInvoice(id: Long) = Invoice().edit {
        this.id = id
        invoiceNumber = InvoiceNumber("RE-2026-08-" + id.toString().padStart(5, '0'))
        forceStatusForTest(InvoiceStatus.DRAFT)
    }

    @Test
    fun `setReceiptsForInvoice wirft wenn die Rechnung nicht existiert`() {
        whenever(invoiceRepository.findById(999L)).thenReturn(null)

        assertThrows(ApplicationValidationException::class.java) {
            service.setReceiptsForInvoice(999L, emptyList())
        }
    }

    @Test
    fun `setReceiptsForInvoice verweigert eine festgeschriebene Rechnung`() {
        val invoice = openInvoice(1L).apply { forceStatusForTest(InvoiceStatus.ISSUED) }
        whenever(invoiceRepository.findById(1L)).thenReturn(invoice)

        assertThrows(InvoiceImmutableException::class.java) {
            service.setReceiptsForInvoice(1L, emptyList())
        }
    }

    // CANCELLED was dead code before this addition (isReadOnly() covered it, but no invoice
    // ever produced that status, so this branch had never actually run).
    @Test
    fun `setReceiptsForInvoice verweigert eine stornierte Rechnung`() {
        val invoice = openInvoice(6L).apply { forceStatusForTest(InvoiceStatus.CANCELLED) }
        whenever(invoiceRepository.findById(6L)).thenReturn(invoice)

        assertThrows(InvoiceImmutableException::class.java) {
            service.setReceiptsForInvoice(6L, emptyList())
        }
    }

    // A cancellation document (CORRECTED_INVOICE, PO decision 1 2026-09-02; previously
    // CREDIT_NOTE) is read-only by type code alone, independent of its own status; DRAFT is used
    // here specifically because it would otherwise be writable.
    @Test
    fun `setReceiptsForInvoice verweigert eine Stornorechnung unabhaengig vom eigenen Status`() {
        val invoice = openInvoice(7L).edit { typeCode = de.littletrotter.domain.invoice.InvoiceTypeCode.CORRECTED_INVOICE }
        whenever(invoiceRepository.findById(7L)).thenReturn(invoice)

        assertThrows(InvoiceImmutableException::class.java) {
            service.setReceiptsForInvoice(7L, emptyList())
        }
    }

    @Test
    fun `setReceiptsForInvoice erlaubt IN_REVIEW mit passender Version`() {
        val invoice = openInvoice(8L).edit {
            forceStatusForTest(InvoiceStatus.IN_REVIEW)
            version = 4L
        }
        whenever(invoiceRepository.findById(8L)).thenReturn(invoice)
        whenever(receiptRepository.findItemReceiptsForInvoice(8L)).thenReturn(emptyMap())
        whenever(invoiceRepository.save(any())).thenAnswer { it.arguments[0] }

        assertDoesNotThrow { service.setReceiptsForInvoice(8L, emptyList(), expectedVersion = 4L) }
        verify(invoiceRepository).save(invoice)
    }

    @Test
    fun `setReceiptsForInvoice rejects a stale expected version before resolving receipts`() {
        val invoice = openInvoice(9L).edit { version = 5L }
        whenever(invoiceRepository.findById(9L)).thenReturn(invoice)

        assertThrows(jakarta.persistence.OptimisticLockException::class.java) {
            service.setReceiptsForInvoice(9L, listOf(123L), expectedVersion = 4L)
        }
        verify(receiptRepository, never()).findById(123L)
        verify(invoiceRepository, never()).save(any())
    }

    @Test
    fun `writable guard follows the invoice lifecycle for every status`() {
        InvoiceStatus.entries.forEachIndexed { index, status ->
            val invoice = openInvoice(20L + index).apply { this.forceStatusForTest(status) }
            whenever(invoiceRepository.findById(invoice.id!!)).thenReturn(invoice)
            whenever(receiptRepository.findItemReceiptsForInvoice(invoice.id!!)).thenReturn(emptyMap())

            if (status == InvoiceStatus.DRAFT || status == InvoiceStatus.IN_REVIEW) {
                assertDoesNotThrow {
                    service.setReceiptsForInvoice(invoice.id!!, emptyList(), expectedVersion = null)
                }
            } else {
                assertThrows(InvoiceImmutableException::class.java) {
                    service.setReceiptsForInvoice(invoice.id!!, emptyList(), expectedVersion = null)
                }
            }
        }
    }

    @Test
    fun `corrected invoice stays immutable in DRAFT even with matching version`() {
        val invoice = openInvoice(26L).edit {
            typeCode = de.littletrotter.domain.invoice.InvoiceTypeCode.CORRECTED_INVOICE
            version = 8L
        }
        whenever(invoiceRepository.findById(26L)).thenReturn(invoice)

        assertThrows(InvoiceImmutableException::class.java) {
            service.setReceiptsForInvoice(26L, emptyList(), expectedVersion = 8L)
        }
    }

    @Test
    fun `setReceiptsForInvoice ersetzt die vollstaendige Belegliste und speichert die Rechnung`() {
        val invoice = openInvoice(2L)
        whenever(invoiceRepository.findById(2L)).thenReturn(invoice)
        whenever(receiptRepository.findById(10L)).thenReturn(receipt(10L))
        whenever(receiptRepository.findById(11L)).thenReturn(receipt(11L))
        whenever(invoiceRepository.save(any())).thenAnswer { it.arguments[0] }

        service.setReceiptsForInvoice(2L, listOf(10L, 11L))

        assertEquals(listOf(10L, 11L), invoice.receiptList.mapNotNull { it.id })
        verify(invoiceRepository).save(invoice)
    }

    @Test
    fun `addReceiptsToInvoice ergaenzt die bestehende Liste statt sie zu ersetzen`() {
        val invoice = openInvoice(3L).apply { linkReceipt(receipt(10L)) }
        whenever(invoiceRepository.findById(3L)).thenReturn(invoice)
        whenever(receiptRepository.findForInvoice(3L, 0, Int.MAX_VALUE)).thenReturn(listOf(receipt(10L)))
        whenever(receiptRepository.findById(10L)).thenReturn(receipt(10L))
        whenever(receiptRepository.findById(20L)).thenReturn(receipt(20L))
        whenever(invoiceRepository.save(any())).thenAnswer { it.arguments[0] }

        service.addReceiptsToInvoice(3L, listOf(20L))

        assertEquals(setOf(10L, 20L), invoice.receiptList.mapNotNull { it.id }.toSet())
    }

    @Test
    fun `removeReceiptsFromInvoice entfernt nur die angegebenen Belege`() {
        val invoice = openInvoice(4L).apply { replaceReceipts(listOf(receipt(10L), receipt(20L))) }
        whenever(invoiceRepository.findById(4L)).thenReturn(invoice)
        whenever(receiptRepository.findForInvoice(4L, 0, Int.MAX_VALUE)).thenReturn(listOf(receipt(10L), receipt(20L)))
        whenever(receiptRepository.findById(20L)).thenReturn(receipt(20L))
        whenever(invoiceRepository.save(any())).thenAnswer { it.arguments[0] }

        service.removeReceiptsFromInvoice(4L, listOf(10L))

        assertEquals(listOf(20L), invoice.receiptList.mapNotNull { it.id })
    }

    // ── requireWithinBudget — ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE ───

    @Test
    fun `setReceiptsForInvoice verweigert mehr als MAX_RECEIPTS_PER_INVOICE Belege statt sie abzuschneiden`() {
        val invoice = openInvoice(5L)
        whenever(invoiceRepository.findById(5L)).thenReturn(invoice)
        val tooMany = (1L..(ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE + 1L)).toList()
        tooMany.forEach { id -> whenever(receiptRepository.findById(id)).thenReturn(receipt(id)) }

        val ex = assertThrows(ReceiptCountLimitExceededException::class.java) {
            service.setReceiptsForInvoice(5L, tooMany)
        }

        assertEquals(ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE, ex.limit)
        assertEquals(ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE + 1, ex.attempted)
        verify(invoiceRepository, never()).save(any())
    }

    @Test
    fun `setReceiptsForItem verweigert mehr als MAX_RECEIPTS_PER_INVOICE Belege`() {
        val invoiceRepo = mock<InvoiceRepository>()
        val receiptRepo = mock<ReceiptRepository>()
        val svc = ReceiptLinkService(receiptRepo, invoiceRepo, invoiceLookup, ReceiptPoolService(receiptRepo, mock()), ReceiptBudgetService(receiptRepo))
        val item = InvoiceItem().edit { id = 50L }
        val invoice = openInvoice(6L).apply { itemList.asMutableForTest().add(item) }
        whenever(invoiceLookup.findInvoiceByItemId(50L)).thenReturn(invoice)
        val tooMany = (1L..(ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE + 1L)).toList()
        tooMany.forEach { id -> whenever(receiptRepo.findById(id)).thenReturn(receipt(id)) }

        val ex = assertThrows(ReceiptCountLimitExceededException::class.java) {
            svc.setReceiptsForItem(50L, tooMany)
        }

        assertEquals(ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE, ex.limit)
        assertEquals(ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE + 1, ex.attempted)
    }

    @Test
    fun `setReceiptsForItem erlaubt alle 19 Belege auf einer einzigen Position`() {
        val invoiceRepo = mock<InvoiceRepository>()
        val receiptRepo = mock<ReceiptRepository>()
        val svc = ReceiptLinkService(receiptRepo, invoiceRepo, invoiceLookup, ReceiptPoolService(receiptRepo, mock()), ReceiptBudgetService(receiptRepo))
        val item = InvoiceItem().edit { id = 51L }
        val invoice = openInvoice(9L).apply { itemList.asMutableForTest().add(item) }
        whenever(invoiceLookup.findInvoiceByItemId(51L)).thenReturn(invoice)
        val exactlyMax = (1L..ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE.toLong()).toList()
        exactlyMax.forEach { id -> whenever(receiptRepo.findById(id)).thenReturn(receipt(id)) }

        assertDoesNotThrow { svc.setReceiptsForItem(51L, exactlyMax) }

        assertEquals(ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE, item.receiptList.size)
    }

    @Test
    fun `setReceiptsForItem zaehlt einen an Rechnung und Position haengenden Beleg nur einmal`() {
        val invoiceRepo = mock<InvoiceRepository>()
        val receiptRepo = mock<ReceiptRepository>()
        val svc = ReceiptLinkService(receiptRepo, invoiceRepo, invoiceLookup, ReceiptPoolService(receiptRepo, mock()), ReceiptBudgetService(receiptRepo))
        val item = InvoiceItem().edit { id = 52L }
        val invoice = openInvoice(10L).apply { itemList.asMutableForTest().add(item) }
        whenever(invoiceLookup.findInvoiceByItemId(52L)).thenReturn(invoice)
        val shared = receipt(200L)
        // Already on the invoice as a whole — the guard must dedupe by object key against this.
        whenever(receiptRepo.findForInvoice(10L, 0, Int.MAX_VALUE)).thenReturn(listOf(shared))
        whenever(receiptRepo.findById(200L)).thenReturn(shared)

        assertDoesNotThrow { svc.setReceiptsForItem(52L, listOf(200L)) }

        assertEquals(listOf(200L), item.receiptList.mapNotNull { it.id })
    }

    /** These three numbers add up exactly (19 x 1 MiB + 1 MiB reserve = 20 MiB), so a receipt set
     *  that stays within both the per-upload cap and the count limit can never itself blow the
     *  document budget. [DocumentTooLarge][de.littletrotter.shared.receipt.BudgetVerdict.DocumentTooLarge]
     *  is only reachable via receipts the 1 MiB upload cap never saw — legacy rows from before the
     *  cap was tightened (PO decision, IR program README: the 1 MiB limit is enforced at upload
     *  time only, not retroactively on existing data).
     *
     *  PO decision: an oversized estimate no longer refuses the *link* — the user can still reduce
     *  or remove receipts before generation, and [GenerationJobCreationService] is the place that
     *  refuses (`GenerationJobCreationServiceTest`). This pins the new behaviour at the service
     *  [requireWithinBudget] itself sits behind: the write goes through and the invoice ends up
     *  carrying all five, even though the resulting budget verdict is
     *  [DocumentTooLarge][de.littletrotter.shared.receipt.BudgetVerdict.DocumentTooLarge]. */
    @Test
    fun `setReceiptsForInvoice laesst die Verknuepfung zu wenn nur die geschaetzte Dokumentgroesse das Limit sprengt`() {
        val invoice = openInvoice(11L)
        whenever(invoiceRepository.findById(11L)).thenReturn(invoice)
        // Five legacy-sized receipts (5 MiB each, pre-dating the 1 MiB upload cap): 25 MiB + 1 MiB
        // reserve = 26 MiB, well over the 20 MiB document limit, while the count (5) stays far
        // under the 19-receipt limit — only the size guard would have caught this.
        val legacyReceipts = (1L..5L).map { id ->
            aReceipt {
                this.id = id
                fileName = "legacy-$id.pdf"
                objectKey = "receipt-pool/legacy-$id.pdf"
                sizeBytes = 5L * 1024 * 1024
                description = "Legacy $id"
                documentDate = java.time.LocalDate.of(2026, 1, 1)
            }
        }
        legacyReceipts.forEach { r -> whenever(receiptRepository.findById(r.id!!)).thenReturn(r) }

        assertDoesNotThrow { service.setReceiptsForInvoice(11L, legacyReceipts.mapNotNull { it.id }) }

        assertEquals(legacyReceipts.map { it.id }, invoice.receiptList.mapNotNull { it.id })
        verify(invoiceRepository).save(invoice)
        // Confirms the write succeeded despite a DocumentTooLarge verdict, not because the fixture
        // actually stayed within budget — same set, same estimator the service itself used.
        val verdict = de.littletrotter.shared.receipt.InvoiceDocumentBudget.calculate(
            legacyReceipts.map { de.littletrotter.shared.receipt.ReceiptSize(it.objectKey, de.littletrotter.shared.receipt.DocumentSize.ofBytes(it.sizeBytes!!)) },
        ).verdict
        assertTrue(verdict is de.littletrotter.shared.receipt.BudgetVerdict.DocumentTooLarge)
    }

    /** BudgetVerdict.calculate reports the count violation over the size violation when both are
     *  broken at once (the count is the rule a user can act on without doing
     *  arithmetic); this pins that [requireWithinBudget] preserves that priority rather than
     *  picking whichever verdict it happens to see first. */
    @Test
    fun `setReceiptsForInvoice meldet die Anzahl wenn Anzahl UND Groesse gleichzeitig gesprengt werden`() {
        val invoice = openInvoice(13L)
        whenever(invoiceRepository.findById(13L)).thenReturn(invoice)
        val tooManyAndTooBig = (1L..25L).map { id ->
            aReceipt {
                this.id = id
                fileName = "huge-$id.pdf"
                objectKey = "receipt-pool/huge-$id.pdf"
                sizeBytes = 5L * 1024 * 1024
                description = "Huge $id"
                documentDate = java.time.LocalDate.of(2026, 1, 1)
            }
        }
        tooManyAndTooBig.forEach { r -> whenever(receiptRepository.findById(r.id!!)).thenReturn(r) }

        val ex = assertThrows(ReceiptCountLimitExceededException::class.java) {
            service.setReceiptsForInvoice(13L, tooManyAndTooBig.mapNotNull { it.id })
        }

        assertEquals(ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE, ex.limit)
        assertEquals(25, ex.attempted)
    }

    @Test
    fun `setReceiptsForInvoice verweigert eine Rechnung mit unbekannter Belegroesse`() {
        val invoice = openInvoice(12L)
        whenever(invoiceRepository.findById(12L)).thenReturn(invoice)
        val unknownSize = aReceipt {
            id = 300L; fileName = "unknown.pdf"; objectKey = "receipt-pool/unknown.pdf"
            // Both mandatory fields set so this test still isolates the unknown-size guard.
            description = "Unknown size"; documentDate = java.time.LocalDate.of(2026, 1, 1)
        }
        whenever(receiptRepository.findById(300L)).thenReturn(unknownSize)

        assertThrows(ApplicationValidationException::class.java) {
            service.setReceiptsForInvoice(12L, listOf(300L))
        }
        verify(invoiceRepository, never()).save(any())
    }

    @Test
    fun `setReceiptsForItem verweigert eine Position mit unbekannter Belegroesse`() {
        val invoiceRepo = mock<InvoiceRepository>()
        val receiptRepo = mock<ReceiptRepository>()
        val svc = ReceiptLinkService(receiptRepo, invoiceRepo, invoiceLookup, ReceiptPoolService(receiptRepo, mock()), ReceiptBudgetService(receiptRepo))
        val item = InvoiceItem().edit { id = 53L }
        val invoice = openInvoice(14L).apply { itemList.asMutableForTest().add(item) }
        whenever(invoiceLookup.findInvoiceByItemId(53L)).thenReturn(invoice)
        val unknownSize = aReceipt {
            id = 301L; fileName = "unknown.pdf"; objectKey = "receipt-pool/unknown2.pdf"
            // Both mandatory fields set so this test still isolates the unknown-size guard.
            description = "Unknown size"; documentDate = java.time.LocalDate.of(2026, 1, 1)
        }
        whenever(receiptRepo.findById(301L)).thenReturn(unknownSize)

        assertThrows(ApplicationValidationException::class.java) {
            svc.setReceiptsForItem(53L, listOf(301L))
        }
    }

    /** The read-only guard must run before [receiptIds] is even resolved — a locked invoice with a
     *  budget-busting request must still surface [InvoiceImmutableException], not a budget
     *  exception (which would require resolving every id first) or a not-found error from a
     *  garbage id. */
    @Test
    fun `setReceiptsForInvoice verweigert eine festgeschriebene Rechnung noch vor der Budgetpruefung`() {
        val invoice = openInvoice(15L).apply { forceStatusForTest(InvoiceStatus.ISSUED) }
        whenever(invoiceRepository.findById(15L)).thenReturn(invoice)
        // 30 unresolvable ids: if the budget check (or receipt resolution) ran first, this would
        // surface a not-found or count-limit exception instead.
        val overBudgetGarbageIds = (9000L..9029L).toList()

        assertThrows(InvoiceImmutableException::class.java) {
            service.setReceiptsForInvoice(15L, overBudgetGarbageIds)
        }
        verify(receiptRepository, never()).findById(any())
    }

    /** [setReceiptsForItem]'s guard must see receipts already attached to *other* items of the
     *  same invoice, not just the invoice-level set — otherwise two positions could each carry 19
     *  distinct receipts and blow the invoice-wide 19-receipt budget unnoticed. */
    @Test
    fun `setReceiptsForItem zaehlt Belege anderer Positionen derselben Rechnung mit`() {
        val invoiceRepo = mock<InvoiceRepository>()
        val receiptRepo = mock<ReceiptRepository>()
        val svc = ReceiptLinkService(receiptRepo, invoiceRepo, invoiceLookup, ReceiptPoolService(receiptRepo, mock()), ReceiptBudgetService(receiptRepo))
        val ourItem = InvoiceItem().edit { id = 60L }
        val otherItem = InvoiceItem().edit { id = 61L }
        val invoice = openInvoice(16L).apply { itemList.asMutableForTest().addAll(listOf(ourItem, otherItem)) }
        whenever(invoiceLookup.findInvoiceByItemId(60L)).thenReturn(invoice)
        val otherItemsReceipts = (1L..18L).map { receipt(it) }
        whenever(receiptRepo.findItemReceiptsForInvoice(16L)).thenReturn(
            mapOf(61L to otherItemsReceipts),
        )
        val newOne = receipt(200L)
        whenever(receiptRepo.findById(200L)).thenReturn(newOne)

        // 18 (other item) + 1 (this write) = 19: right at the limit, must be accepted.
        assertDoesNotThrow { svc.setReceiptsForItem(60L, listOf(200L)) }

        // One more distinct receipt tips it over 19.
        val overflow = receipt(201L)
        whenever(receiptRepo.findById(201L)).thenReturn(overflow)
        val ex = assertThrows(ReceiptCountLimitExceededException::class.java) {
            svc.setReceiptsForItem(60L, listOf(200L, 201L))
        }
        assertEquals(ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE, ex.limit)
        assertEquals(20, ex.attempted)
    }

    /** [setReceiptsForInvoice]'s guard must see every item's own receipts, not just what is
     *  currently at invoice level — the invoice-level write replaces the invoice-level set only,
     *  so item-level receipts stay and must still count toward the shared 19-receipt budget. */
    @Test
    fun `setReceiptsForInvoice zaehlt vorhandene Belege der Positionen mit`() {
        val invoice = openInvoice(17L)
        whenever(invoiceRepository.findById(17L)).thenReturn(invoice)
        val itemLevelReceipts = (1L..19L).map { receipt(it) }
        whenever(receiptRepository.findItemReceiptsForInvoice(17L)).thenReturn(
            mapOf(70L to itemLevelReceipts),
        )
        val newInvoiceLevel = receipt(200L)
        whenever(receiptRepository.findById(200L)).thenReturn(newInvoiceLevel)

        // The 19 item-level receipts already saturate the budget; one more at invoice level tips it over.
        val ex = assertThrows(ReceiptCountLimitExceededException::class.java) {
            service.setReceiptsForInvoice(17L, listOf(200L))
        }

        assertEquals(ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE, ex.limit)
        assertEquals(20, ex.attempted)
        verify(invoiceRepository, never()).save(any())
    }

    // ── invoiceDocumentBudget, the read API that renders its hint from ────────────

    @Test
    fun `invoiceDocumentBudget kombiniert Rechnungs- und Positionsbelege dedupliziert`() {
        val invoiceLevel = listOf(receipt(1L), receipt(2L))
        // receipt 2L also hangs on an item — must be counted once, not twice.
        val itemLevel = mapOf(80L to listOf(receipt(2L), receipt(3L)))
        whenever(receiptRepository.findForInvoice(18L, 0, Int.MAX_VALUE)).thenReturn(invoiceLevel)
        whenever(receiptRepository.findItemReceiptsForInvoice(18L)).thenReturn(itemLevel)

        val budget = budgetService.invoiceDocumentBudget(18L)

        assertEquals(3, budget.distinctReceiptCount)
        assertEquals(de.littletrotter.shared.receipt.BudgetVerdict.Accepted, budget.verdict)
    }

    @Test
    fun `invoiceDocumentBudget ist leer wenn die Rechnung keine Belege hat`() {
        whenever(receiptRepository.findForInvoice(19L, 0, Int.MAX_VALUE)).thenReturn(emptyList())
        whenever(receiptRepository.findItemReceiptsForInvoice(19L)).thenReturn(emptyMap())

        val budget = budgetService.invoiceDocumentBudget(19L)

        assertEquals(0, budget.distinctReceiptCount)
        assertEquals(ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE, budget.remainingSlots)
    }

    // ── upload — Valkey fast path (InvoiceCachePort), otherwise only tested with NoopInvoiceCachePort ──

    private fun validPdfBytes(tag: Byte = 0x00): ByteArray =
        byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x37, tag)

    @Test
    fun `upload liest bei bekanntem Checksum-Cache-Treffer die Datenbank statt neu hochzuladen`() {
        val cache = mock<InvoiceCachePort>()
        val svc = receiptUploadService(receiptRepository, documentStore, cache, FIXED_TIME_SOURCE)
        val bytes = validPdfBytes()
        val existing = aReceipt { id = 1L; objectKey = "receipt-pool/x.pdf" }
        whenever(cache.isChecksumKnown(any())).thenReturn(true)
        whenever(receiptRepository.findByChecksum(any())).thenReturn(existing)
        whenever(documentStore.exists(existing.objectKey)).thenReturn(true)

        val result = svc.upload("a.pdf", "Beleg a.pdf", bytes, LocalDate.of(2026, 3, 14))

        assertEquals(existing, result)
        verify(documentStore, never()).put(any(), any(), any())
    }

    @Test
    fun `upload stellt das Objekt im Store wieder her wenn der Cache-Treffer stimmt aber das Objekt fehlt`() {
        val cache = mock<InvoiceCachePort>()
        val svc = receiptUploadService(receiptRepository, documentStore, cache, FIXED_TIME_SOURCE)
        val bytes = validPdfBytes()
        val existing = aReceipt { id = 1L; objectKey = "receipt-pool/x.pdf" }
        whenever(cache.isChecksumKnown(any())).thenReturn(true)
        whenever(receiptRepository.findByChecksum(any())).thenReturn(existing)
        whenever(documentStore.exists(existing.objectKey)).thenReturn(false)

        svc.upload("a.pdf", "Beleg a.pdf", bytes, LocalDate.of(2026, 3, 14))

        verify(documentStore).put(existing.objectKey, bytes, "application/pdf")
    }

    @Test
    fun `upload wirft einen veralteten Checksum-Cache-Treffer weg wenn die Datenbank ihn nicht mehr kennt`() {
        val cache = mock<InvoiceCachePort>()
        val svc = receiptUploadService(receiptRepository, documentStore, cache, FIXED_TIME_SOURCE)
        val bytes = validPdfBytes()
        whenever(cache.isChecksumKnown(any())).thenReturn(true)
        // The cache claims the checksum is known, but no database row for it exists anymore
        // (e.g. the receipt was deleted in the meantime) -- the hit is stale.
        whenever(receiptRepository.findByChecksum(any())).thenReturn(null)
        whenever(documentStore.exists(any())).thenReturn(false)
        whenever(receiptRepository.saveAndFlush(any())).thenAnswer { it.arguments[0] }

        svc.upload("a.pdf", "Beleg a.pdf", bytes, LocalDate.of(2026, 3, 14))

        verify(cache).evictChecksum(any())
        // Written once before the lock transaction and once more under the lock, because the
        // mocked store reports the object as missing both times.
        verify(documentStore, times(2)).put(any(), any(), any())
    }

    // ── The ten branches named reachable-but-untested in this test's scope extension:
    // upload()'s race-window re-check (both outcomes), the post-failure cleanup guard, the whole of
    // findReceiptUsageSummary (no test at all before this), delete()'s checksum-cache eviction
    // guard, and findItemWithInvoice's second `?: throw`. ──────────────────────────────────────

    @Test
    fun `upload returns the concurrently persisted receipt when another writer closes the checksum race first`() {
        val cache = mock<InvoiceCachePort>()
        val svc = receiptUploadService(receiptRepository, documentStore, cache, FIXED_TIME_SOURCE)
        val bytes = validPdfBytes()
        val winner = aReceipt { id = 7L; objectKey = "receipt-pool/winner.pdf" }
        whenever(cache.isChecksumKnown(any())).thenReturn(false)
        // First call (before the object put): no row yet. Second call (the race re-check after the
        // put): a concurrent writer's row has landed in between.
        whenever(receiptRepository.findByChecksum(any())).thenReturn(null, winner)
        whenever(documentStore.exists(any())).thenReturn(false)

        val result = svc.upload("a.pdf", "Beleg a.pdf", bytes, LocalDate.of(2026, 3, 14))

        assertEquals(winner, result)
        verify(cache).markChecksumKnown(any())
        verify(receiptRepository, never()).saveAndFlush(any())
    }

    @Test
    fun `upload keeps the object and writes it only once when the save fails and no winner ever appears`() {
        val cache = mock<InvoiceCachePort>()
        val svc = receiptUploadService(receiptRepository, documentStore, cache, FIXED_TIME_SOURCE)
        val bytes = validPdfBytes()
        whenever(cache.isChecksumKnown(any())).thenReturn(false)
        // No row ever appears, before or after the put, or after the failed save.
        whenever(receiptRepository.findByChecksum(any())).thenReturn(null)
        // The object already exists under the lock, so only the pre-transaction put happens and the
        // object is not deleted once the save fails (content-addressed, a later upload reuses it).
        whenever(documentStore.exists(any())).thenReturn(true)
        whenever(receiptRepository.saveAndFlush(any())).thenThrow(PersistenceException("DB save failed"))

        assertThrows(PersistenceException::class.java) { svc.upload("a.pdf", "Beleg a.pdf", bytes, LocalDate.of(2026, 3, 14)) }

        verify(documentStore, times(1)).put(any(), any(), any())
        verify(documentStore, never()).delete(any())
    }

    @Test
    fun `findReceiptUsageSummary returns the unused label when the receipt has no usage`() {
        whenever(receiptRepository.findReceiptUsage(1L)).thenReturn(emptyList())

        val result = poolService.findReceiptUsageSummary(1L, "unused", "invoice level")

        assertEquals("unused", result)
    }

    @Test
    fun `findReceiptUsageSummary joins every usage row, invoice-level and per-position, into one label`() {
        val usage = listOf(
            ReceiptUsage(invoiceId = 1L, invoiceNumber = "RE-1", positionId = "10", invoiceItemId = 100L),
            ReceiptUsage(invoiceId = 2L, invoiceNumber = "RE-2", positionId = null, invoiceItemId = null),
        )
        whenever(receiptRepository.findReceiptUsage(1L)).thenReturn(usage)

        val result = poolService.findReceiptUsageSummary(1L, "unused", "invoice level")

        assertEquals("RE-1 10, RE-2 invoice level", result)
    }

    @Test
    fun `delete does not evict the checksum cache when the receipt has no checksum`() {
        val cache = mock<InvoiceCachePort>()
        val svc = ReceiptPoolService(receiptRepository, documentStore, cache)
        val r = aReceipt { id = 9L; objectKey = "receipt-pool/9.pdf"; sha256Checksum = null }
        whenever(receiptRepository.findById(9L)).thenReturn(r)
        whenever(receiptRepository.findReceiptUsage(9L)).thenReturn(emptyList())

        svc.delete(9L)

        verify(cache, never()).evictChecksum(any())
    }

    @Test
    fun `delete does not evict the checksum cache when the receipt's checksum is blank`() {
        val cache = mock<InvoiceCachePort>()
        val svc = ReceiptPoolService(receiptRepository, documentStore, cache)
        val r = aReceipt { id = 9L; objectKey = "receipt-pool/9.pdf"; sha256Checksum = "   " }
        whenever(receiptRepository.findById(9L)).thenReturn(r)
        whenever(receiptRepository.findReceiptUsage(9L)).thenReturn(emptyList())

        svc.delete(9L)

        verify(cache, never()).evictChecksum(any())
    }

    @Test
    fun `delete evicts the checksum cache when the receipt has a real checksum`() {
        val cache = mock<InvoiceCachePort>()
        val svc = ReceiptPoolService(receiptRepository, documentStore, cache)
        val r = aReceipt { id = 9L; objectKey = "receipt-pool/9.pdf"; sha256Checksum = "abc123" }
        whenever(receiptRepository.findById(9L)).thenReturn(r)
        whenever(receiptRepository.findReceiptUsage(9L)).thenReturn(emptyList())

        svc.delete(9L)

        verify(cache).evictChecksum("abc123")
    }

    @Test
    fun `findItemWithInvoice throws when the owning invoice no longer lists the item`() {
        // The invoice is found by item id, but its own `items` list doesn't contain that item —
        // findInvoiceByItemId's non-null result alone isn't enough; the second guard catches this.
        val invoice = openInvoice(9L)
        whenever(invoiceLookup.findInvoiceByItemId(77L)).thenReturn(invoice)

        assertThrows(ApplicationValidationException::class.java) {
            service.countReceiptsForItem(77L)
        }
    }

    @Test
    fun `findItemWithInvoice throws when the invoice has other items but not the requested one`() {
        val invoice = openInvoice(8L).apply { itemList.asMutableForTest().add(InvoiceItem().edit { id = 555L }) }
        whenever(invoiceLookup.findInvoiceByItemId(77L)).thenReturn(invoice)

        assertThrows(ApplicationValidationException::class.java) {
            service.countReceiptsForItem(77L)
        }
    }

    // ── Simple repository pass-throughs that had never been called against the real
    // ReceiptLinkService: every other test file either mocks ReceiptLinkService itself (the UI
    // Karibu tests) or exercises the repository double directly, never `service.findAssignableForItem`/
    // `countAssignableForItem`/`findUsageSummaries`/`findAssignableForInvoice`/
    // `countAssignableForInvoice`/`countReceiptsForInvoice` themselves. ──────────────────────────

    @Test
    fun `findAssignableForItem delegates to the repository with the item id`() {
        val r1 = receipt(1L)
        whenever(receiptRepository.findAssignable(0, 50, "hotel", 10L, excludeReceiptIds = emptySet())).thenReturn(listOf(r1))

        val result = service.findAssignableForItem(10L, 0, 50, "hotel")

        assertEquals(listOf(r1), result)
    }

    @Test
    fun `countAssignableForItem delegates to the repository with the item id`() {
        whenever(receiptRepository.countAssignable("hotel", 10L, excludeReceiptIds = emptySet())).thenReturn(4L)

        val result = service.countAssignableForItem(10L, "hotel")

        assertEquals(4L, result)
    }

    @Test
    fun `findUsageSummaries returns per-receipt usage labels in one batched call`() {
        val usage = listOf(ReceiptUsage(invoiceId = 1L, invoiceNumber = "RE-1", positionId = "10"))
        whenever(receiptRepository.findReceiptUsageBatch(listOf(1L, 2L))).thenReturn(mapOf(1L to usage))

        val result = poolService.findUsageSummaries(listOf(1L, 2L), "unused", "invoice level")

        assertEquals(mapOf(1L to "RE-1 10", 2L to "unused"), result)
    }

    @Test
    fun `findAssignableForInvoice delegates to the repository excluding the invoice`() {
        val r1 = receipt(1L)
        whenever(receiptRepository.findAssignable(0, 50, "hotel", excludeInvoiceId = 42L)).thenReturn(listOf(r1))

        val result = service.findAssignableForInvoice(42L, 0, 50, "hotel")

        assertEquals(listOf(r1), result)
    }

    @Test
    fun `countAssignableForInvoice delegates to the repository excluding the invoice`() {
        whenever(receiptRepository.countAssignable("hotel", excludeInvoiceId = 42L)).thenReturn(3L)

        val result = service.countAssignableForInvoice(42L, "hotel")

        assertEquals(3L, result)
    }

    @Test
    fun `boundary - findAssignableForInvoice defaults filterText to null when omitted`() {
        val r1 = receipt(1L)
        whenever(receiptRepository.findAssignable(0, 50, null, excludeInvoiceId = 42L)).thenReturn(listOf(r1))

        val result = service.findAssignableForInvoice(42L, 0, 50)

        assertEquals(listOf(r1), result)
    }

    @Test
    fun `boundary - countAssignableForInvoice defaults filterText to null when omitted`() {
        whenever(receiptRepository.countAssignable(null, excludeInvoiceId = 42L)).thenReturn(3L)

        val result = service.countAssignableForInvoice(42L)

        assertEquals(3L, result)
    }

    @Test
    fun `countReceiptsForInvoice delegates to the repository`() {
        whenever(receiptRepository.countForInvoice(42L)).thenReturn(2L)

        val result = service.countReceiptsForInvoice(42L)

        assertEquals(2L, result)
    }
}
