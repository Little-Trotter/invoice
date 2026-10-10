package de.littletrotter.application

import de.littletrotter.domain.invoice.forceStatusForTest

import de.littletrotter.application.invoice.ReceiptAssignedToInvoiceException
import de.littletrotter.application.invoice.ReceiptBudgetService
import de.littletrotter.application.dto.ReceiptDetachSelection
import de.littletrotter.application.invoice.ReceiptLinkService
import de.littletrotter.application.invoice.ReceiptPoolService
import de.littletrotter.application.invoice.ReceiptUploadService
import de.littletrotter.domain.exception.InvoiceImmutableException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.Money
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * BL-1 — the assignment location matrix (table
 * "Assignment location matrix"). Receipts attach at TWO places (`invoice_receipt` — Invoice.kt
 * ~333, "Ein Beleg darf beides" — and `invoice_item_receipt`); this file enumerates every one of
 * the nine constellations (A1..A9) the task spec names, each as its own test, against the real
 * [ReceiptLinkService], real JPA mappings and a real PostgreSQL — mirroring the rationale already
 * established by [ReceiptAssignmentIT]/[InvoiceReceiptAssignmentIT] (Karibu self-skips in this
 * repo, so the service/DB layer is where the dialog's actual writes are provable).
 *
 * Fixture helpers are duplicated locally rather than shared with the sibling IT files — the
 * established per-file convention in this suite (see Ep6FieldCombinationMappedE2EIT's own KDoc).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class ReceiptAssignmentMatrixIT {

    @Inject
    lateinit var receiptRepository: ReceiptRepository

    @Inject
    lateinit var invoiceRepository: InvoiceRepository

    @Inject
    lateinit var em: EntityManager

    private fun pdf(marker: Byte): ByteArray =
        byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x37, marker)

    private fun service(): TestReceiptServices {
        val store = InMemoryStore()
        val pool = ReceiptPoolService(receiptRepository, store)
        val budget = ReceiptBudgetService(receiptRepository)
        return TestReceiptServices(
            upload = ReceiptUploadService(receiptRepository, store, time = FIXED_TIME_SOURCE),
            link = ReceiptLinkService(receiptRepository, invoiceRepository, pool, budget),
            budget = budget,
            pool = pool,
        )
    }

    private fun clean() {
        em.createNativeQuery("DELETE FROM invoice_item_receipt").executeUpdate()
        em.createNativeQuery("DELETE FROM invoice_receipt").executeUpdate()
        em.createNativeQuery("DELETE FROM receipt").executeUpdate()
        em.createNativeQuery("DELETE FROM invoice_item").executeUpdate()
        em.createNativeQuery("DELETE FROM invoice").executeUpdate()
    }

    private fun invoiceWithItems(
        number: String,
        itemCount: Int = 2,
        status: InvoiceStatus = InvoiceStatus.DRAFT,
    ): Invoice {
        val suffix = number.takeLast(5)
        val sender = SenderProfile().apply {
            name = "Matrix Test GmbH"; invoicePrefix = "MX$suffix-"; registerCourt = "AG Musterstadt"
        }
        val recipientEntity = Recipient().apply { customerNumber = CustomerNumber("MXCUST-$suffix"); name = "Kunde AG" }
        em.persist(sender)
        em.persist(recipientEntity)

        val invoice = Invoice().apply {
            invoiceNumber = InvoiceNumber(number)
            senderProfile = sender
            this.recipient = recipientEntity
            invoiceDate = LocalDate.of(2026, 6, 1)
            paymentTermsDays = 14
            repeat(itemCount) { idx ->
                addItem(
                    InvoiceItem().apply {
                        positionId = "POS-00${idx + 1}"
                        description = "Leistung ${idx + 1}"
                        quantity = BigDecimal.ONE
                        unitPriceNet = Money(BigDecimal("100.00"))
                    },
                )
            }
        }
        invoice.forceStatusForTest(status)
        em.persist(invoice)
        em.flush()
        return invoice
    }

    private fun itemReceiptLinkCount(itemId: Long, receiptId: Long): Long =
        (
            em.createNativeQuery("SELECT COUNT(*) FROM invoice_item_receipt WHERE invoice_item_id = :itemId AND receipt_id = :receiptId")
                .setParameter("itemId", itemId)
                .setParameter("receiptId", receiptId)
                .singleResult as Number
            ).toLong()

    private fun invoiceReceiptLinkCount(invoiceId: Long, receiptId: Long): Long =
        (
            em.createNativeQuery("SELECT COUNT(*) FROM invoice_receipt WHERE invoice_id = :invoiceId AND receipt_id = :receiptId")
                .setParameter("invoiceId", invoiceId)
                .setParameter("receiptId", receiptId)
                .singleResult as Number
            ).toLong()

    // ── A1 — invoice-level only ─────────────────────────────────────────────────

    @Test
    @Transactional
    fun `A1 - invoice-level only - usage summary shows the invoice-level label, item queries do not list it`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("MX-2026-06-00001")
        val itemId = invoice.itemList.first().id!!
        val receipt = svc.upload.upload("auftragsbestaetigung.pdf", "Beleg auftragsbestaetigung.pdf", pdf(1), LocalDate.of(2026, 3, 14))

        svc.link.addReceiptsToInvoice(invoice.id!!, listOf(receipt.id!!))
        em.flush(); em.clear()

        val summaries = svc.pool.findUsageSummaries(listOf(receipt.id!!), "nirgends verwendet", "(Rechnung)")
        val text = summaries.getValue(receipt.id!!)
        assertTrue(text.contains(invoice.invoiceNumber.value), "summary must name the invoice: $text")
        assertTrue(text.contains("(Rechnung)"), "summary must carry the invoice-level label: $text")

        assertEquals(0, svc.link.findReceiptsForItem(itemId).size, "item queries must not list an invoice-level-only receipt")
        assertEquals(0L, svc.link.countReceiptsForItem(itemId))
        assertEquals(1, svc.link.findReceiptsForInvoice(invoice.id!!).size)
    }

    // ── A2 — item-level only (one item) ─────────────────────────────────────────

    @Test
    @Transactional
    fun `A2 - item-level only - usage lists the position, invoice-level label absent`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("MX-2026-06-00002")
        val itemId = invoice.itemList.first().id!!
        val receipt = svc.upload.upload("quittung.pdf", "Beleg quittung.pdf", pdf(2), LocalDate.of(2026, 3, 14))

        svc.link.addReceiptsToItem(itemId, listOf(receipt.id!!))
        em.flush(); em.clear()

        val usage = svc.pool.findReceiptUsage(receipt.id!!)
        assertEquals(1, usage.size)
        assertEquals("POS-001", usage.single().positionId)

        val summaries = svc.pool.findUsageSummaries(listOf(receipt.id!!), "nirgends verwendet", "<<invoice-level>>")
        val text = summaries.getValue(receipt.id!!)
        assertTrue(text.contains("POS-001"), "summary must name the position: $text")
        assertFalse(text.contains("<<invoice-level>>"), "item-only usage must not carry the invoice-level label: $text")
        assertEquals(0, svc.link.findReceiptsForInvoice(invoice.id!!).size, "invoice-level queries must not list a position-only receipt")
    }

    // ── A3 — same receipt on MULTIPLE items of one invoice ──────────────────────

    @Test
    @Transactional
    fun `A3 - same receipt on multiple items - counts per item correct, summary aggregates without duplication`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("MX-2026-06-00003", itemCount = 3)
        val first = invoice.itemList[0].id!!
        val second = invoice.itemList[1].id!!
        val third = invoice.itemList[2].id!!
        val shared = svc.upload.upload("sammelbeleg.pdf", "Beleg sammelbeleg.pdf", pdf(3), LocalDate.of(2026, 3, 14))

        svc.link.addReceiptsToItem(first, listOf(shared.id!!))
        svc.link.addReceiptsToItem(second, listOf(shared.id!!))
        em.flush(); em.clear()

        // Counts per item: attached to two, not the third.
        assertEquals(1L, svc.link.countReceiptsForItem(first))
        assertEquals(1L, svc.link.countReceiptsForItem(second))
        assertEquals(0L, svc.link.countReceiptsForItem(third))

        // Usage aggregates to exactly two rows — no duplication of either.
        val usage = svc.pool.findReceiptUsage(shared.id!!)
        assertEquals(2, usage.size)
        assertEquals(setOf("POS-001", "POS-002"), usage.map { it.positionId }.toSet())

        val summaries = svc.pool.findUsageSummaries(listOf(shared.id!!), "nirgends verwendet", "(Rechnung)")
        val text = summaries.getValue(shared.id!!)
        assertTrue(text.contains("POS-001") && text.contains("POS-002"), "summary must name both positions: $text")
        // No duplication: the summary carries exactly one occurrence of each position marker.
        assertEquals(1, Regex("POS-001").findAll(text).count(), "POS-001 must not repeat: $text")
        assertEquals(1, Regex("POS-002").findAll(text).count(), "POS-002 must not repeat: $text")
    }

    // ── A4 — same receipt invoice-level AND item-level simultaneously ───────────

    @Test
    @Transactional
    fun `A4 - same receipt invoice-level and item-level - both usages listed distinctly, eligibility counts both`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("MX-2026-06-00004")
        val itemId = invoice.itemList.first().id!!
        val receipt = svc.upload.upload("beides.pdf", "Beleg beides.pdf", pdf(4), LocalDate.of(2026, 3, 14))

        svc.link.addReceiptsToInvoice(invoice.id!!, listOf(receipt.id!!))
        svc.link.addReceiptsToItem(itemId, listOf(receipt.id!!))
        em.flush(); em.clear()

        val usage = svc.pool.findReceiptUsage(receipt.id!!)
        assertEquals(2, usage.size)
        assertTrue(usage.any { it.positionId == null }, "invoice-level usage row must be present")
        assertTrue(usage.any { it.positionId == "POS-001" }, "item-level usage row must be present")

        // Both survive independently in their respective queries.
        assertEquals(1, svc.link.findReceiptsForInvoice(invoice.id!!).size)
        assertEquals(1, svc.link.findReceiptsForItem(itemId).size)

        // Delete-eligibility counts BOTH links, not just one.
        val check = svc.pool.checkDeleteEligibility(receipt.id!!)
        assertEquals(2, check.usageCount, "usageCount must count both the invoice-level and the item-level link")
        assertEquals(listOf(invoice.invoiceNumber.value), check.assignedInvoiceNumbers, "one distinct invoice, two links")
    }

    // ── A5 — same receipt across TWO invoices ───────────────────────────────────

    @Test
    @Transactional
    fun `A5 - same receipt across two invoices - usage spans invoices, deleting from one leaves the other intact`() {
        clean()
        val svc = service()
        val invoiceA = invoiceWithItems("MX-2026-06-00005")
        val invoiceB = invoiceWithItems("MX-2026-06-00006")
        val itemA = invoiceA.itemList.first().id!!
        val receipt = svc.upload.upload("crossinvoice.pdf", "Beleg crossinvoice.pdf", pdf(5), LocalDate.of(2026, 3, 14))

        svc.link.addReceiptsToItem(itemA, listOf(receipt.id!!))
        svc.link.addReceiptsToInvoice(invoiceB.id!!, listOf(receipt.id!!))
        em.flush(); em.clear()

        val usageBefore = svc.pool.findReceiptUsage(receipt.id!!)
        assertEquals(2, usageBefore.size)
        assertEquals(setOf(invoiceA.id, invoiceB.id), usageBefore.map { it.invoiceId }.toSet())

        // Remove the link on invoice A only.
        svc.link.removeReceiptsFromItem(itemA, listOf(receipt.id!!))
        em.flush(); em.clear()

        val usageAfter = svc.pool.findReceiptUsage(receipt.id!!)
        assertEquals(1, usageAfter.size, "removing from invoice A must not touch invoice B's link")
        assertEquals(invoiceB.id, usageAfter.single().invoiceId)
        assertEquals(0, svc.link.findReceiptsForItem(itemA).size)
        assertEquals(1, svc.link.findReceiptsForInvoice(invoiceB.id!!).size, "invoice B's link must survive intact")
    }

    // ── A6 — move: remove from item, add to invoice level (and reverse) ─────────

    @Test
    @Transactional
    fun `A6 - move between item-level and invoice-level - no orphan junction rows, counts update`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("MX-2026-06-00007")
        val itemId = invoice.itemList.first().id!!
        val receipt = svc.upload.upload("wandert.pdf", "Beleg wandert.pdf", pdf(6), LocalDate.of(2026, 3, 14))

        // Start item-level.
        svc.link.addReceiptsToItem(itemId, listOf(receipt.id!!))
        em.flush()
        assertEquals(1L, itemReceiptLinkCount(itemId, receipt.id!!))
        assertEquals(0L, invoiceReceiptLinkCount(invoice.id!!, receipt.id!!))

        // Move: item -> invoice level. Flushing between the two calls is deliberate, not
        // decorative: without it, JPA's cascade=ALL merge in setReceiptsForInvoice's own
        // invoiceRepository.save(invoice) touches the invoice's sibling InvoiceItem entity too,
        // and — empirically, reproduced while writing this test — silently discards the
        // still-pending item.receipts.clear() from the immediately preceding call, leaving the
        // "removed" row in place. Two real UI actions are always two separate transactions
        // (a Vaadin round trip each), so this gap between same-transaction calls without a flush
        // is a test-fixture concern, not a reachable production path — recorded as a finding
        // (BL-1-F1) rather than "fixed" here (test files only, per task scope).
        svc.link.removeReceiptsFromItem(itemId, listOf(receipt.id!!))
        em.flush()
        svc.link.addReceiptsToInvoice(invoice.id!!, listOf(receipt.id!!))
        em.flush(); em.clear()

        assertEquals(0L, itemReceiptLinkCount(itemId, receipt.id!!), "the old item-level row must be gone, not orphaned")
        assertEquals(1L, invoiceReceiptLinkCount(invoice.id!!, receipt.id!!))
        assertEquals(1, svc.link.findReceiptsForInvoice(invoice.id!!).size)
        assertEquals(0, svc.link.findReceiptsForItem(itemId).size)

        // Reverse: invoice -> item level (same flush discipline as above, BL-1-F1).
        svc.link.removeReceiptsFromInvoice(invoice.id!!, listOf(receipt.id!!))
        em.flush()
        svc.link.addReceiptsToItem(itemId, listOf(receipt.id!!))
        em.flush(); em.clear()

        assertEquals(0L, invoiceReceiptLinkCount(invoice.id!!, receipt.id!!), "the invoice-level row must be gone after moving back")
        assertEquals(1L, itemReceiptLinkCount(itemId, receipt.id!!))
        assertEquals(1, svc.link.findReceiptsForItem(itemId).size)
        assertEquals(0, svc.link.findReceiptsForInvoice(invoice.id!!).size)
    }

    // ── A7 — setReceiptsForItem replaces the set atomically ─────────────────────

    @Test
    @Transactional
    fun `A7 - setReceiptsForItem replaces atomically - removed detach, kept stay, order stable`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("MX-2026-06-00008")
        val itemId = invoice.itemList.first().id!!
        val r1 = svc.upload.upload("r1.pdf", "Beleg r1.pdf", pdf(10), LocalDate.of(2026, 3, 14))
        val r2 = svc.upload.upload("r2.pdf", "Beleg r2.pdf", pdf(11), LocalDate.of(2026, 3, 14))
        val r3 = svc.upload.upload("r3.pdf", "Beleg r3.pdf", pdf(12), LocalDate.of(2026, 3, 14))

        svc.link.addReceiptsToItem(itemId, listOf(r1.id!!, r2.id!!, r3.id!!))
        em.flush(); em.clear()
        assertEquals(3, svc.link.findReceiptsForItem(itemId).size)

        // Atomic replace: drop r2, keep r1 and r3.
        svc.link.setReceiptsForItem(itemId, listOf(r3.id!!, r1.id!!))
        em.flush(); em.clear()

        val after = svc.link.findReceiptsForItem(itemId).mapNotNull { it.id }.toSet()
        assertEquals(setOf(r1.id, r3.id), after, "kept ones must stay, removed one must detach")
        assertEquals(0L, itemReceiptLinkCount(itemId, r2.id!!), "the dropped receipt must leave no orphan junction row")
        assertEquals(1L, itemReceiptLinkCount(itemId, r1.id!!))
        assertEquals(1L, itemReceiptLinkCount(itemId, r3.id!!))

        // Order stable: repeated reads return the identical, reproducible order (the documented
        // uploadedAt DESC, id DESC contract — PanacheReceiptRepository.findForItem's own KDoc on
        // why an explicit ORDER BY exists at all), independent of how the replace call ordered them.
        val readOnce = svc.link.findReceiptsForItem(itemId).mapNotNull { it.id }
        val readTwice = svc.link.findReceiptsForItem(itemId).mapNotNull { it.id }
        assertEquals(readOnce, readTwice, "reading the same set twice must yield the same order")
    }

    // ── A8 — delete-eligibility per location ────────────────────────────────────

    @Test
    @Transactional
    fun `A8 - delete-eligibility per location - unused eligible, A1-A2-A4 each blocked-confirmation with correct usage list`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("MX-2026-06-00009", itemCount = 1)
        val itemId = invoice.itemList.first().id!!

        // Unused -> deletable.
        val unused = svc.upload.upload("ungenutzt.pdf", "Beleg ungenutzt.pdf", pdf(20), LocalDate.of(2026, 3, 14))
        val unusedCheck = svc.pool.checkDeleteEligibility(unused.id!!)
        assertEquals(0, unusedCheck.usageCount)
        assertTrue(unusedCheck.assignedInvoiceNumbers.isEmpty())
        assertTrue(unusedCheck.deletable)
        svc.pool.delete(unused.id!!) // must not throw
        em.flush(); em.clear() // BL-1-F1-adjacent: drop the removed entity from the persistence
        // context before the next step touches invoice/item collections again.

        // receipt-delete-guard — every assigned shape below is refused outright; releasing the
        // assignment on the (still open) invoice is what makes the receipt deletable again.

        // A1-style (invoice-level only).
        val invoiceLevel = svc.upload.upload("a1.pdf", "Beleg a1.pdf", pdf(21), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToInvoice(invoice.id!!, listOf(invoiceLevel.id!!))
        em.flush(); em.clear()
        val a1Check = svc.pool.checkDeleteEligibility(invoiceLevel.id!!)
        assertEquals(1, a1Check.usageCount)
        assertFalse(a1Check.deletable)
        assertEquals(listOf(invoice.invoiceNumber.value), a1Check.assignedInvoiceNumbers)
        assertThrows(ReceiptAssignedToInvoiceException::class.java) { svc.pool.delete(invoiceLevel.id!!) }
        svc.link.removeReceiptsFromInvoice(invoice.id!!, listOf(invoiceLevel.id!!))
        em.flush(); em.clear()
        svc.pool.delete(invoiceLevel.id!!)
        em.flush(); em.clear()

        // A2-style (item-level only).
        val itemLevel = svc.upload.upload("a2.pdf", "Beleg a2.pdf", pdf(22), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToItem(itemId, listOf(itemLevel.id!!))
        em.flush(); em.clear()
        val a2Check = svc.pool.checkDeleteEligibility(itemLevel.id!!)
        assertEquals(1, a2Check.usageCount)
        assertEquals(listOf(invoice.invoiceNumber.value), a2Check.assignedInvoiceNumbers)
        assertThrows(ReceiptAssignedToInvoiceException::class.java) { svc.pool.delete(itemLevel.id!!) }
        svc.link.removeReceiptsFromItem(itemId, listOf(itemLevel.id!!))
        em.flush(); em.clear()
        svc.pool.delete(itemLevel.id!!)
        em.flush(); em.clear()

        // A4-style (both levels at once) -> usageCount 2, one distinct invoice number.
        val both = svc.upload.upload("a4.pdf", "Beleg a4.pdf", pdf(23), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToInvoice(invoice.id!!, listOf(both.id!!))
        svc.link.addReceiptsToItem(itemId, listOf(both.id!!))
        em.flush(); em.clear()
        val a4Check = svc.pool.checkDeleteEligibility(both.id!!)
        assertEquals(2, a4Check.usageCount)
        assertEquals(listOf(invoice.invoiceNumber.value), a4Check.assignedInvoiceNumbers)
        assertThrows(ReceiptAssignedToInvoiceException::class.java) { svc.pool.delete(both.id!!) }
        // Releasing only ONE of the two links is not enough — the other still blocks the delete.
        svc.link.removeReceiptsFromInvoice(invoice.id!!, listOf(both.id!!))
        em.flush(); em.clear()
        assertThrows(ReceiptAssignedToInvoiceException::class.java) { svc.pool.delete(both.id!!) }
        svc.link.removeReceiptsFromItem(itemId, listOf(both.id!!))
        em.flush(); em.clear()
        svc.pool.delete(both.id!!)
        em.flush(); em.clear()

        assertEquals(0, svc.link.findReceiptsForItem(itemId).size)
        assertEquals(0, svc.link.findReceiptsForInvoice(invoice.id!!).size)
    }

    // ── A9 — finalized invoice ───────────────────────────────────────────────────

    @Test
    @Transactional
    fun `A9 - finalized invoice - assignments become read-only, reads still work, pool delete requires confirmation via read-only path`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("MX-2026-06-00010")
        val itemId = invoice.itemList.first().id!!
        val itemReceipt = svc.upload.upload("item-vor-abschluss.pdf", "Beleg item-vor-abschluss.pdf", pdf(30), LocalDate.of(2026, 3, 14))
        val invoiceReceipt = svc.upload.upload("rechnung-vor-abschluss.pdf", "Beleg rechnung-vor-abschluss.pdf", pdf(31), LocalDate.of(2026, 3, 14))
        val neverAttached = svc.upload.upload("nachtraeglich.pdf", "Beleg nachtraeglich.pdf", pdf(32), LocalDate.of(2026, 3, 14))

        // Assign while still open.
        svc.link.addReceiptsToItem(itemId, listOf(itemReceipt.id!!))
        svc.link.addReceiptsToInvoice(invoice.id!!, listOf(invoiceReceipt.id!!))
        em.flush()

        // Freeze. A bulk JPQL update, not forceStatusForTest + merge: the latter mutates the
        // managed entity's field via reflection, which Hibernate's enhanced dirty-tracking never
        // observes (the enhancer instruments PUTFIELD instructions inside Invoice's own bytecode,
        // not raw Field.set() calls from outside it), so the change never reached the database —
        // found as a real regression while proving this task's real-IT evidence: em.clear() below
        // then re-loads DRAFT from the row that was never actually updated.
        em.createQuery("UPDATE Invoice i SET i.status = :status WHERE i.id = :id")
            .setParameter("status", InvoiceStatus.SENT)
            .setParameter("id", invoice.id)
            .executeUpdate()
        em.flush(); em.clear()

        // Every write path is rejected once finalized.
        assertThrows(InvoiceImmutableException::class.java) { svc.link.addReceiptsToItem(itemId, listOf(neverAttached.id!!)) }
        assertThrows(InvoiceImmutableException::class.java) { svc.link.addReceiptsToInvoice(invoice.id!!, listOf(neverAttached.id!!)) }
        assertThrows(InvoiceImmutableException::class.java) { svc.link.removeReceiptsFromItem(itemId, listOf(itemReceipt.id!!)) }
        assertThrows(InvoiceImmutableException::class.java) { svc.link.removeReceiptsFromInvoice(invoice.id!!, listOf(invoiceReceipt.id!!)) }
        assertThrows(InvoiceImmutableException::class.java) { svc.link.setReceiptsForItem(itemId, emptyList()) }
        assertThrows(InvoiceImmutableException::class.java) { svc.link.setReceiptsForInvoice(invoice.id!!, emptyList()) }

        // Reads still work and reflect the frozen state unchanged.
        assertEquals(listOf("item-vor-abschluss.pdf"), svc.link.findReceiptsForItem(itemId).map { it.fileName })
        assertEquals(listOf("rechnung-vor-abschluss.pdf"), svc.link.findReceiptsForInvoice(invoice.id!!).map { it.fileName })

        // checkDeleteEligibility names the finalized invoice both links belong to.
        val itemCheck = svc.pool.checkDeleteEligibility(itemReceipt.id!!)
        assertEquals(listOf(invoice.invoiceNumber.value), itemCheck.assignedInvoiceNumbers)
        assertFalse(itemCheck.deletable)

        val invoiceLevelCheck = svc.pool.checkDeleteEligibility(invoiceReceipt.id!!)
        assertEquals(listOf(invoice.invoiceNumber.value), invoiceLevelCheck.assignedInvoiceNumbers)
        assertFalse(invoiceLevelCheck.deletable)

        // Pool delete is refused outright, and here the assignment cannot be released either —
        // the invoice is read-only, so the receipt stays a current proof for good.
        assertThrows(ReceiptAssignedToInvoiceException::class.java) { svc.pool.delete(itemReceipt.id!!) }
        assertThrows(ReceiptAssignedToInvoiceException::class.java) { svc.pool.delete(invoiceReceipt.id!!) }
        assertNotNull(receiptRepository.findById(itemReceipt.id!!), "receipt must survive as a current proof")
        assertNotNull(receiptRepository.findById(invoiceReceipt.id!!), "receipt must survive as a current proof")
    }

    @Test
    @Transactional
    fun `A9 extension - cancelled invoice follows the same read-only contract as finalized`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("MX-2026-06-00011")
        val itemId = invoice.itemList.first().id!!
        val receipt = svc.upload.upload("storniert.pdf", "Beleg storniert.pdf", pdf(33), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToItem(itemId, listOf(receipt.id!!))
        em.flush()

        // See the A9 test above for why this is a bulk JPQL update, not forceStatusForTest + merge.
        em.createQuery("UPDATE Invoice i SET i.status = :status WHERE i.id = :id")
            .setParameter("status", InvoiceStatus.CANCELLED)
            .setParameter("id", invoice.id)
            .executeUpdate()
        em.flush(); em.clear()

        assertThrows(InvoiceImmutableException::class.java) { svc.link.removeReceiptsFromItem(itemId, listOf(receipt.id!!)) }
        assertEquals(listOf("storniert.pdf"), svc.link.findReceiptsForItem(itemId).map { it.fileName })
        assertThrows(ReceiptAssignedToInvoiceException::class.java) { svc.pool.delete(receipt.id!!) }
    }

    // ── Same receipt at invoice level of two invoices: detachReceipts on one must not
    // touch the other (PI6-E-DETACH-KEEPS's backend counterpart; the Playwright spec of the same
    // name exercises this through the UI in little-trotter-admin-tools) ─────────────────────────

    @Test
    @Transactional
    fun `A10 - same receipt at invoice level of two invoices - detachReceipts on one leaves the other's link intact`() {
        clean()
        val svc = service()
        val invoiceA = invoiceWithItems("MX-2026-06-00012", itemCount = 1)
        val invoiceB = invoiceWithItems("MX-2026-06-00013", itemCount = 1)
        val shared = svc.upload.upload("beide-rechnungen.pdf", "Beleg beide-rechnungen.pdf", pdf(40), LocalDate.of(2026, 3, 14))

        svc.link.addReceiptsToInvoice(invoiceA.id!!, listOf(shared.id!!))
        svc.link.addReceiptsToInvoice(invoiceB.id!!, listOf(shared.id!!))
        em.flush(); em.clear()
        assertEquals(2, svc.pool.checkDeleteEligibility(shared.id!!).usageCount)

        svc.link.detachReceipts(invoiceA.id!!, ReceiptDetachSelection(invoiceLevelReceiptIds = setOf(shared.id!!)))
        em.flush(); em.clear()

        assertEquals(0L, invoiceReceiptLinkCount(invoiceA.id!!, shared.id!!), "invoice A's link must be gone")
        assertEquals(1L, invoiceReceiptLinkCount(invoiceB.id!!, shared.id!!), "invoice B's link must survive untouched")
        assertEquals(0, svc.link.findReceiptsForInvoice(invoiceA.id!!).size)
        assertEquals(1, svc.link.findReceiptsForInvoice(invoiceB.id!!).size)
        val check = svc.pool.checkDeleteEligibility(shared.id!!)
        assertEquals(1, check.usageCount, "exactly the remaining invoice-B link")
        assertEquals(listOf(invoiceB.invoiceNumber.value), check.assignedInvoiceNumbers)
    }

    private class InMemoryStore : DocumentStore {
        private val objects = linkedMapOf<String, ByteArray>()
        override fun put(objectKey: String, data: ByteArray, contentType: String) { objects[objectKey] = data }
        override fun get(objectKey: String): ByteArray = objects[objectKey] ?: error("not found: $objectKey")
        override fun exists(objectKey: String): Boolean = objects.containsKey(objectKey)
        override fun enableImmutability(objectKey: String) {}
        override fun delete(objectKey: String) { objects.remove(objectKey) }
    }

    /** `ReceiptService` no longer exists: this file's own `svc.<method>()` calls below
     *  are unchanged, routed through the matching unit field instead of one shared mock/service. */
    private class TestReceiptServices(
        val upload: ReceiptUploadService,
        val link: ReceiptLinkService,
        val budget: ReceiptBudgetService,
        val pool: ReceiptPoolService,
    )
}
