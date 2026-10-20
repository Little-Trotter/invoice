package de.littletrotter.e2e.invoice

import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceReverseChargeService
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.e2e.E2eBusinessDay
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.KositValidatorPort
import de.littletrotter.shared.Money
import de.littletrotter.xpdf.PdfLayoutInspector
import de.littletrotter.xpdf.testsupport.PdfExportServiceImplTestFactory
import de.littletrotter.xpdf.testsupport.TypstContainerRenderer
import de.littletrotter.xpdf.impl.XmlExportServiceImpl
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.edit

/** Persists optional item descriptions, maps them and exports real PDFs. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class OptionalItemDescriptionE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var reverseChargeService: InvoiceReverseChargeService

    @Inject
    lateinit var receiptRepository: ReceiptRepository

    private class InMemoryDocumentStore : DocumentStore {
        val objects = mutableMapOf<String, ByteArray>()
        override fun put(objectKey: String, data: ByteArray, contentType: String) { objects[objectKey] = data }
        override fun get(objectKey: String): ByteArray = objects[objectKey] ?: error("not stored: $objectKey")
        override fun exists(objectKey: String): Boolean = objects.containsKey(objectKey)
        override fun enableImmutability(objectKey: String) {}
        override fun delete(objectKey: String) { objects.remove(objectKey) }
    }

    private fun mapToDto(invoiceId: Long): InvoiceDto {
        val invoice = em.find(Invoice::class.java, invoiceId)!!
        return InvoiceDtoMapper(receiptRepository = receiptRepository).mapToDto(invoice)
    }

    private fun exportPdf(dto: InvoiceDto, jobId: String): ByteArray {
        val store = InMemoryDocumentStore()
        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = XmlExportServiceImpl(null),
            reportRenderer = TypstContainerRenderer(),
            documentStore = store,
            kositValidator = object : KositValidatorPort {
                override fun validate(xml: String, timeoutMs: Long) = KositValidationResult(status = KositOutcome.VALID)
            },
        )
        val objectKey = service.performExport(dto, jobId).objectKey
        return store.get(objectKey)
    }

    private fun createInvoice(tag: String): Long {
        val sender = aSenderProfile {
            name = "$tag Sender GmbH"
            invoicePrefix = "$tag-"
            registerCourt = "AG Musterstadt"
            email = Email.ofNullable("billing@$tag.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE111111111")
            iban = Iban.ofNullable("DE89370400440532013000")
        }
        sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
        val recipient = aRecipient {
            customerNumber = CustomerNumber("$tag-100")
            name = "$tag Dupont Conseil S.A.R.L."
            email = Email.ofNullable("ap@$tag.example")
            vatId = VatId("FRXX123456789")
            customerType = CustomerType.BUSINESS
            vatIdVerificationStatus = VatIdVerificationStatus.VERIFIED
        }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Rue Exemple 1", "75001", "Paris", Country.FR, true))
        em.persist(sender)
        em.persist(recipient)

        // Placeholder pre-toggle rate, same idiom EInvoiceGenerationE2EIT's RC scenario uses —
        // setReverseChargeDecision below replaces it with the real seeded EU_B2B_RC_SERVICE rule.
        val placeholderRate = TaxRate().edit {
            code = "${tag}_DE_STD_19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isActive = true; isSelectable = true
        }
        em.persist(placeholderRate)

        val invoice = anInvoice().edit {
            senderProfile = sender
            this.recipient = recipient
            invoiceNumber = InvoiceNumber("$tag-2026-0001")
            invoiceDate = LocalDate.of(2026, 2, 10)
            dueDate = LocalDate.of(2026, 3, 12)
            // Same CORE.SERVICE_PERIOD_XOR guard EInvoiceGenerationE2EIT documents.
            deliveryDate = E2eBusinessDay.today()
        }
        listOf("Description & <parts>\nLastDescriptionLine", null, " \n\t").forEachIndexed { index, detail ->
            invoice.addItem(
                anInvoiceItem().edit {
                    positionId = (index + 1).toString()
                    category = InvoiceItemCategory.PRODUCT
                    description = "ArticleName${index + 1}"
                    itemDescription = detail
                    quantity = BigDecimal.ONE
                    unit = InvoiceItemUnit.PIECE
                    unitPriceNet = Money.of("100.00")
                    sortOrder = index
                }.also { it.applyTaxRule(placeholderRate) },
            )
        }
        invoice.recalculateTotals()
        em.persist(invoice)
        em.flush()
        val invoiceId = invoice.id!!

        reverseChargeService.setReverseChargeDecision(invoiceId, invoice.version, true)
        em.flush()
        return invoiceId
    }

    private fun allTextBlocks(pdfBytes: ByteArray): List<PdfLayoutInspector.TextBlock> =
        PdfLayoutInspector().inspect(pdfBytes).pages.flatMap { it.textBlocks }

    @Test
    fun `persisted optional descriptions render in German and English`() {
        val invoiceId = QuarkusTransaction.requiringNew().call { createInvoice("OID1") }
        val dto = QuarkusTransaction.requiringNew().call { mapToDto(invoiceId) }
        org.junit.jupiter.api.Assertions.assertEquals("Description & <parts>\nLastDescriptionLine", dto.items.first().itemDescription)
        for (language in listOf("de", "en")) {
            val document = dto.copy(meta = dto.meta.copy(documentLanguage = language))
            val blocks = allTextBlocks(exportPdf(document, "OID1-$language"))
            assertTrue(blocks.any { it.text.contains("Description & <parts>") })
            val detail = blocks.single { it.text.contains("LastDescriptionLine") }
            val next = blocks.single { it.text.contains("ArticleName2") }
            assertTrue(detail.y + detail.height <= next.y, "Description overlaps the next item")
            assertTrue(blocks.any { it.text.contains("ArticleName3") })
            assertTrue(blocks.none { it.text == "null" })
        }
    }
}
