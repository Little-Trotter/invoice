package de.littletrotter.e2e.receipts

import de.littletrotter.application.error.ApplicationValidationException
import de.littletrotter.application.dto.ReceiptDetachSelection
import de.littletrotter.application.invoice.ReceiptBudgetService
import de.littletrotter.application.invoice.ReceiptLinkService
import de.littletrotter.application.invoice.ReceiptPoolService
import de.littletrotter.application.invoice.ReceiptUploadService
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.S3TestResource
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.Money
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.VatId

/**
 * [ReceiptLinkService.detachReceipts], the single atomic bulk-removal command the
 * receipts tab's contextual selection bar drives, exercised through the real stack (Postgres + S3)
 * the way [ReceiptDeleteGuardE2EIT] already does for the delete guard: a bulk selection can span
 * several positions and the invoice level in one call, must never be blocked by the size budget
 * guard (that guard exists to stop the document from growing, not from shrinking), and must treat
 * an id that is no longer attached as a silent no-op rather than an error.
 *
 *  - DET-1 happy path — invoice-level + two positions detached in one atomic call
 *  - DET-2 border — a foreign item id in the selection refuses the whole call, nothing detaches
 *  - DET-3 border — a stale expected version surfaces the existing concurrency exception, nothing
 *    detaches
 *  - DET-4 border — an invoice already over the size budget (legacy data) can still shrink
 *  - DET-5 border — detaching an id that is not attached is a silent no-op
 *
 * Fixture shape copied from [ReceiptDeleteGuardE2EIT] (same module idiom: plain JPA fixtures, a
 * hand-built [ReceiptLinkService] over the injected repositories).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(S3TestResource::class)
class ReceiptDetachE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var receiptRepository: ReceiptRepository

    @Inject
    lateinit var invoiceRepository: InvoiceRepository

    @Inject
    lateinit var documentStore: DocumentStore

    /** The receipt services, split into 4 units; a plain field bundle, no forwarding methods,
     *  since this fixture is shared across tests that each only need a subset. */
    private class TestReceiptServices(
        val upload: ReceiptUploadService,
        val link: ReceiptLinkService,
        val budget: ReceiptBudgetService,
        val pool: ReceiptPoolService,
    )

    private fun receiptService(): TestReceiptServices {
        val pool = ReceiptPoolService(receiptRepository, documentStore)
        val budget = ReceiptBudgetService(receiptRepository)
        return TestReceiptServices(
            upload = ReceiptUploadService(receiptRepository, documentStore, de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z"))),
            link = ReceiptLinkService(receiptRepository, invoiceRepository, pool, budget),
            budget = budget,
            pool = pool,
        )
    }

    /** A real, parseable one-page PDF; a distinct [marker] gives a distinct checksum/object key
     *  (the upload path deduplicates by checksum). */
    private fun pdf(marker: String): ByteArray {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            PDPageContentStream(doc, doc.getPage(0)).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                cs.newLineAtOffset(50f, 700f)
                cs.showText("receipt-detach fixture $marker")
                cs.endText()
            }
            val os = ByteArrayOutputStream()
            doc.save(os)
            return os.toByteArray()
        }
    }

    /** A DRAFT invoice with [itemCount] items, persisted via plain JPA. */
    private fun buildInvoiceFixture(tag: String, itemCount: Int = 2): Invoice {
        val sender = SenderProfile().apply {
            name = "Loesen E2E GmbH"; invoicePrefix = "$tag-"; registerCourt = "AG Musterstadt"
            vatId = VatId("DE111111111")
        }
        sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
        val recipient = Recipient().apply { customerNumber = CustomerNumber("$tag-100"); name = "Kunde AG"; vatId = VatId("DE222222222") }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
        em.persist(sender); em.persist(recipient)

        val taxRate = TaxRate().apply {
            code = "${tag}_DE_STD_19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isActive = true; isSelectable = true
        }
        em.persist(taxRate)

        val invoice = Invoice.withInitialStatus(InvoiceStatus.DRAFT).apply {
            invoiceNumber = InvoiceNumber("$tag-2026-0001")
            senderProfile = sender
            this.recipient = recipient
            invoiceDate = LocalDate.of(2026, 9, 1)
            paymentTermsDays = 14
            repeat(itemCount) { idx ->
                addItem(
                    InvoiceItem().apply {
                        positionId = "${idx + 1}"; description = "Position ${idx + 1}"
                        quantity = BigDecimal.ONE; unitPriceNet = Money(BigDecimal("100.00"))
                        sortOrder = idx + 1
                    }.also { it.applyTaxRule(taxRate) },
                )
            }
        }
        invoice.recalculateTotals()
        em.persist(invoice); em.flush()
        return invoice
    }

    private fun itemReceiptLinkCount(itemId: Long, receiptId: Long): Long =
        (
            em.createNativeQuery("SELECT COUNT(*) FROM invoice_item_receipt WHERE invoice_item_id = :itemId AND receipt_id = :receiptId")
                .setParameter("itemId", itemId).setParameter("receiptId", receiptId).singleResult as Number
            ).toLong()

    @Test
    @Transactional
    fun `DET-1 happy path - invoice-level and two-position receipts detach in one atomic call`() {
        val svc = receiptService()
        val invoice = buildInvoiceFixture("DET1", itemCount = 2)
        val first = invoice.itemList[0].id!!
        val second = invoice.itemList[1].id!!
        val invLevel = svc.upload.upload("det1-inv.pdf", "Beleg det1-inv", pdf("det1-inv"), LocalDate.of(2026, 3, 14))
        val onFirst = svc.upload.upload("det1-first.pdf", "Beleg det1-first", pdf("det1-first"), LocalDate.of(2026, 3, 14))
        val onSecond = svc.upload.upload("det1-second.pdf", "Beleg det1-second", pdf("det1-second"), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToInvoice(invoice.id!!, listOf(invLevel.id!!))
        svc.link.addReceiptsToItem(first, listOf(onFirst.id!!))
        svc.link.addReceiptsToItem(second, listOf(onSecond.id!!))
        em.flush(); em.clear()

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
        listOf(invLevel, onFirst, onSecond).forEach {
            assertEquals(0, svc.pool.checkDeleteEligibility(it.id!!).usageCount, "receipt ${it.id} must be fully released back to the pool")
        }
    }

    @Test
    @Transactional
    fun `DET-2 atomic rollback - a foreign item id in the selection refuses the whole call`() {
        val svc = receiptService()
        val invoiceA = buildInvoiceFixture("DET2A", itemCount = 1)
        val invoiceB = buildInvoiceFixture("DET2B", itemCount = 1)
        val itemA = invoiceA.itemList.first().id!!
        val foreignItemId = invoiceB.itemList.first().id!!
        val onA = svc.upload.upload("det2-a.pdf", "Beleg det2-a", pdf("det2-a"), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToItem(itemA, listOf(onA.id!!))
        em.flush(); em.clear()

        assertThrows(ApplicationValidationException::class.java) {
            svc.link.detachReceipts(
                invoiceA.id!!,
                ReceiptDetachSelection(itemReceiptIds = mapOf(itemA to setOf(onA.id!!), foreignItemId to setOf(999999L))),
            )
        }
        em.flush(); em.clear()

        assertEquals(1L, itemReceiptLinkCount(itemA, onA.id!!), "the valid part of the same rejected selection must not have been applied")
        assertEquals(1, svc.link.findReceiptsForItem(itemA).size)
    }

    @Test
    @Transactional
    fun `DET-3 stale version - a concurrent change surfaces OptimisticLockException, nothing detaches`() {
        val svc = receiptService()
        val invoice = buildInvoiceFixture("DET3", itemCount = 1)
        val itemId = invoice.itemList.first().id!!
        val onItem = svc.upload.upload("det3-item.pdf", "Beleg det3-item", pdf("det3-item"), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToItem(itemId, listOf(onItem.id!!))
        em.flush(); em.clear()
        val versionBeforeConcurrentChange = em.find(Invoice::class.java, invoice.id!!).version!!

        // Simulate a second session writing to the invoice in between — a real version bump, not
        // a hand-set field.
        val elsewhere = svc.upload.upload("det3-elsewhere.pdf", "Beleg det3-elsewhere", pdf("det3-elsewhere"), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToInvoice(invoice.id!!, listOf(elsewhere.id!!))
        em.flush(); em.clear()

        assertThrows(jakarta.persistence.OptimisticLockException::class.java) {
            svc.link.detachReceipts(
                invoice.id!!,
                ReceiptDetachSelection(itemReceiptIds = mapOf(itemId to setOf(onItem.id!!))),
                expectedVersion = versionBeforeConcurrentChange,
            )
        }
        em.flush(); em.clear()

        assertEquals(1, svc.link.findReceiptsForItem(itemId).size, "the stale-version call must not have detached anything")
        assertEquals(listOf("det3-item.pdf"), svc.link.findReceiptsForItem(itemId).map { it.fileName })
    }

    @Test
    @Transactional
    fun `DET-4 over-budget removal - an invoice already over the IR-2 budget can still shrink`() {
        val svc = receiptService()
        val invoice = buildInvoiceFixture("DET4", itemCount = 1)
        val item = invoice.itemList.first()
        val itemId = item.id!!
        // 25 links on one position alone already exceeds the 19-receipt invoice-wide budget —
        // attached directly via JPA (bypassing addReceiptsToItem's own budget guard), the way
        // legacy data from before the cap existed would sit in the database today.
        val legacyReceipts = (1..25).map { i ->
            Receipt().apply {
                fileName = "det4-legacy-$i.pdf"
                objectKey = "receipt-pool/det4-legacy-$i.pdf"
                sha256Checksum = "det4-legacy-checksum-$i"
                sizeBytes = 1024L
                description = "Legacy $i"
                documentDate = LocalDate.of(2026, 1, 1)
                uploadedAt = OffsetDateTime.now()
            }.also { em.persist(it) }
        }
        item.receipts.addAll(legacyReceipts)
        em.merge(invoice)
        em.flush(); em.clear()
        val toDetach = legacyReceipts.first().id!!

        assertDoesNotThrow { svc.link.removeReceiptsFromItem(itemId, listOf(toDetach)) }
        em.flush(); em.clear()

        assertEquals(24, svc.link.findReceiptsForItem(itemId).size, "the remaining, still over-budget set must be accepted")
        assertEquals(0L, itemReceiptLinkCount(itemId, toDetach))
    }

    @Test
    @Transactional
    fun `DET-5 no-op - detaching an id that is not attached leaves everything untouched`() {
        val svc = receiptService()
        val invoice = buildInvoiceFixture("DET5", itemCount = 1)
        val itemId = invoice.itemList.first().id!!
        val attached = svc.upload.upload("det5-attached.pdf", "Beleg det5-attached", pdf("det5-attached"), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToItem(itemId, listOf(attached.id!!))
        em.flush(); em.clear()

        assertDoesNotThrow {
            svc.link.detachReceipts(invoice.id!!, ReceiptDetachSelection(itemReceiptIds = mapOf(itemId to setOf(999999L))))
        }
        em.flush(); em.clear()

        assertEquals(listOf("det5-attached.pdf"), svc.link.findReceiptsForItem(itemId).map { it.fileName })
        assertEquals(1L, itemReceiptLinkCount(itemId, attached.id!!))
    }
}
