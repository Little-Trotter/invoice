package de.littletrotter.e2e.invoice

import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.PartyRole
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SellerVatRegistrationCommand
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.ForeignTaxNumber
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.e2e.E2eBusinessDay
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import de.littletrotter.e2e.einvoice.EInvoiceXml
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.KositValidatorPort
import de.littletrotter.shared.Money
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.xpdf.impl.XmlExportServiceImpl
import de.littletrotter.xpdf.testsupport.PdfExportServiceImplTestFactory
import de.littletrotter.xpdf.testsupport.TypstContainerRenderer
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The seller's tax number of a foreign VAT registration on the produced documents: the PDF header
 * prints it directly below that country's VAT ID, the e-invoice XML never carries it (BT-32 stays
 * the German tax number), and the value printed is the one frozen when the invoice was issued.
 * Synthetic data only; PostgreSQL and the Typst renderer are provisioned by the test resources.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class ForeignTaxNumberInvoiceE2EIT {

    @Inject lateinit var em: EntityManager
    @Inject lateinit var lifecycleService: InvoiceLifecycleService
    @Inject lateinit var itemMutationService: InvoiceItemMutationService
    @Inject lateinit var taxRateQueries: TaxRateQueryPort
    @Inject lateinit var receiptRepository: ReceiptRepository

    private val createdInvoiceIds = mutableListOf<Long>()
    private val createdRecipientIds = mutableListOf<Long>()
    private val createdSenderIds = mutableListOf<Long>()

    @AfterEach
    fun cleanup() {
        QuarkusTransaction.requiringNew().run {
            createdInvoiceIds.forEach { em.find(Invoice::class.java, it)?.let(em::remove) }
            createdRecipientIds.forEach { em.find(Recipient::class.java, it)?.let(em::remove) }
            createdSenderIds.forEach { em.find(SenderProfile::class.java, it)?.let(em::remove) }
            createdInvoiceIds.clear(); createdRecipientIds.clear(); createdSenderIds.clear()
        }
    }

    private class InMemoryDocumentStore : DocumentStore {
        val objects = mutableMapOf<String, ByteArray>()
        override fun put(objectKey: String, data: ByteArray, contentType: String) { objects[objectKey] = data }
        override fun get(objectKey: String): ByteArray = objects[objectKey] ?: error("not stored: $objectKey")
        override fun exists(objectKey: String): Boolean = objects.containsKey(objectKey)
        override fun enableImmutability(objectKey: String) {}
        override fun delete(objectKey: String) { objects.remove(objectKey) }
    }

    private fun dto(invoiceId: Long): InvoiceDto = QuarkusTransaction.requiringNew().call {
        InvoiceDtoMapper(receiptRepository = receiptRepository).mapToDto(em.find(Invoice::class.java, invoiceId)!!)
    }

    /** The PDF text, one entry per line, whitespace inside a line collapsed to single blanks. */
    private fun pdfLines(dto: InvoiceDto, jobId: String): List<String> {
        val store = InMemoryDocumentStore()
        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = XmlExportServiceImpl(null),
            reportRenderer = TypstContainerRenderer(),
            documentStore = store,
            kositValidator = object : KositValidatorPort {
                override fun validate(xml: String, timeoutMs: Long) = KositValidationResult(status = KositOutcome.VALID)
            },
        )
        val bytes = store.get(service.performExport(dto, jobId).objectKey)
        val text = Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }
        return text.lines().map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }
    }

    private fun luTaxRateId(): Long =
        taxRateQueries.findAllTaxRates().find { it.code == "LU_STANDARD_17" }?.id
            ?: error("expected seeded TaxRate 'LU_STANDARD_17'")

    /** Issues a one-position invoice billed under the seller's LU registration; returns its id. */
    private fun issuedLuInvoice(
        tag: String,
        luTaxNumber: String?,
        language: String = "de",
        recipientCountry: Country = Country.LU,
    ): Pair<Long, Long> {
        val (senderId, recipientId) = QuarkusTransaction.requiringNew().call {
            val sender = aSenderProfile {
                phone = "+49 30 1234567"
                name = "$tag Sender GmbH"
                invoicePrefix = "$tag-"
                registerCourt = "AG Musterstadt"
                email = Email.ofNullable("billing@$tag.example")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE111111111")
                iban = Iban.ofNullable("DE89370400440532013000")
            }
            sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
            sender.updateVatRegistrations(
                listOf(SellerVatRegistrationCommand(Country.LU, VatId("LU12345678"), ForeignTaxNumber.ofNullable(luTaxNumber))),
                E2eBusinessDay.today(),
            )
            val recipient = aRecipient {
                customerNumber = CustomerNumber("$tag-100")
                name = "$tag Michels S.A."
                email = Email.ofNullable("ap@$tag.example")
                vatId = VatId("LU21416127")
                customerType = CustomerType.BUSINESS
                routingId = "PO-$tag-1"
            }
            recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Rue Exemple 5", if (recipientCountry == Country.DE) "10115" else "1000", "Stadt", recipientCountry, true))
            em.persist(sender); em.persist(recipient); em.flush()
            sender.id!! to recipient.id!!
        }
        createdSenderIds += senderId; createdRecipientIds += recipientId

        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(senderId, recipientId).id!! }
        createdInvoiceIds += invoiceId
        QuarkusTransaction.requiringNew().run {
            val invoice = em.find(Invoice::class.java, invoiceId)!!
            if (recipientCountry == Country.LU) {
                assertEquals(Country.LU, invoice.sellerVatRegistrationCountry)
            }
            invoice.edit {
                documentLanguage = language
                deliveryDate = E2eBusinessDay.today()
                sellerVatRegistrationCountry = Country.LU
            }
        }
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "$tag Beratung",
                    quantity = BigDecimal("2"),
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"),
                    taxRateId = luTaxRateId(),
                ),
            )
        }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.ISSUED) }
        return invoiceId to senderId
    }

    private fun indexOfLine(lines: List<String>, vararg parts: String): Int =
        lines.indexOfFirst { line -> parts.all { line.contains(it) } }

    @Test
    fun `a recognised LU number prints as Matricule below the LU VAT ID, the German rows stay`() {
        val (invoiceId, _) = issuedLuInvoice("FTN1", "1990 0101 12345")

        val lines = pdfLines(dto(invoiceId), "ftn1")

        val vat = indexOfLine(lines, "TVA", "LU12345678")
        val tax = indexOfLine(lines, "Matricule", "1990010112345")
        assertTrue(vat >= 0, "LU VAT ID row missing: $lines")
        assertTrue(tax >= 0, "Matricule row missing: $lines")
        assertTrue(tax > vat && indexOfLine(lines, "DE111111111") > tax, "the number sits right below the LU VAT ID: $lines")
        assertTrue(lines.any { it.contains("12/345/67890") }, "the German tax number row is unchanged: $lines")
        assertTrue(lines.any { it.contains("DE111111111") }, "the German VAT ID row is unchanged: $lines")
    }

    @Test
    fun `an unrecognised format prints as TIN (LU) in a German and an English document`() {
        val (germanId, _) = issuedLuInvoice("FTN2", "12345")
        val (englishId, _) = issuedLuInvoice("FTN3", "12345", language = "en")

        assertTrue(pdfLines(dto(germanId), "ftn2").any { it.contains("TIN (LU)") && it.contains("12345") })
        assertTrue(pdfLines(dto(englishId), "ftn3").any { it.contains("TIN (LU)") && it.contains("12345") })
    }

    @Test
    fun `without a tax number there is no extra row`() {
        val (invoiceId, _) = issuedLuInvoice("FTN4", null)

        val lines = pdfLines(dto(invoiceId), "ftn4")

        assertTrue(lines.any { it.contains("LU12345678") })
        assertFalse(lines.any { it.contains("TIN (") || it.contains("Matricule") })
    }

    @Test
    fun `a tax number equal to the VAT ID without prefix adds no row`() {
        val (invoiceId, _) = issuedLuInvoice("FTN5", "12345678")

        val lines = pdfLines(dto(invoiceId), "ftn5")

        assertTrue(lines.any { it.contains("LU12345678") })
        assertFalse(lines.any { it.contains("TIN (") || it.contains("Matricule") }, "duplicate must be suppressed: $lines")
    }

    @Test
    fun `the XML keeps the German tax number as BT-32 and never carries the foreign one`() {
        val (invoiceId, _) = issuedLuInvoice("FTN6", "1990010112345")

        val xml = String(XmlExportServiceImpl(null).generate(dto(invoiceId)), Charsets.UTF_8)

        EInvoiceXml.assertText(
            xml, "//ram:SellerTradeParty/ram:SpecifiedTaxRegistration/ram:ID[@schemeID='FC']", "12/345/67890",
            "BT-32 stays the German tax number",
        )
        assertFalse(xml.contains("1990010112345"), "the foreign tax number never reaches the XML")
    }

    @Test
    fun `a later profile change does not alter the issued invoice`() {
        val (invoiceId, senderId) = issuedLuInvoice("FTN7", "1990010112345")

        QuarkusTransaction.requiringNew().run {
            val sender = em.find(SenderProfile::class.java, senderId)!!
            sender.updateVatRegistrations(
                listOf(SellerVatRegistrationCommand(Country.LU, VatId("LU12345678"), ForeignTaxNumber.of("1999999999999"))),
                E2eBusinessDay.today(),
            )
            em.flush()
        }

        val lines = pdfLines(dto(invoiceId), "ftn7")

        assertTrue(lines.any { it.contains("1990010112345") }, "the frozen number still prints: $lines")
        assertFalse(lines.any { it.contains("1999999999999") }, "the edited number must not leak: $lines")
    }

    @Test
    fun `a LU registration with a German recipient is not effective in PDF, XML and snapshot`() {
        val (invoiceId, _) = issuedLuInvoice("FTN8", "1990010112345", recipientCountry = Country.DE)

        val dto = dto(invoiceId)
        val lines = pdfLines(dto, "ftn8")
        val xml = String(XmlExportServiceImpl(null).generate(dto), Charsets.UTF_8)

        assertFalse(lines.any { it.contains("LU12345678") || it.contains("Matricule") || it.contains("1990010112345") }, "no LU rows: $lines")
        assertTrue(lines.any { it.contains("DE111111111") }, "the German VAT ID row stays: $lines")
        EInvoiceXml.assertText(
            xml, "//ram:SellerTradeParty/ram:SpecifiedTaxRegistration/ram:ID[@schemeID='VA']", "DE111111111",
            "BT-31 stays the German VAT ID",
        )
        assertFalse(xml.contains("LU12345678"), "the LU VAT ID never reaches the XML")
        QuarkusTransaction.requiringNew().run {
            val snapshot = em.find(Invoice::class.java, invoiceId)!!.partySnapshotList.first { it.partyRole == PartyRole.SELLER }
            assertEquals(null, snapshot.registrationTaxNumber, "no snapshot of the foreign tax number")
        }
    }

    @Test
    fun `a LU registration with a LU recipient is effective in PDF and XML BT-31`() {
        val (invoiceId, _) = issuedLuInvoice("FTN9", "1990010112345")

        val dto = dto(invoiceId)
        val xml = String(XmlExportServiceImpl(null).generate(dto), Charsets.UTF_8)

        assertTrue(pdfLines(dto, "ftn9").any { it.contains("Matricule") && it.contains("1990010112345") })
        EInvoiceXml.assertText(
            xml, "//ram:SellerTradeParty/ram:SpecifiedTaxRegistration/ram:ID[@schemeID='VA']", "LU12345678",
            "BT-31 is the LU VAT ID",
        )
    }

    /**
     * Rows loaded straight into the database carry no seller snapshot. A missing snapshot is not a
     * frozen "none": the header reads the seller's current registration, so the Matricule still prints.
     */
    @Test
    fun `an issued invoice without a seller snapshot reads the live number for the Matricule row`() {
        val (invoiceId, _) = issuedLuInvoice("FTN10", "1990010112345")
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery("DELETE FROM invoice_party_snapshot WHERE invoice_id = :id AND party_role = 0")
                .setParameter("id", invoiceId)
                .executeUpdate()
        }

        val lines = pdfLines(dto(invoiceId), "ftn10")

        val vat = indexOfLine(lines, "TVA", "LU12345678")
        val tax = indexOfLine(lines, "Matricule", "1990010112345")
        assertTrue(vat >= 0, "LU VAT ID row missing: $lines")
        assertTrue(tax > vat && indexOfLine(lines, "DE111111111") > tax, "the live number sits right below the LU VAT ID: $lines")
    }
}
