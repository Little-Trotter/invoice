package de.littletrotter.e2e.invoice

import de.littletrotter.application.invoice.port.inbound.EditInvoiceHeaderUseCase
import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.invoice.UpdateInvoiceHeaderCommand
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import de.littletrotter.shared.Currency
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.KositValidatorPort
import de.littletrotter.shared.Money
import de.littletrotter.shared.documents.FreeTextMarkup
import de.littletrotter.xpdf.testsupport.TypstContainerRenderer
import de.littletrotter.xpdf.testsupport.PdfExportServiceImplTestFactory
import de.littletrotter.xpdf.impl.XmlExportServiceImpl
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * The free text the editing bar produces survives the whole path: saved through the header
 * facade, read back unchanged (every marker and list kind, and the full MAX_LENGTH characters), and
 * printed with its formatting applied — the PDF text carries the words and none of the markers.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class InvoiceFreeTextMarkupE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var editInvoiceHeader: EditInvoiceHeaderUseCase

    @Inject
    lateinit var createInvoiceUseCase: CreateInvoiceUseCase

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

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

    private data class Fixture(val invoiceId: Long, val senderId: Long, val recipientId: Long)

    private fun buildInvoice(tag: String): Fixture = QuarkusTransaction.requiringNew().call {
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
            name = "$tag Empfänger AG"
            email = Email.ofNullable("ap@$tag.example")
            vatId = VatId("DE222222222")
        }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
        em.persist(sender)
        em.persist(recipient)
        val taxRate = TaxRate().edit {
            code = "${tag}_STD19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isSelectable = true; isActive = true
        }
        em.persist(taxRate)
        em.flush()
        val invoiceId = createInvoiceUseCase.createInvoiceWithRecipient(sender.id!!, recipient.id!!).id
        itemMutationService.addItem(
            invoiceId,
            InvoiceItemCommand(
                category = InvoiceItemCategory.SERVICE,
                description = "$tag Beratung",
                quantity = BigDecimal("2"),
                unit = InvoiceItemUnit.HOUR,
                unitPriceNet = Money.of("100.00"),
                taxRateId = taxRate.id!!,
            ),
        )
        Fixture(invoiceId, sender.id!!, recipient.id!!)
    }

    private fun saveFreeText(fixture: Fixture, freeText: String): String? = QuarkusTransaction.requiringNew().call {
        editInvoiceHeader.updateInvoiceHeader(
            UpdateInvoiceHeaderCommand(
                invoiceId = fixture.invoiceId,
                expectedVersion = null,
                invoiceDate = null,
                servicePeriodFrom = null,
                servicePeriodTo = null,
                dueDate = null,
                freeText = freeText,
                buyerReference = null,
                orderReference = null,
                orderReferenceDate = null,
                currency = Currency.EUR,
                recipientId = fixture.recipientId,
                senderProfileId = fixture.senderId,
                paymentTermId = null,
                sellerVatRegistrationCountry = null,
                invoiceFormat = null,
            ),
        ).freeText
    }

    private fun mapToDto(invoiceId: Long): InvoiceDto {
        val invoice = em.find(Invoice::class.java, invoiceId)!!
        return InvoiceDtoMapper(receiptRepository = receiptRepository).mapToDto(invoice)
    }

    private fun exportPdfText(dto: InvoiceDto, jobId: String): String {
        val store = InMemoryDocumentStore()
        val key = PdfExportServiceImplTestFactory.create(
            xmlExportService = XmlExportServiceImpl(null),
            reportRenderer = TypstContainerRenderer(),
            documentStore = store,
            kositValidator = object : KositValidatorPort {
                override fun validate(xml: String, timeoutMs: Long) = KositValidationResult(status = KositOutcome.VALID)
            },
        ).performExport(dto, jobId).objectKey
        val pdf = store.get(key)
        return Loader.loadPDF(pdf).use { PDFTextStripper().getText(it) }.replace(Regex("\\s+"), "")
    }

    @Test
    fun `free text with every marker and list kind is saved unchanged and printed without its markers`() {
        val fixture = buildInvoice("FT4A")
        val freeText = "**Wichtig** *kursiv* ++unterstrichen++ ~~durchgestrichen~~\n" +
            "- Punkt eins\n    - Unterpunkt zwei\n1. Nummer eins\n    2. Nummer zwei"

        val saved = saveFreeText(fixture, freeText)

        assertEquals(freeText, saved, "the header facade returns the free text exactly as typed")
        val text = exportPdfText(QuarkusTransaction.requiringNew().call { mapToDto(fixture.invoiceId) }, "FT4A-pdf")
        listOf("Wichtig", "kursiv", "unterstrichen", "durchgestrichen", "Punkteins", "Unterpunktzwei", "Nummereins", "Nummerzwei")
            .forEach { assertTrue(text.contains(it), "the printed text carries \"$it\", got: $text") }
        listOf("**", "++", "~~").forEach { assertFalse(text.contains(it), "no raw marker $it in the print, got: $text") }
    }

    @Test
    fun `free text of the full MAX_LENGTH characters is saved and read back unchanged`() {
        val fixture = buildInvoice("FT4B")
        val freeText = ("Zeile **fett** und ++unterstrichen++\n").repeat(FreeTextMarkup.MAX_LENGTH / 37).padEnd(FreeTextMarkup.MAX_LENGTH, 'x')
        assertEquals(FreeTextMarkup.MAX_LENGTH, freeText.length)

        val saved = saveFreeText(fixture, freeText)

        assertEquals(freeText, saved)
    }
}
