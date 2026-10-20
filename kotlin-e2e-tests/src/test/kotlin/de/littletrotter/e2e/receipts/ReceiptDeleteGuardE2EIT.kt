package de.littletrotter.e2e.receipts

import de.littletrotter.application.invoice.port.outbound.InvoiceLookupRepository
import de.littletrotter.application.invoice.ReceiptAssignedToInvoiceException
import de.littletrotter.application.invoice.ReceiptBudgetService
import de.littletrotter.application.invoice.ReceiptLinkService
import de.littletrotter.application.invoice.ReceiptPoolService
import de.littletrotter.application.invoice.ReceiptUploadService
import de.littletrotter.domain.exception.InvoiceImmutableException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.country.Country
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.edit

/**
 * receipt-delete-guard (PO 2026-09-04) — a receipt that is assigned to an invoice must not be
 * deletable, and releasing the assignment is possible only while the invoice is in DRAFT or
 * IN_REVIEW. Pinned here through the real stack (Postgres + S3), because the guard has to hold on
 * the persisted link rows and the stored object, not just on a mocked repository:
 *
 *  - RDG-1 happy path — an unassigned receipt is deletable, row and S3 object both go
 *  - RDG-2 border — an invoice-level assignment refuses the delete; the object survives untouched
 *  - RDG-3 border — a position-level assignment refuses it just the same, and releasing it on the
 *    still-open invoice is what makes the receipt deletable again
 *  - RDG-4 border — IN_REVIEW still allows releasing; ISSUED no longer does, so the receipt stays a
 *    current proof and can never be deleted through the pool
 *
 * Fixture shape copied from [ReceiptBudgetE2EIT] (same module idiom: plain JPA fixtures, a
 * hand-built [ReceiptLinkService] over the injected repositories).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(S3TestResource::class)
class ReceiptDeleteGuardE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var receiptRepository: ReceiptRepository

    @Inject
    lateinit var invoiceRepository: InvoiceRepository

    @Inject
    lateinit var invoiceLookup: InvoiceLookupRepository

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
            upload = receiptUploadService(receiptRepository, documentStore, de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z"))),
            link = ReceiptLinkService(receiptRepository, invoiceRepository, invoiceLookup, pool, budget),
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
                cs.showText("receipt-delete-guard fixture $marker")
                cs.endText()
            }
            val os = ByteArrayOutputStream()
            doc.save(os)
            return os.toByteArray()
        }
    }

    /** A one-item DRAFT invoice, persisted via plain JPA. */
    private fun buildInvoiceFixture(tag: String): Invoice {
        val sender = aSenderProfile {
            name = "Löschschutz E2E GmbH"; invoicePrefix = "$tag-"; registerCourt = "AG Musterstadt"
            vatId = VatId("DE111111111")
        }
        sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
        val recipient = aRecipient { customerNumber = CustomerNumber("$tag-100"); name = "Kunde AG"; vatId = VatId("DE222222222") }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
        em.persist(sender); em.persist(recipient)

        val taxRate = TaxRate().edit {
            code = "${tag}_DE_STD_19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isActive = true; isSelectable = true
        }
        em.persist(taxRate)

        val invoice = anInvoice(
            invoiceNumber = InvoiceNumber("$tag-2026-0001"),
            senderProfile = sender,
            recipient = recipient,
            invoiceDate = LocalDate.of(2026, 8, 1),
            paymentTermsDays = 14,
            items = listOf(
                anInvoiceItem(
                    positionId = "1",
                    description = "Beratung",
                    quantity = BigDecimal.ONE,
                    unitPriceNet = Money(BigDecimal("100.00")),
                    sortOrder = 1,
                ).also { it.applyTaxRule(taxRate) },
            ),
        )
        invoice.recalculateTotals()
        em.persist(invoice); em.flush()
        return invoice
    }

    @Test
    @Transactional
    fun `RDG-1 an unassigned receipt is deletable - row and stored object both go`() {
        val svc = receiptService()
        val receipt = svc.upload.upload("rdg1.pdf", "Beleg rdg1", pdf("rdg1"), LocalDate.of(2026, 3, 14))
        val objectKey = receipt.objectKey
        assertTrue(documentStore.exists(objectKey))

        val check = svc.pool.checkDeleteEligibility(receipt.id!!)
        assertTrue(check.deletable)
        assertEquals(0, check.usageCount)

        svc.pool.delete(receipt.id!!)

        assertEquals(null, receiptRepository.findById(receipt.id!!), "the row must be gone")
        assertFalse(documentStore.exists(objectKey), "the stored object must be gone with it")
    }

    @Test
    @Transactional
    fun `RDG-2 an invoice-level assignment refuses the delete and leaves the object untouched`() {
        val svc = receiptService()
        val invoice = buildInvoiceFixture("RDG2")
        val receipt = svc.upload.upload("rdg2.pdf", "Beleg rdg2", pdf("rdg2"), LocalDate.of(2026, 3, 14))
        val objectKey = receipt.objectKey
        svc.link.addReceiptsToInvoice(invoice.id!!, listOf(receipt.id!!))
        em.flush(); em.clear()

        val check = svc.pool.checkDeleteEligibility(receipt.id!!)
        assertFalse(check.deletable)
        assertEquals(1, check.usageCount)
        assertEquals(listOf(invoice.invoiceNumber.value), check.assignedInvoiceNumbers)

        val ex = assertThrows(ReceiptAssignedToInvoiceException::class.java) { svc.pool.delete(receipt.id!!) }
        assertTrue(ex.message!!.contains(invoice.invoiceNumber.value))

        assertNotNull(receiptRepository.findById(receipt.id!!), "the refused delete changes nothing")
        assertTrue(documentStore.exists(objectKey), "the stored object survives a refused delete")
        assertEquals(1, svc.link.findReceiptsForInvoice(invoice.id!!).size)
    }

    @Test
    @Transactional
    fun `RDG-3 a position-level assignment refuses it too - releasing it on the draft restores the delete`() {
        val svc = receiptService()
        val invoice = buildInvoiceFixture("RDG3")
        val itemId = invoice.itemList.first().id!!
        val receipt = svc.upload.upload("rdg3.pdf", "Beleg rdg3", pdf("rdg3"), LocalDate.of(2026, 3, 14))
        val objectKey = receipt.objectKey
        svc.link.addReceiptsToItem(itemId, listOf(receipt.id!!))
        em.flush(); em.clear()

        assertFalse(svc.pool.checkDeleteEligibility(receipt.id!!).deletable)
        assertThrows(ReceiptAssignedToInvoiceException::class.java) { svc.pool.delete(receipt.id!!) }

        // Releasing the assignment is only possible on the invoice — and only while it is open.
        svc.link.removeReceiptsFromItem(itemId, listOf(receipt.id!!))
        em.flush(); em.clear()

        assertTrue(svc.pool.checkDeleteEligibility(receipt.id!!).deletable)
        svc.pool.delete(receipt.id!!)
        assertEquals(null, receiptRepository.findById(receipt.id!!))
        assertFalse(documentStore.exists(objectKey))
    }

    @Test
    @Transactional
    fun `RDG-4 IN_REVIEW still releases, ISSUED no longer does - the receipt stays a proof for good`() {
        val svc = receiptService()
        val invoice = buildInvoiceFixture("RDG4")
        val itemId = invoice.itemList.first().id!!
        val inReview = svc.upload.upload("rdg4a.pdf", "Beleg rdg4a", pdf("rdg4a"), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToItem(itemId, listOf(inReview.id!!))
        em.flush()

        // IN_REVIEW is still writable: releasing works, and the receipt becomes deletable.
        invoice.transitionTo(InvoiceStatus.IN_REVIEW)
        em.merge(invoice); em.flush(); em.clear()
        svc.link.removeReceiptsFromItem(itemId, listOf(inReview.id!!))
        em.flush(); em.clear()
        svc.pool.delete(inReview.id!!)
        assertEquals(null, receiptRepository.findById(inReview.id!!))

        // A second receipt, this time frozen with the invoice.
        val frozen = svc.upload.upload("rdg4b.pdf", "Beleg rdg4b", pdf("rdg4b"), LocalDate.of(2026, 3, 14))
        val frozenKey = frozen.objectKey
        svc.link.addReceiptsToItem(itemId, listOf(frozen.id!!))
        em.flush()
        val reloaded = em.find(Invoice::class.java, invoice.id!!)
        reloaded.transitionTo(InvoiceStatus.ISSUED)
        em.merge(reloaded); em.flush(); em.clear()

        assertThrows(InvoiceImmutableException::class.java) { svc.link.removeReceiptsFromItem(itemId, listOf(frozen.id!!)) }
        assertThrows(ReceiptAssignedToInvoiceException::class.java) { svc.pool.delete(frozen.id!!) }
        assertNotNull(receiptRepository.findById(frozen.id!!))
        assertTrue(documentStore.exists(frozenKey))
        assertEquals(listOf("rdg4b.pdf"), svc.link.findReceiptsForItem(itemId).map { it.fileName })
    }
}
