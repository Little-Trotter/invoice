package de.littletrotter.application

import de.littletrotter.fixture.seededStandardTaxRate
import de.littletrotter.application.invoice.receiptUploadService
import de.littletrotter.application.invoice.port.outbound.InvoiceLookupRepository
import de.littletrotter.application.error.ApplicationValidationException
import de.littletrotter.application.dto.ReceiptDetachSelection
import de.littletrotter.application.invoice.ReceiptBudgetService
import de.littletrotter.application.invoice.ReceiptLinkService
import de.littletrotter.application.invoice.ReceiptPoolService
import de.littletrotter.application.invoice.ReceiptUploadService
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.Money
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * PO addition (2026-08-30) to BL-1 — removal emphasis: beyond the assignment location matrix
 * ([ReceiptAssignmentMatrixIT]), explicit later-removal scenarios: assign -> use -> remove
 * (single, multiple, all) -> usage summaries, counts and eligibility update correctly; no orphan
 * rows in either junction table (`invoice_item_receipt`, `invoice_receipt`).
 *
 * Fixture helpers duplicated locally per this suite's established per-file convention (see
 * [ReceiptAssignmentMatrixIT]'s own KDoc).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class ReceiptRemovalLifecycleIT {

    @Inject
    lateinit var receiptRepository: ReceiptRepository

    @Inject
    lateinit var invoiceRepository: InvoiceRepository

    @Inject
    lateinit var invoiceLookup: InvoiceLookupRepository

    @Inject
    lateinit var em: EntityManager

    private fun pdf(marker: Byte): ByteArray =
        byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x37, marker)

    private fun service(): TestReceiptServices {
        val store = InMemoryStore()
        val pool = ReceiptPoolService(receiptRepository, store)
        val budget = ReceiptBudgetService(receiptRepository)
        return TestReceiptServices(
            upload = receiptUploadService(receiptRepository, store, time = FIXED_TIME_SOURCE),
            link = ReceiptLinkService(receiptRepository, invoiceRepository, invoiceLookup, pool, budget),
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
        val sender = aSenderProfile {
            name = "Removal Test GmbH"; invoicePrefix = "RM$suffix-"; registerCourt = "AG Musterstadt"
        }
        val recipientEntity = aRecipient { customerNumber = CustomerNumber("RMCUST-$suffix"); name = "Kunde AG" }
        em.persist(sender)
        em.persist(recipientEntity)

        val invoice = anInvoice(
            invoiceNumber = InvoiceNumber(number),
            senderProfile = sender,
            recipient = recipientEntity,
            invoiceDate = LocalDate.of(2026, 7, 1),
            paymentTermsDays = 14,
            status = status,
            items = List(itemCount) { idx ->
                anInvoiceItem(
                taxRateRef = em.seededStandardTaxRate(),
                    positionId = "POS-00${idx + 1}",
                    description = "Leistung ${idx + 1}",
                    quantity = BigDecimal.ONE,
                    unitPriceNet = Money(BigDecimal("100.00")),
                )
            },
        )
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

    private fun allItemReceiptRows(itemId: Long): Long =
        (
            em.createNativeQuery("SELECT COUNT(*) FROM invoice_item_receipt WHERE invoice_item_id = :itemId")
                .setParameter("itemId", itemId).singleResult as Number
            ).toLong()

    private fun allInvoiceReceiptRows(invoiceId: Long): Long =
        (
            em.createNativeQuery("SELECT COUNT(*) FROM invoice_receipt WHERE invoice_id = :invoiceId")
                .setParameter("invoiceId", invoiceId).singleResult as Number
            ).toLong()

    /** Event 13 = INVOICE_RECEIPT_UNASSIGNED, 15 = ITEM_RECEIPT_UNASSIGNED (chk_invoice_
     *  audit_journal_event / InvoiceAuditEvent ordinal, see V1__baseline_schema.sql section 8). */
    private fun journalEventCount(invoiceId: Long, event: Int): Long =
        (
            em.createNativeQuery("SELECT COUNT(*) FROM invoice_audit_journal WHERE invoice_id = :invoiceId AND event = :event")
                .setParameter("invoiceId", invoiceId)
                .setParameter("event", event)
                .singleResult as Number
            ).toLong()

    // ── Item-level removal: single / multiple / all ─────────────────────────────

    @Test
    @Transactional
    fun `RM-T01 - item-level - removing a single receipt updates counts, summary and eligibility, no orphan row`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("RM-2026-07-00001", itemCount = 1)
        val itemId = invoice.itemList.first().id!!
        val r1 = svc.upload.upload("r1.pdf", "Beleg r1.pdf", pdf(1), LocalDate.of(2026, 3, 14))
        val r2 = svc.upload.upload("r2.pdf", "Beleg r2.pdf", pdf(2), LocalDate.of(2026, 3, 14))
        val r3 = svc.upload.upload("r3.pdf", "Beleg r3.pdf", pdf(3), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToItem(itemId, listOf(r1.id!!, r2.id!!, r3.id!!))
        em.flush()
        assertEquals(3L, svc.link.countReceiptsForItem(itemId))

        svc.link.removeReceiptsFromItem(itemId, listOf(r2.id!!))
        em.flush(); em.clear()

        assertEquals(2L, svc.link.countReceiptsForItem(itemId))
        assertEquals(setOf(r1.id, r3.id), svc.link.findReceiptsForItem(itemId).mapNotNull { it.id }.toSet())
        assertEquals(0L, itemReceiptLinkCount(itemId, r2.id!!), "no orphan row for the removed receipt")

        // Eligibility for the removed receipt resets to "unused".
        val check = svc.pool.checkDeleteEligibility(r2.id!!)
        assertEquals(0, check.usageCount)
        assertTrue(check.assignedInvoiceNumbers.isEmpty())
        assertTrue(check.deletable, "releasing the assignment makes the receipt deletable again")
        svc.pool.delete(r2.id!!)

        // Usage summary for the removed receipt no longer names the invoice.
        val summaries = svc.pool.findUsageSummaries(listOf(r1.id!!, r3.id!!), "nirgends verwendet", "(Rechnung)")
        assertTrue(summaries.getValue(r1.id!!).contains(invoice.invoiceNumber.value))
        assertTrue(summaries.getValue(r3.id!!).contains(invoice.invoiceNumber.value))
    }

    @Test
    @Transactional
    fun `RM-T02 - item-level - removing multiple receipts at once leaves exactly the remaining set, no orphans`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("RM-2026-07-00002", itemCount = 1)
        val itemId = invoice.itemList.first().id!!
        val ids = (1..5).map { svc.upload.upload("multi-$it.pdf", "Beleg multi-$it.pdf", pdf(it.toByte()), LocalDate.of(2026, 3, 14)).id!! }
        svc.link.addReceiptsToItem(itemId, ids)
        em.flush()

        val toRemove = listOf(ids[0], ids[2], ids[4])
        svc.link.removeReceiptsFromItem(itemId, toRemove)
        em.flush(); em.clear()

        val remaining = svc.link.findReceiptsForItem(itemId).mapNotNull { it.id }.toSet()
        assertEquals(setOf(ids[1], ids[3]), remaining)
        assertEquals(2L, svc.link.countReceiptsForItem(itemId))
        toRemove.forEach { removedId -> assertEquals(0L, itemReceiptLinkCount(itemId, removedId), "removed id $removedId must leave no orphan row") }
        remaining.forEach { keptId -> assertEquals(1L, itemReceiptLinkCount(itemId, keptId!!)) }

        toRemove.forEach { removedId ->
            val check = svc.pool.checkDeleteEligibility(removedId)
            assertEquals(0, check.usageCount, "removed receipt $removedId must be unused now")
        }
    }

    @Test
    @Transactional
    fun `RM-T03 - item-level - removing all receipts leaves no junction rows, receipts survive in the pool`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("RM-2026-07-00003", itemCount = 2)
        val first = invoice.itemList[0].id!!
        val second = invoice.itemList[1].id!!
        val onFirst = (1..3).map { svc.upload.upload("first-$it.pdf", "Beleg first-$it.pdf", pdf((it + 10).toByte()), LocalDate.of(2026, 3, 14)).id!! }
        val onSecond = (1..2).map { svc.upload.upload("second-$it.pdf", "Beleg second-$it.pdf", pdf((it + 20).toByte()), LocalDate.of(2026, 3, 14)).id!! }
        svc.link.addReceiptsToItem(first, onFirst)
        svc.link.addReceiptsToItem(second, onSecond)
        em.flush()

        svc.link.removeReceiptsFromItem(first, onFirst)
        svc.link.removeReceiptsFromItem(second, onSecond)
        em.flush(); em.clear()

        assertEquals(0, svc.link.findReceiptsForItem(first).size)
        assertEquals(0, svc.link.findReceiptsForItem(second).size)
        assertEquals(0L, allItemReceiptRows(first), "no junction row must remain for item 1")
        assertEquals(0L, allItemReceiptRows(second), "no junction row must remain for item 2")

        (onFirst + onSecond).forEach { id ->
            assertNotNull(receiptRepository.findById(id), "receipt $id must survive in the pool")
            val check = svc.pool.checkDeleteEligibility(id)
            assertEquals(0, check.usageCount, "receipt $id must be fully unused after removing all links")
        }
        assertEquals(5L, svc.pool.countAll(null), "all five receipts remain available in the pool")
    }

    // ── Invoice-level removal: single / multiple / all ──────────────────────────

    @Test
    @Transactional
    fun `RM-T04 - invoice-level - removing a single receipt updates counts, summary and eligibility, no orphan row`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("RM-2026-07-00004", itemCount = 1)
        val r1 = svc.upload.upload("inv-r1.pdf", "Beleg inv-r1.pdf", pdf(31), LocalDate.of(2026, 3, 14))
        val r2 = svc.upload.upload("inv-r2.pdf", "Beleg inv-r2.pdf", pdf(32), LocalDate.of(2026, 3, 14))
        val r3 = svc.upload.upload("inv-r3.pdf", "Beleg inv-r3.pdf", pdf(33), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToInvoice(invoice.id!!, listOf(r1.id!!, r2.id!!, r3.id!!))
        em.flush()

        svc.link.removeReceiptsFromInvoice(invoice.id!!, listOf(r2.id!!))
        em.flush(); em.clear()

        assertEquals(2L, svc.link.countReceiptsForInvoice(invoice.id!!))
        assertEquals(setOf(r1.id, r3.id), svc.link.findReceiptsForInvoice(invoice.id!!).mapNotNull { it.id }.toSet())
        assertEquals(0L, invoiceReceiptLinkCount(invoice.id!!, r2.id!!), "no orphan row for the removed receipt")

        val check = svc.pool.checkDeleteEligibility(r2.id!!)
        assertEquals(0, check.usageCount)
        svc.pool.delete(r2.id!!)
    }

    @Test
    @Transactional
    fun `RM-T05 - invoice-level - removing multiple receipts at once leaves exactly the remaining set, no orphans`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("RM-2026-07-00005", itemCount = 1)
        val ids = (1..5).map { svc.upload.upload("inv-multi-$it.pdf", "Beleg inv-multi-$it.pdf", pdf((it + 40).toByte()), LocalDate.of(2026, 3, 14)).id!! }
        svc.link.addReceiptsToInvoice(invoice.id!!, ids)
        em.flush()

        val toRemove = listOf(ids[1], ids[3])
        svc.link.removeReceiptsFromInvoice(invoice.id!!, toRemove)
        em.flush(); em.clear()

        val remaining = svc.link.findReceiptsForInvoice(invoice.id!!).mapNotNull { it.id }.toSet()
        assertEquals(setOf(ids[0], ids[2], ids[4]), remaining)
        assertEquals(3L, svc.link.countReceiptsForInvoice(invoice.id!!))
        toRemove.forEach { removedId -> assertEquals(0L, invoiceReceiptLinkCount(invoice.id!!, removedId)) }
    }

    @Test
    @Transactional
    fun `RM-T06 - invoice-level - removing all receipts leaves no junction rows, receipts survive in the pool`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("RM-2026-07-00006", itemCount = 1)
        val ids = (1..4).map { svc.upload.upload("inv-all-$it.pdf", "Beleg inv-all-$it.pdf", pdf((it + 50).toByte()), LocalDate.of(2026, 3, 14)).id!! }
        svc.link.addReceiptsToInvoice(invoice.id!!, ids)
        em.flush()

        svc.link.removeReceiptsFromInvoice(invoice.id!!, ids)
        em.flush(); em.clear()

        assertEquals(0, svc.link.findReceiptsForInvoice(invoice.id!!).size)
        assertEquals(0L, allInvoiceReceiptRows(invoice.id!!))
        ids.forEach { id ->
            assertNotNull(receiptRepository.findById(id), "receipt $id must survive in the pool")
            assertEquals(0, svc.pool.checkDeleteEligibility(id).usageCount)
        }
    }

    // ── Mixed: removing one location leaves the other's link and rows intact ────

    @Test
    @Transactional
    fun `RM-T07 - removing only the item-level half of an A4 link leaves the invoice-level link and its row untouched`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("RM-2026-07-00007", itemCount = 1)
        val itemId = invoice.itemList.first().id!!
        val receipt = svc.upload.upload("mixed.pdf", "Beleg mixed.pdf", pdf(60), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToInvoice(invoice.id!!, listOf(receipt.id!!))
        svc.link.addReceiptsToItem(itemId, listOf(receipt.id!!))
        em.flush()
        assertEquals(2, svc.pool.checkDeleteEligibility(receipt.id!!).usageCount)

        svc.link.removeReceiptsFromItem(itemId, listOf(receipt.id!!))
        em.flush(); em.clear()

        assertEquals(0L, itemReceiptLinkCount(itemId, receipt.id!!), "the item-level row must be gone")
        assertEquals(1L, invoiceReceiptLinkCount(invoice.id!!, receipt.id!!), "the invoice-level row must be untouched")
        val check = svc.pool.checkDeleteEligibility(receipt.id!!)
        assertEquals(1, check.usageCount, "exactly the remaining invoice-level link")
        assertEquals(listOf(invoice.invoiceNumber.value), check.assignedInvoiceNumbers)
    }

    // ── detachReceipts: one atomic bulk command across positions + invoice level ────────

    @Test
    @Transactional
    fun `RM-T08 - detachReceipts removes invoice-level and multi-position receipts in one save, writing the matching journal rows`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("RM-2026-07-00008", itemCount = 2)
        val first = invoice.itemList[0].id!!
        val second = invoice.itemList[1].id!!
        val invLevel = svc.upload.upload("detach-inv.pdf", "Beleg detach-inv.pdf", pdf(70), LocalDate.of(2026, 3, 14))
        val onFirst = svc.upload.upload("detach-first.pdf", "Beleg detach-first.pdf", pdf(71), LocalDate.of(2026, 3, 14))
        val onSecond = svc.upload.upload("detach-second.pdf", "Beleg detach-second.pdf", pdf(72), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToInvoice(invoice.id!!, listOf(invLevel.id!!))
        svc.link.addReceiptsToItem(first, listOf(onFirst.id!!))
        svc.link.addReceiptsToItem(second, listOf(onSecond.id!!))
        em.flush(); em.clear()
        val invoiceUnassignedBefore = journalEventCount(invoice.id!!, 13)
        val itemUnassignedBefore = journalEventCount(invoice.id!!, 15)

        svc.link.detachReceipts(
            invoice.id!!,
            ReceiptDetachSelection(
                invoiceLevelReceiptIds = setOf(invLevel.id!!),
                itemReceiptIds = mapOf(first to setOf(onFirst.id!!), second to setOf(onSecond.id!!)),
            ),
        )
        em.flush(); em.clear()

        assertEquals(0, svc.link.findReceiptsForInvoice(invoice.id!!).size)
        assertEquals(0, svc.link.findReceiptsForItem(first).size)
        assertEquals(0, svc.link.findReceiptsForItem(second).size)
        assertEquals(0L, invoiceReceiptLinkCount(invoice.id!!, invLevel.id!!))
        assertEquals(0L, itemReceiptLinkCount(first, onFirst.id!!))
        assertEquals(0L, itemReceiptLinkCount(second, onSecond.id!!))
        assertEquals(
            invoiceUnassignedBefore + 1,
            journalEventCount(invoice.id!!, 13),
            "exactly one INVOICE_RECEIPT_UNASSIGNED row for the one invoice-level link removed",
        )
        assertEquals(
            itemUnassignedBefore + 2,
            journalEventCount(invoice.id!!, 15),
            "exactly two ITEM_RECEIPT_UNASSIGNED rows, one per detached position link",
        )
        (listOf(invLevel, onFirst, onSecond)).forEach { r ->
            assertEquals(0, svc.pool.checkDeleteEligibility(r.id!!).usageCount, "receipt ${r.id} must be fully unused")
        }
    }

    @Test
    @Transactional
    fun `RM-T09 - detachReceipts rolls back the whole selection when one item id does not belong to the invoice`() {
        clean()
        val svc = service()
        val invoiceA = invoiceWithItems("RM-2026-07-00009", itemCount = 1)
        val invoiceB = invoiceWithItems("RM-2026-07-00010", itemCount = 1)
        val itemA = invoiceA.itemList.first().id!!
        val foreignItemId = invoiceB.itemList.first().id!!
        val onA = svc.upload.upload("rollback-a.pdf", "Beleg rollback-a.pdf", pdf(73), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToItem(itemA, listOf(onA.id!!))
        em.flush(); em.clear()

        assertThrows(ApplicationValidationException::class.java) {
            svc.link.detachReceipts(
                invoiceA.id!!,
                ReceiptDetachSelection(itemReceiptIds = mapOf(itemA to setOf(onA.id!!), foreignItemId to setOf(999L))),
            )
        }
        em.flush(); em.clear()

        assertEquals(1, svc.link.findReceiptsForItem(itemA).size, "the valid part of the same rejected selection must not have been applied either")
        assertEquals(1L, itemReceiptLinkCount(itemA, onA.id!!))
    }

    @Test
    @Transactional
    fun `RM-T10 - detachReceipts on an already-detached id is a silent no-op, no journal row`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("RM-2026-07-00011", itemCount = 1)
        val itemId = invoice.itemList.first().id!!
        val r = svc.upload.upload("noop.pdf", "Beleg noop.pdf", pdf(74), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToItem(itemId, listOf(r.id!!))
        em.flush(); em.clear()
        svc.link.removeReceiptsFromItem(itemId, listOf(r.id!!))
        em.flush(); em.clear()
        val itemUnassignedAfterFirstDetach = journalEventCount(invoice.id!!, 15)

        // Second detach of the very same id — nothing left to unassign.
        svc.link.removeReceiptsFromItem(itemId, listOf(r.id!!))
        em.flush(); em.clear()

        assertEquals(0, svc.link.findReceiptsForItem(itemId).size)
        assertEquals(itemUnassignedAfterFirstDetach, journalEventCount(invoice.id!!, 15), "no second ITEM_RECEIPT_UNASSIGNED row for an id that was already detached")
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
