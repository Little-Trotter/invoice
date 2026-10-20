package de.littletrotter.application

import de.littletrotter.fixture.seededStandardTaxRate
import de.littletrotter.application.invoice.receiptUploadService
import de.littletrotter.application.invoice.port.outbound.InvoiceLookupRepository
import de.littletrotter.application.invoice.DocumentJobPublisher
import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.invoice.ReceiptCountLimitExceededException
import de.littletrotter.application.invoice.ReceiptBudgetService
import de.littletrotter.application.invoice.ReceiptLinkService
import de.littletrotter.application.invoice.ReceiptPoolService
import de.littletrotter.application.invoice.ReceiptUploadService
import de.littletrotter.domain.exception.InvoiceImmutableException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.domain.value.TaxRatePercent
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import de.littletrotter.fixture.aReceipt
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.edit
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.receipt.ReceiptSizePolicy
import de.littletrotter.test.requireJsonMap
import de.littletrotter.test.requireJsonMapList
import io.nats.client.JetStream
import io.nats.client.PublishOptions
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
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * belege-improve A4 — end-to-end coverage of assigning receipts to the invoice as a whole
 * (join table `invoice_receipt`), through the real service, real JPA mappings and a real
 * PostgreSQL. Mirrors `ReceiptAssignmentIT`'s rationale: the UI goes through [ReceiptLinkService], so
 * testing that against the database covers the same writes the dialog performs.
 *
 * Numbered BI-T01..BI-T05, Testfälle A4.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class InvoiceReceiptAssignmentIT {

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

    /** An invoice with [itemCount] positions in the given status, persisted and flushed. */
    private fun invoiceWithItems(
        number: String,
        itemCount: Int = 2,
        status: InvoiceStatus = InvoiceStatus.DRAFT,
    ): Invoice {
        val suffix = number.takeLast(5)
        val sender = aSenderProfile {
            name = "A4 Test GmbH"; invoicePrefix = "BRA$suffix-"; registerCourt = "AG Musterstadt"
        }
        val recipientEntity = aRecipient { customerNumber = CustomerNumber("A4CUST-$suffix"); name = "Kunde AG" }
        em.persist(sender)
        em.persist(recipientEntity)

        val invoice = anInvoice(
            invoiceNumber = InvoiceNumber(number),
            senderProfile = sender,
            recipient = recipientEntity,
            invoiceDate = LocalDate.of(2026, 5, 1),
            paymentTermsDays = 14,
            status = status,
            items = List(itemCount) { idx ->
                anInvoiceItem(
                taxRateRef = em.seededStandardTaxRate(),
                    positionId = "POS-00${idx + 1}",
                    description = "Leistung ${idx + 1}",
                    quantity = BigDecimal.ONE,
                    unitPriceNet = de.littletrotter.shared.Money(BigDecimal("100.00")),
                )
            },
        )
        em.persist(invoice)
        em.flush()
        return invoice
    }

    @Test
    @Transactional
    fun `BI-T01 - assigns a receipt to the invoice, positions stay untouched`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("BTA4-2026-05-00001")
        val firstItemId = invoice.itemList.first().id!!
        val receipt = svc.upload.upload("auftrag.pdf", "Auftragsbestätigung", pdf(1), LocalDate.of(2026, 3, 14))

        svc.link.addReceiptsToInvoice(invoice.id!!, listOf(receipt.id!!))
        em.flush(); em.clear()

        assertEquals(listOf("auftrag.pdf"), svc.link.findReceiptsForInvoice(invoice.id!!).map { it.fileName })
        assertEquals(1L, svc.link.countReceiptsForInvoice(invoice.id!!))
        // A4: positions carry the receipt nowhere — the link lives in invoice_receipt only.
        assertEquals(0, svc.link.findReceiptsForItem(firstItemId).size)
        assertEquals(0L, svc.link.countReceiptsForItem(firstItemId))
    }

    @Test
    @Transactional
    fun `BI-T02 - the same receipt at invoice AND position, usage names both`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("BTA4-2026-05-00002")
        val itemId = invoice.itemList.first().id!!
        val receipt = svc.upload.upload("sammelbeleg.pdf", "Beleg sammelbeleg.pdf", pdf(2), LocalDate.of(2026, 3, 14))

        svc.link.addReceiptsToInvoice(invoice.id!!, listOf(receipt.id!!))
        svc.link.addReceiptsToItem(itemId, listOf(receipt.id!!))
        em.flush(); em.clear()

        val usage = svc.pool.findReceiptUsage(receipt.id!!)
        assertEquals(2, usage.size)
        assertTrue(usage.any { it.positionId == null && it.invoiceId == invoice.id }, "invoice-level usage must be present")
        assertTrue(usage.any { it.positionId != null }, "position-level usage must be present")
        assertTrue(usage.all { it.invoiceId == invoice.id })
        assertEquals(1, svc.link.findReceiptsForInvoice(invoice.id!!).size)
        assertEquals(1, svc.link.findReceiptsForItem(itemId).size)
    }

    @Test
    @Transactional
    fun `BI-T03 - assigning to a finalized invoice is rejected`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("BTA4-2026-05-00003", status = InvoiceStatus.ISSUED)
        val receipt = svc.upload.upload("spaet.pdf", "Beleg spaet.pdf", pdf(3), LocalDate.of(2026, 3, 14))
        em.flush()

        assertThrows(InvoiceImmutableException::class.java) {
            svc.link.addReceiptsToInvoice(invoice.id!!, listOf(receipt.id!!))
        }
        // Nothing was written through on the failed attempt.
        assertEquals(0, svc.link.findReceiptsForInvoice(invoice.id!!).size)
    }

    @Test
    @Transactional
    fun `BI-T04 - a receipt hanging only on a finalized invoice cannot be deleted`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("BTA4-2026-05-00004")
        val receipt = svc.upload.upload("nachweis.pdf", "Beleg nachweis.pdf", pdf(4), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToInvoice(invoice.id!!, listOf(receipt.id!!))
        em.flush()

        // Freeze after the invoice-level link exists — the receipt is a goBD proof of the invoice.
        // A bulk JPQL update, not forceStatusForTest + merge: the latter mutates the managed
        // entity's field via reflection, which Hibernate's enhanced dirty-tracking never observes,
        // so the change never reached the database (found as a real regression while proving this
        // task's real-IT evidence).
        em.createQuery("UPDATE Invoice i SET i.status = :status WHERE i.id = :id")
            .setParameter("status", InvoiceStatus.ISSUED)
            .setParameter("id", invoice.id)
            .executeUpdate()
        em.flush(); em.clear()

        assertThrows(de.littletrotter.application.invoice.ReceiptAssignedToInvoiceException::class.java) {
            svc.pool.delete(receipt.id!!)
        }
        assertNotNull(receiptRepository.findById(receipt.id!!), "receipt must still exist")
        assertEquals(1, svc.link.findReceiptsForInvoice(invoice.id!!).size)
    }

    @Test
    @Transactional
    fun `BI-T05 - invoice picker excludes invoice-level receipts but shows position-only ones`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("BTA4-2026-05-00005")
        val itemId = invoice.itemList.first().id!!
        val onInvoice = svc.upload.upload("anrechnung.pdf", "Beleg anrechnung.pdf", pdf(5), LocalDate.of(2026, 3, 14))
        val onPositionOnly = svc.upload.upload("nur-position.pdf", "Beleg nur-position.pdf", pdf(6), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToInvoice(invoice.id!!, listOf(onInvoice.id!!))
        svc.link.addReceiptsToItem(itemId, listOf(onPositionOnly.id!!))
        em.flush(); em.clear()

        val picker = svc.link.findAssignableForInvoice(invoice.id!!, 0, 50, null)
        val ids = picker.mapNotNull { it.id }.toSet()
        assertFalse(ids.contains(onInvoice.id), "must not offer what the invoice already carries")
        assertTrue(ids.contains(onPositionOnly.id), "must offer what only hangs on a position")
        assertTrue(
            svc.link.countAssignableForInvoice(invoice.id!!, null) == picker.size.toLong(),
            "page and count must agree",
        )
    }

    // ── Invoice-wide receipt budget, formerly a per-target cap ──

    @Test
    @Transactional
    fun `BI-T09 - the 20th distinct receipt on an invoice is refused, the first 19 survive intact`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("BTA4-2026-05-00009")
        val limit = ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE
        val ids = (1..limit).map { svc.upload.upload("beleg-$it.pdf", "Beleg beleg-$it.pdf", pdf(it.toByte()), LocalDate.of(2026, 3, 14)).id!! }

        svc.link.addReceiptsToInvoice(invoice.id!!, ids)
        em.flush()
        assertEquals(limit, svc.link.findReceiptsForInvoice(invoice.id!!).size)

        val overflow = svc.upload.upload("zu-viel.pdf", "Beleg zu-viel.pdf", pdf(99), LocalDate.of(2026, 3, 14))
        val ex = assertThrows(ReceiptCountLimitExceededException::class.java) {
            svc.link.addReceiptsToInvoice(invoice.id!!, listOf(overflow.id!!))
        }
        assertEquals(limit, ex.limit)
        assertEquals(limit + 1, ex.attempted)

        // The decisive part: refused, not truncated — nothing was dropped to make room.
        em.flush(); em.clear()
        val remaining = svc.link.findReceiptsForInvoice(invoice.id!!).mapNotNull { it.id }.toSet()
        assertEquals(ids.toSet(), remaining, "the existing links must be untouched by the refused write")
    }

    @Test
    @Transactional
    fun `BI-T10 - removing a receipt frees a slot again`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("BTA4-2026-05-00010")
        val limit = ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE
        val ids = (1..limit).map { svc.upload.upload("voll-$it.pdf", "Beleg voll-$it.pdf", pdf(it.toByte()), LocalDate.of(2026, 3, 14)).id!! }
        svc.link.addReceiptsToInvoice(invoice.id!!, ids)
        val extra = svc.upload.upload("spaeter.pdf", "Beleg spaeter.pdf", pdf(98), LocalDate.of(2026, 3, 14))
        em.flush()

        svc.link.removeReceiptsFromInvoice(invoice.id!!, listOf(ids.first()))
        svc.link.addReceiptsToInvoice(invoice.id!!, listOf(extra.id!!))
        em.flush(); em.clear()

        val after = svc.link.findReceiptsForInvoice(invoice.id!!).mapNotNull { it.id }.toSet()
        assertEquals(limit, after.size)
        assertTrue(after.contains(extra.id), "the freed slot must be usable")
        assertFalse(after.contains(ids.first()), "the removed receipt must be gone")
    }

    // PO decision: the per-target cap goes away entirely, one rule (19
    // distinct receipts per invoice) applies, and a single position may carry all 19.
    @Test
    @Transactional
    fun `BI-T11 - a single position may carry the invoice-wide budget's full 19 receipts, the 20th is refused`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("BTA4-2026-05-00011")
        val itemId = invoice.itemList.first().id!!
        val limit = ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE
        val ids = (1..limit).map { svc.upload.upload("pos-$it.pdf", "Beleg pos-$it.pdf", pdf((it + 20).toByte()), LocalDate.of(2026, 3, 14)).id!! }
        svc.link.addReceiptsToItem(itemId, ids)
        em.flush()
        assertEquals(limit, svc.link.findReceiptsForItem(itemId).size, "a single position may carry the full budget")

        val overflow = svc.upload.upload("pos-zu-viel.pdf", "Beleg pos-zu-viel.pdf", pdf(97), LocalDate.of(2026, 3, 14))
        val ex = assertThrows(ReceiptCountLimitExceededException::class.java) {
            svc.link.addReceiptsToItem(itemId, listOf(overflow.id!!))
        }
        assertEquals(limit, ex.limit)
        assertEquals(limit + 1, ex.attempted)
        em.flush(); em.clear()
        assertEquals(limit, svc.link.findReceiptsForItem(itemId).size)
    }

    @Test
    @Transactional
    fun `BI-T12 - the invoice-level usage label comes from the caller, not from the service`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("BTA4-2026-05-00012")
        val receipt = svc.upload.upload("label.pdf", "Beleg label.pdf", pdf(96), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToInvoice(invoice.id!!, listOf(receipt.id!!))
        em.flush(); em.clear()

        val summaries = svc.pool.findUsageSummaries(listOf(receipt.id!!), "nirgends verwendet", "<<invoice-level>>")
        val text = summaries[receipt.id!!]!!
        assertTrue(text.contains("<<invoice-level>>"), "caller-supplied label must be used, was: $text")
        assertTrue(text.contains("BTA4-2026-05-00012"), "summary names the invoice, was: $text")
    }

    // No existing test assigns the SAME receipt at invoice level AND on two
    // DIFFERENT positions simultaneously (other tests cover two positions only, or invoice
    // level + one position only); this closes that combination, then detaches from one of the two
    // positions and proves neither the other position nor the invoice-level link is disturbed.
    @Test
    @Transactional
    fun `BI-T13 - RC-3 row 3-4 - same receipt at invoice level and two positions, detaching one leaves the other two untouched`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("BTA4-2026-05-00013", itemCount = 2)
        val firstItemId = invoice.itemList[0].id!!
        val secondItemId = invoice.itemList[1].id!!
        val shared = svc.upload.upload("dreifach.pdf", "Beleg dreifach.pdf", pdf(97), LocalDate.of(2026, 3, 14))

        svc.link.addReceiptsToInvoice(invoice.id!!, listOf(shared.id!!))
        svc.link.addReceiptsToItem(firstItemId, listOf(shared.id!!))
        svc.link.addReceiptsToItem(secondItemId, listOf(shared.id!!))
        em.flush(); em.clear()

        // Row 3 — three independent junction rows, none replacing another.
        val usage = svc.pool.findReceiptUsage(shared.id!!)
        assertEquals(3, usage.size, "invoice level + two positions = three distinct links")
        assertTrue(usage.any { it.positionId == null && it.invoiceId == invoice.id }, "invoice-level usage must be present")
        assertEquals(2, usage.count { it.positionId != null }, "both positions must carry their own link")
        assertEquals(1, svc.link.findReceiptsForInvoice(invoice.id!!).size)
        assertEquals(1, svc.link.findReceiptsForItem(firstItemId).size)
        assertEquals(1, svc.link.findReceiptsForItem(secondItemId).size)

        // Row 4 — detaching from the first position only.
        svc.link.removeReceiptsFromItem(firstItemId, listOf(shared.id!!))
        em.flush(); em.clear()

        assertEquals(0, svc.link.findReceiptsForItem(firstItemId).size, "the detached position must lose its link")
        assertEquals(1, svc.link.findReceiptsForItem(secondItemId).size, "the other position must keep its own link")
        assertEquals(1, svc.link.findReceiptsForInvoice(invoice.id!!).size, "the invoice-level link must be untouched")
        assertEquals(2, svc.pool.findReceiptUsage(shared.id!!).size, "exactly the two remaining links")
    }

    private class InMemoryStore : DocumentStore {
        private val objects = linkedMapOf<String, ByteArray>()
        override fun put(objectKey: String, data: ByteArray, contentType: String) { objects[objectKey] = data }
        override fun get(objectKey: String): ByteArray = objects[objectKey] ?: error("not found: $objectKey")
        override fun exists(objectKey: String): Boolean = objects.containsKey(objectKey)
        override fun enableImmutability(objectKey: String) {}
        override fun delete(objectKey: String) { objects.remove(objectKey) }
    }

    /** ReceiptService dissolved into 4 units; this file's own `svc.<method>()` calls below
     *  are unchanged, routed through the matching unit field instead of one shared mock/service. */
    private class TestReceiptServices(
        val upload: ReceiptUploadService,
        val link: ReceiptLinkService,
        val budget: ReceiptBudgetService,
        val pool: ReceiptPoolService,
    )

    /**
     * BI-T08 — the export path must send REAL receipt data to the worker. The UI publishes a
     * detached invoice whose receipts are id-only stubs (dtoToInvoice); before belege-improve A4
     * those empty stubs reached the worker and R10's annex silently got no pages at all. The
     * publisher now derives the wire value from the receipt pool (source of truth).
     */
    @Test
    @Transactional
    fun `BI-T08 - publisher sends real receipt data to the worker even for a detached stub invoice`() {
        clean()
        val svc = service()
        val invoice = invoiceWithItems("BTA4-2026-05-00007")
        val itemId = invoice.itemList.first().id!!
        val itemReceipt = svc.upload.upload("position-beleg.pdf", "Beleg position-beleg.pdf", pdf(7), LocalDate.of(2026, 3, 14))
        val invoiceReceipt = svc.upload.upload("rechnung-beleg.pdf", "Beleg rechnung-beleg.pdf", pdf(8), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToItem(itemId, listOf(itemReceipt.id!!))
        svc.link.addReceiptsToInvoice(invoice.id!!, listOf(invoiceReceipt.id!!))
        em.flush(); em.clear()

        // Detached stub invoice — exactly the shape dtoToInvoice produces for the UI export path:
        // id-only receipt stubs, no fileName/objectKey. The publisher must replace them with pool data.
        val detached = anInvoice().edit {
            this.id = invoice.id
            this.invoiceNumber = invoice.invoiceNumber
            addItem(anInvoiceItem().edit { taxRateRef = em.seededStandardTaxRate()
                this.id = itemId
                positionId = "POS-001"
                description = "Leistung 1"
                taxRateSnapshot = TaxRatePercent(BigDecimal("19"))
            })
            itemList.first().edit { receipts = listOf(aReceipt { this.id = itemReceipt.id }) }
            receipts = mutableListOf(aReceipt { this.id = invoiceReceipt.id })
        }

        val captured = mutableListOf<String>()
        val jetStream: JetStream = mock()
        whenever(jetStream.publishAsync(any<String>(), any<io.nats.client.impl.Headers>(), any<ByteArray>(), any<PublishOptions>()))
            .thenAnswer { inv ->
                captured.add(String(inv.getArgument<ByteArray>(2)))
                CompletableFuture.completedFuture(null)
            }
        val publisher = DocumentJobPublisher(
            jetStreamProvider = { jetStream },
            jobsSubjectOverride = "bi-t08",
            mapper = InvoiceDtoMapper(receiptRepository = receiptRepository),
        )

        publisher.publish(detached, 99L).get(10, TimeUnit.SECONDS)
        assertEquals(1, captured.size, "exactly one NATS message expected")

        val mapper = jacksonObjectMapper()
        val payload = mapper.readValue<Map<String, Any?>>(captured.first().toByteArray())
        val invoiceNode = payload["payload"].requireJsonMap()["invoice"].requireJsonMap()

        // Invoice-level receipts: real fileName + objectKey from the pool, not empty stubs.
        val invoiceLevel = invoiceNode["receipts"].requireJsonMapList()
        assertEquals(1, invoiceLevel.size)
        assertEquals("rechnung-beleg.pdf", invoiceLevel[0]["fileName"])
        assertTrue((invoiceLevel[0]["objectKey"] as String).startsWith("receipt-pool/"))

        // Item-level receipts: same — the stub's empty data must be replaced by pool data.
        val items = invoiceNode["items"].requireJsonMapList()
        val itemLevel = items.first()["receipts"].requireJsonMapList()
        assertEquals(1, itemLevel.size)
        assertEquals("position-beleg.pdf", itemLevel[0]["fileName"])
        assertTrue((itemLevel[0]["objectKey"] as String).startsWith("receipt-pool/"))
    }
}
