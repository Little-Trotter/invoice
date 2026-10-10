package de.littletrotter.application

import de.littletrotter.domain.invoice.forceStatusForTest

import de.littletrotter.application.invoice.ReceiptBudgetService
import de.littletrotter.application.invoice.ReceiptLinkService
import de.littletrotter.application.invoice.ReceiptPoolService
import de.littletrotter.application.invoice.ReceiptUploadService
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.shared.DocumentStore
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
 * belege-improve — end-to-end coverage of assigning receipts to invoice positions, through the
 * real service, the real JPA mappings and a real PostgreSQL (join table `invoice_item_receipt`).
 *
 * Why here and not in the UI layer: Karibu-Testing self-skips in this repo (Vaadin version
 * mismatch), so a click-level test would silently pass without asserting anything. Everything the
 * dialog does goes through [ReceiptLinkService]/[ReceiptUploadService]; testing that against the database exercises the same
 * writes the UI performs — including the n:m join, the read-only guard and the paging query that
 * feeds the picker.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class ReceiptAssignmentIT {

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
        // belege-improve A4 — the new invoice-level join table must be cleared before receipts,
        // or a leftover link blocks DELETE FROM receipt with an FK violation.
        em.createNativeQuery("DELETE FROM invoice_receipt").executeUpdate()
        em.createNativeQuery("DELETE FROM receipt").executeUpdate()
        em.createNativeQuery("DELETE FROM invoice_item").executeUpdate()
        em.createNativeQuery("DELETE FROM invoice").executeUpdate()
    }

    /** An invoice with [itemCount] positions in the given status, persisted and flushed. */
    private fun invoiceWithItems(
        number: String,
        itemCount: Int = 2,
        status: InvoiceStatus = InvoiceStatus.DRAFT,
    ): Invoice {
        // invoice_prefix and customer_number are unique per row — derive them from the invoice
        // number so each test brings its own master data instead of colliding with the previous one.
        val suffix = number.takeLast(5)
        val sender = SenderProfile().apply {
            name = "Beleg Test GmbH"; invoicePrefix = "BT$suffix-"; registerCourt = "AG Musterstadt"
        }
        val recipientEntity = Recipient().apply { customerNumber = CustomerNumber("CUST-$suffix"); name = "Kunde AG" }
        em.persist(sender)
        em.persist(recipientEntity)

        val invoice = Invoice().apply {
            invoiceNumber = InvoiceNumber(number)
            senderProfile = sender
            this.recipient = recipientEntity
            invoiceDate = LocalDate.of(2026, 3, 1)
            paymentTermsDays = 14
            repeat(itemCount) { idx ->
                addItem(
                    InvoiceItem().apply {
                        positionId = "POS-00${idx + 1}"
                        description = "Leistung ${idx + 1}"
                        quantity = BigDecimal.ONE
                        unitPriceNet = de.littletrotter.shared.Money(BigDecimal("100.00"))
                    },
                )
            }
        }
        invoice.forceStatusForTest(status)
        em.persist(invoice)
        em.flush()
        return invoice
    }

    @Test
    @Transactional
    fun `V4-T01 - assigns a pool receipt to a position and counts it`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("BT-2026-03-00001")
        val itemId = invoice.itemList.first().id!!
        val receipt = svc.upload.upload("hotel.pdf", "Hotelrechnung", pdf(1), LocalDate.of(2026, 3, 14))

        svc.link.addReceiptsToItem(itemId, listOf(receipt.id!!))
        em.flush(); em.clear()

        val attached = svc.link.findReceiptsForItem(itemId)
        assertEquals(1, attached.size)
        assertEquals("hotel.pdf", attached.single().fileName)
        assertEquals(1L, svc.link.countReceiptsForItem(itemId))
    }

    @Test
    @Transactional
    fun `V4-T02 - assigns two receipts in one step`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("BT-2026-03-00002")
        val itemId = invoice.itemList.first().id!!
        val a = svc.upload.upload("bahn.pdf", "Beleg bahn.pdf", pdf(2), LocalDate.of(2026, 3, 14))
        val b = svc.upload.upload("taxi.pdf", "Beleg taxi.pdf", pdf(3), LocalDate.of(2026, 3, 14))

        svc.link.addReceiptsToItem(itemId, listOf(a.id!!, b.id!!))
        em.flush(); em.clear()

        assertEquals(setOf("bahn.pdf", "taxi.pdf"), svc.link.findReceiptsForItem(itemId).map { it.fileName }.toSet())
    }

    @Test
    @Transactional
    fun `V4-T03 - the same receipt may hang on two positions (n to m)`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("BT-2026-03-00003", itemCount = 2)
        val first = invoice.itemList[0].id!!
        val second = invoice.itemList[1].id!!
        val shared = svc.upload.upload("sammelbeleg.pdf", "Beleg sammelbeleg.pdf", pdf(4), LocalDate.of(2026, 3, 14))

        svc.link.addReceiptsToItem(first, listOf(shared.id!!))
        svc.link.addReceiptsToItem(second, listOf(shared.id!!))
        em.flush(); em.clear()

        assertEquals(1, svc.link.findReceiptsForItem(first).size)
        assertEquals(1, svc.link.findReceiptsForItem(second).size)
        // R10 relies on exactly this: one receipt, two positions, one annex page naming both.
        assertEquals(2, svc.pool.findReceiptUsage(shared.id!!).size)
    }

    @Test
    @Transactional
    fun `V4-T04 - detaching removes only the link, the receipt stays in the pool`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("BT-2026-03-00004")
        val itemId = invoice.itemList.first().id!!
        val receipt = svc.upload.upload("quittung.pdf", "Beleg quittung.pdf", pdf(5), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToItem(itemId, listOf(receipt.id!!))
        em.flush(); em.clear()

        svc.link.removeReceiptsFromItem(itemId, listOf(receipt.id!!))
        em.flush(); em.clear()

        assertEquals(0, svc.link.findReceiptsForItem(itemId).size)
        assertNotNull(receiptRepository.findById(receipt.id!!), "receipt must survive in the pool")
        assertEquals(1L, svc.pool.countAll())
    }

    @Test
    @Transactional
    fun `V4-T05 - assigning to a finalized invoice is rejected`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("BT-2026-03-00005", status = InvoiceStatus.SENT)
        val itemId = invoice.itemList.first().id!!
        val receipt = svc.upload.upload("spaet.pdf", "Beleg spaet.pdf", pdf(6), LocalDate.of(2026, 3, 14))
        em.flush()

        assertThrows(de.littletrotter.domain.exception.InvoiceImmutableException::class.java) {
            svc.link.addReceiptsToItem(itemId, listOf(receipt.id!!))
        }
    }

    @Test
    @Transactional
    fun `V4-T06 - re-uploading the same bytes reuses the pool entry instead of duplicating`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("BT-2026-03-00006")
        val itemId = invoice.itemList.first().id!!
        val bytes = pdf(7)

        val first = svc.upload.upload("beleg.pdf", "Beleg beleg.pdf", bytes, LocalDate.of(2026, 3, 14))
        val second = svc.upload.upload("beleg-kopie.pdf", "Beleg beleg-kopie.pdf", bytes, LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToItem(itemId, listOf(second.id!!))
        em.flush(); em.clear()

        assertEquals(first.id, second.id, "SHA-256 dedup must return the existing receipt")
        assertEquals(1L, svc.pool.countAll())
        assertEquals(1, svc.link.findReceiptsForItem(itemId).size)
    }

    @Test
    @Transactional
    fun `V4-T07 - a receipt proving a finalized invoice cannot be deleted`() {
        clean()
        val svc = service()
        val draft = invoiceWithItems("BT-2026-03-00007")
        val itemId = draft.itemList.first().id!!
        val receipt = svc.upload.upload("nachweis.pdf", "Beleg nachweis.pdf", pdf(8), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToItem(itemId, listOf(receipt.id!!))
        em.flush()

        // Freeze the invoice after the link exists — the receipt is now a goBD proof.
        // A bulk JPQL update, not forceStatusForTest + merge: the latter mutates the managed
        // entity's field via reflection, which Hibernate's enhanced dirty-tracking never observes,
        // so the change never reached the database (found as a real regression while proving this
        // task's real-IT evidence).
        em.createQuery("UPDATE Invoice i SET i.status = :status WHERE i.id = :id")
            .setParameter("status", InvoiceStatus.SENT)
            .setParameter("id", draft.id)
            .executeUpdate()
        em.flush(); em.clear()

        val ex = assertThrows(de.littletrotter.application.invoice.ReceiptAssignedToInvoiceException::class.java) {
            svc.pool.delete(receipt.id!!)
        }
        assertTrue(
            ex.message!!.contains("BT-2026-03-00007"),
            "A4 requires the message to name the blocking invoice: ${ex.message}",
        )
        assertNotNull(receiptRepository.findById(receipt.id!!), "receipt must still exist")
    }

    @Test
    @Transactional
    fun `V4-T08 - deleting a receipt used by a draft is refused until the assignment is released`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("BT-2026-03-00008")
        val itemId = invoice.itemList.first().id!!
        val receipt = svc.upload.upload("entwurf.pdf", "Beleg entwurf.pdf", pdf(9), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToItem(itemId, listOf(receipt.id!!))
        em.flush(); em.clear()

        // receipt-delete-guard — a draft link blocks the delete like any other; the pool has no
        // way to break it, the assignment is released on the (still open) invoice itself.
        assertThrows(de.littletrotter.application.invoice.ReceiptAssignedToInvoiceException::class.java) {
            svc.pool.delete(receipt.id!!)
        }
        assertEquals(1, svc.link.findReceiptsForItem(itemId).size, "the refused delete changes nothing")

        svc.link.removeReceiptsFromItem(itemId, listOf(receipt.id!!))
        em.flush(); em.clear()
        svc.pool.delete(receipt.id!!)
        em.flush(); em.clear()
        assertEquals(0L, svc.pool.countAll())
        assertEquals(0, svc.link.findReceiptsForItem(itemId).size)
    }

    @Test
    @Transactional
    fun `V4-T09 - writing to a position that does not exist fails as a business error, not an NPE`() {
        clean()
        val svc = service()
        val receipt = svc.upload.upload("ohne-position.pdf", "Beleg ohne-position.pdf", pdf(10), LocalDate.of(2026, 3, 14))
        em.flush()

        // The dialog blocks this in the UI; the service must not fall over either.
        assertThrows(RuntimeException::class.java) {
            svc.link.addReceiptsToItem(-1L, listOf(receipt.id!!))
        }
    }

    @Test
    @Transactional
    fun `belege-improve - the picker hides receipts the position already carries`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("BT-2026-03-00010")
        val itemId = invoice.itemList.first().id!!
        val assigned = svc.upload.upload("schon-dran.pdf", "Beleg schon-dran.pdf", pdf(11), LocalDate.of(2026, 3, 14))
        val free = svc.upload.upload("noch-frei.pdf", "Beleg noch-frei.pdf", pdf(12), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToItem(itemId, listOf(assigned.id!!))
        em.flush(); em.clear()

        val offered = svc.link.findAssignableForItem(itemId, 0, 50)

        assertEquals(listOf("noch-frei.pdf"), offered.map { it.fileName })
        // Count and page must agree, otherwise paging in the picker breaks.
        assertEquals(1L, svc.link.countAssignableForItem(itemId))
        assertEquals(2L, svc.pool.countAll(), "both receipts remain in the pool itself")
        assertEquals(free.id, offered.single().id)
    }

    @Test
    @Transactional
    fun `belege-improve - picker filter narrows both page and count consistently`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("BT-2026-03-00011")
        val itemId = invoice.itemList.first().id!!
        svc.upload.upload("hotel-berlin.pdf", "Beleg hotel-berlin.pdf", pdf(13), LocalDate.of(2026, 3, 14))
        svc.upload.upload("bahn-hamburg.pdf", "Beleg bahn-hamburg.pdf", pdf(14), LocalDate.of(2026, 3, 14))
        svc.upload.upload("hotel-bremen.pdf", "Beleg hotel-bremen.pdf", pdf(15), LocalDate.of(2026, 3, 14))
        em.flush(); em.clear()

        val page = svc.link.findAssignableForItem(itemId, 0, 50, "hotel")

        assertEquals(2, page.size)
        assertEquals(2L, svc.link.countAssignableForItem(itemId, "hotel"))
        assertTrue(page.all { it.fileName.contains("hotel") })
    }

    @Test
    @Transactional
    fun `belege-improve - usage summaries for a whole page come back in one lookup`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("BT-2026-03-00012", itemCount = 2)
        val first = invoice.itemList[0].id!!
        val second = invoice.itemList[1].id!!
        val used = svc.upload.upload("verwendet.pdf", "Beleg verwendet.pdf", pdf(16), LocalDate.of(2026, 3, 14))
        val unused = svc.upload.upload("unbenutzt.pdf", "Beleg unbenutzt.pdf", pdf(17), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToItem(first, listOf(used.id!!))
        svc.link.addReceiptsToItem(second, listOf(used.id!!))
        em.flush(); em.clear()

        val summaries = svc.pool.findUsageSummaries(listOf(used.id!!, unused.id!!), "nirgends verwendet", "(Rechnung)")

        assertEquals(2, summaries.size)
        val usedText = summaries[used.id!!]!!
        assertTrue(usedText.contains("BT-2026-03-00012"), "summary names the invoice: $usedText")
        assertTrue(usedText.contains("POS-001") && usedText.contains("POS-002"), "names both positions: $usedText")
        assertEquals("nirgends verwendet", summaries[unused.id!!])
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
