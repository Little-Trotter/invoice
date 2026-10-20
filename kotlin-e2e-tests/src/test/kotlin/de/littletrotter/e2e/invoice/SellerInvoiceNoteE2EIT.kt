package de.littletrotter.e2e.invoice

import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.initialInvoiceNoteSnapshot
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.invoiceNoteFor
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.Language
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
import de.littletrotter.xpdf.testsupport.PdfExportServiceImplTestFactory
import de.littletrotter.xpdf.testsupport.TypstContainerRenderer
import de.littletrotter.xpdf.impl.XmlExportServiceImpl
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.edit

/**
 * The seller's own invoice note ("HINWEIS" box) on the produced PDF, end to end against a
 * real Postgres and the real Typst renderer. `testmatrix.md`'s own three-column shape:
 * German document / English document with an English note / English document with no English note
 * (falls back to German — the MVP's normal case, not an edge case) — plus the golden invariant that
 * a German document never moves, English note or not.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class SellerInvoiceNoteE2EIT {

    @Inject
    lateinit var em: EntityManager

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

    /** Whitespace-collapsed text of every page — same idiom as
     *  [InvoiceLifecycleWatermarkE2EIT.pageTexts]. */
    private fun pageTexts(pdfBytes: ByteArray): List<String> = Loader.loadPDF(pdfBytes).use { doc ->
        (0 until doc.numberOfPages).map { index ->
            val stripper = PDFTextStripper().apply { startPage = index + 1; endPage = index + 1 }
            stripper.getText(doc).replace(Regex("\\s+"), "")
        }
    }

    private data class Parties(val senderId: Long, val recipientId: Long, val taxRateId: Long)

    /** [germanNote]/[englishNote] mirror [SenderProfile.changeInvoiceNote]'s own semantics — `null`
     *  means no entry for that language at all, not a blank one. */
    private fun buildParties(tag: String, germanNote: String?, englishNote: String?): Parties = QuarkusTransaction.requiringNew().call {
        val sender = aSenderProfile {
            name = "$tag Sender GmbH"
            invoicePrefix = "$tag-"
            registerCourt = "AG Musterstadt"
            email = Email.ofNullable("billing@$tag.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE111111111")
            iban = Iban.ofNullable("DE89370400440532013000")
        }
        germanNote?.let { sender.changeInvoiceNote(em.find(Language::class.java, 1), it) }
        englishNote?.let { sender.changeInvoiceNote(em.find(Language::class.java, 2), it) }
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
        Parties(sender.id!!, recipient.id!!, taxRate.id!!)
    }

    private fun draftInvoicePdf(tag: String, documentLanguage: String, germanNote: String?, englishNote: String?): List<String> {
        val parties = buildParties(tag, germanNote, englishNote)
        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(parties.senderId, parties.recipientId).id!! }
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "$tag Beratung",
                    quantity = BigDecimal("2"),
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"),
                    taxRateId = parties.taxRateId,
                ),
            )
        }
        // R3 normally resolves documentLanguage once at creation from the recipient's country;
        // overridden directly here purely to drive the language dimension — same idiom
        // InvoiceLifecycleWatermarkE2EIT.sentInvoiceForCancellation uses for documentLanguage. Since
        // ea6d6dd64 the invoice note is a snapshot taken at that same moment (INV-B), so
        // overriding the language afterwards must also refresh the snapshot — exactly what
        // InvoiceLifecycleService.updateHeader's applyInvoiceNoteChange does for an untouched
        // (SELLER_DEFAULT) note whose source language changed.
        QuarkusTransaction.requiringNew().run {
            val invoice = em.find(Invoice::class.java, invoiceId)!!
            invoice.edit { this.documentLanguage = documentLanguage }
            val sender = em.find(SenderProfile::class.java, parties.senderId)!!
            invoice.texts.applyInvoiceNote(
                initialInvoiceNoteSnapshot(sender.id, documentLanguage, sender.invoiceNoteFor(documentLanguage)),
            )
        }

        val dto = QuarkusTransaction.requiringNew().call { mapToDto(invoiceId) }
        return pageTexts(exportPdf(dto, "$tag-note"))
    }

    @Test
    fun `an English document with an English note prints the English text`() {
        val pages = draftInvoicePdf(
            tag = "IN2E1",
            documentLanguage = "en",
            germanNote = "Zahlbar ohne Abzug.",
            englishNote = "Payable without deduction.",
        )

        assertTrue(pages.any { it.contains("Payablewithoutdeduction.") }, "the English note must print, got: $pages")
        assertFalse(pages.any { it.contains("ZahlbarohneAbzug.") }, "the German note must not also print")
    }

    @Test
    fun `an English document without an English note falls back to the German text (MVP default, not an edge case)`() {
        val pages = draftInvoicePdf(
            tag = "IN2E2",
            documentLanguage = "en",
            germanNote = "Zahlbar ohne Abzug.",
            englishNote = null,
        )

        assertTrue(pages.any { it.contains("ZahlbarohneAbzug.") }, "the German note must print as the fallback, got: $pages")
    }

    @Test
    fun `a German document always prints the German text, even when an English note exists (golden - no German document moves)`() {
        val pages = draftInvoicePdf(
            tag = "IN2E3",
            documentLanguage = "de",
            germanNote = "Zahlbar ohne Abzug.",
            englishNote = "Payable without deduction.",
        )

        assertTrue(pages.any { it.contains("ZahlbarohneAbzug.") }, "the German note must print, got: $pages")
        assertFalse(pages.any { it.contains("Payablewithoutdeduction.") }, "the English note must never appear on a German document")
    }
}
