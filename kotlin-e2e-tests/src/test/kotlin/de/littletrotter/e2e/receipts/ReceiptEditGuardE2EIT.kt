package de.littletrotter.e2e.receipts

import de.littletrotter.application.invoice.ReceiptBudgetService
import de.littletrotter.application.invoice.ReceiptLinkService
import de.littletrotter.application.invoice.ReceiptPoolService
import de.littletrotter.application.invoice.ReceiptUploadService
import de.littletrotter.domain.exception.ReceiptLockedByIssuedInvoiceException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.VatId

/**
 * fix-receipt-editable-after-invoice-issued — a receipt's description/documentDate must not
 * change once any invoice it is attached to is issued (SENT/CANCELLED). Fixture idiom copied
 * from [ReceiptDeleteGuardE2EIT] (same module).
 *
 *  - REG-1 happy path — edit succeeds while the only linked invoice is still DRAFT
 *  - REG-2 border — edit blocked once that item-level link's invoice is SENT
 *  - REG-3 border — an invoice-level link (not item-level) locks it just the same
 *  - REG-4 border — locked when only one of two linked invoices is issued
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(S3TestResource::class)
class ReceiptEditGuardE2EIT {

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

    private fun pdf(marker: String): ByteArray {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            PDPageContentStream(doc, doc.getPage(0)).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                cs.newLineAtOffset(50f, 700f)
                cs.showText("receipt-edit-guard fixture $marker")
                cs.endText()
            }
            val os = ByteArrayOutputStream()
            doc.save(os)
            return os.toByteArray()
        }
    }

    private fun buildInvoiceFixture(tag: String): Invoice {
        val sender = SenderProfile().apply {
            name = "Bearbeitungssperre E2E GmbH"; invoicePrefix = "$tag-"; registerCourt = "AG Musterstadt"
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

        val invoice = Invoice().apply {
            invoiceNumber = InvoiceNumber("$tag-2026-0001")
            senderProfile = sender
            this.recipient = recipient
            invoiceDate = LocalDate.of(2026, 8, 1)
            paymentTermsDays = 14
            addItem(
                InvoiceItem().apply {
                    positionId = "1"; description = "Beratung"
                    quantity = BigDecimal.ONE; unitPriceNet = Money(BigDecimal("100.00"))
                    sortOrder = 1
                }.also { it.applyTaxRule(taxRate) },
            )
        }
        invoice.recalculateTotals()
        em.persist(invoice); em.flush()
        return invoice
    }

    private fun issue(invoice: Invoice) {
        val reloaded = em.find(Invoice::class.java, invoice.id!!)
        reloaded.transitionTo(InvoiceStatus.IN_REVIEW)
        reloaded.transitionTo(InvoiceStatus.SENT)
        em.merge(reloaded); em.flush(); em.clear()
    }

    @Test
    @Transactional
    fun `REG-1 edit succeeds while the only linked invoice is still DRAFT`() {
        val svc = receiptService()
        val invoice = buildInvoiceFixture("REG1")
        val itemId = invoice.itemList.first().id!!
        val receipt = svc.upload.upload("reg1.pdf", "old", pdf("reg1"), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToItem(itemId, listOf(receipt.id!!))
        em.flush(); em.clear()

        svc.pool.updateDetails(receipt.id!!, "new", LocalDate.of(2026, 9, 1))

        assertEquals("new", receiptRepository.findById(receipt.id!!)!!.description)
    }

    @Test
    @Transactional
    fun `REG-2 edit is blocked once the item-level link's invoice is SENT`() {
        val svc = receiptService()
        val invoice = buildInvoiceFixture("REG2")
        val itemId = invoice.itemList.first().id!!
        val receipt = svc.upload.upload("reg2.pdf", "old", pdf("reg2"), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToItem(itemId, listOf(receipt.id!!))
        em.flush()
        issue(invoice)

        val ex = assertThrows(ReceiptLockedByIssuedInvoiceException::class.java) {
            svc.pool.updateDetails(receipt.id!!, "new", LocalDate.of(2026, 9, 1))
        }
        assertTrue(ex.message!!.contains(invoice.invoiceNumber.value))
        assertEquals("old", receiptRepository.findById(receipt.id!!)!!.description)
    }

    @Test
    @Transactional
    fun `REG-3 an invoice-level link locks the receipt just the same`() {
        val svc = receiptService()
        val invoice = buildInvoiceFixture("REG3")
        val receipt = svc.upload.upload("reg3.pdf", "old", pdf("reg3"), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToInvoice(invoice.id!!, listOf(receipt.id!!))
        em.flush()
        issue(invoice)

        assertThrows(ReceiptLockedByIssuedInvoiceException::class.java) {
            svc.pool.updateDetails(receipt.id!!, "new", LocalDate.of(2026, 9, 1))
        }
    }

    @Test
    @Transactional
    fun `REG-4 locked when only one of two linked invoices is issued`() {
        val svc = receiptService()
        val draftInvoice = buildInvoiceFixture("REG4A")
        val sentInvoice = buildInvoiceFixture("REG4B")
        val receipt = svc.upload.upload("reg4.pdf", "old", pdf("reg4"), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToItem(draftInvoice.itemList.first().id!!, listOf(receipt.id!!))
        svc.link.addReceiptsToItem(sentInvoice.itemList.first().id!!, listOf(receipt.id!!))
        em.flush()
        issue(sentInvoice)

        assertThrows(ReceiptLockedByIssuedInvoiceException::class.java) {
            svc.pool.updateDetails(receipt.id!!, "new", LocalDate.of(2026, 9, 1))
        }
    }
}
