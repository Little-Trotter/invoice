package de.littletrotter.e2e.invoice

import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.invoice.port.inbound.ChangeInvoiceStatusUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.domain.exception.InvoiceCancellationNotAllowedException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.e2e.E2eBusinessDay
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.Money
import de.littletrotter.shared.documents.DocumentDates
import de.littletrotter.shared.documents.FreeTextMarkup
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.edit

/** Cancellation must only show when invoice status is ISSUED. Tests the real input ports
 *  ([InvoiceDetailQueryPort.canCreateCreditNote]/[ChangeInvoiceStatusUseCase.createCreditNote]) against the
 *  real [de.littletrotter.application.invoice.InvoiceCancellationService] to verify the whole chain
 *  works end to end. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class InvoiceDetailFacadeCancellationE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    @Inject
    lateinit var changeInvoiceStatus: ChangeInvoiceStatusUseCase

    @Inject
    lateinit var invoiceDetailQuery: InvoiceDetailQueryPort

    /** Same fixture idiom as [de.littletrotter.e2e.receipts.CancellationReceiptEmbeddingE2EIT] — a single
     *  sender/recipient/tax-rate/item, transitioned all the way to `ISSUED`.
     *  [documentLanguage] defaults to the entity default (`"de"`); pass `"en"` to build
     *  an English original for the cancellation-reference-language tests below. */
    private fun sentInvoice(tag: String, documentLanguage: String = "de", freeText: String? = null): Long {
        val (senderId, recipientId, taxRateId) = QuarkusTransaction.requiringNew().call {
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
            val recipient = aRecipient {
                customerNumber = CustomerNumber("$tag-100")
                name = "$tag Empfänger AG"
                email = Email.ofNullable("ap@$tag.example")
                vatId = VatId("DE222222222")
                routingId = "PO-$tag-STANDING"
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
            Triple(sender.id!!, recipient.id!!, taxRate.id!!)
        }

        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(senderId, recipientId).id!! }
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "$tag Beratung",
                    quantity = BigDecimal("2"),
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"),
                    taxRateId = taxRateId,
                ),
            )
        }
        // Cheap satisfaction of the CORE.SERVICE_PERIOD_XOR validation rule, same idiom as the
        // other suites in this module (CancellationReceiptEmbeddingE2EIT, InvoiceValidationStatusResolutionE2EIT).
        QuarkusTransaction.requiringNew().run {
            val invoice = em.find(Invoice::class.java, invoiceId)!!
            invoice.edit { deliveryDate = E2eBusinessDay.today() }
            invoice.edit { this.documentLanguage = documentLanguage }
            invoice.edit { this.freeText = freeText }
        }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.ISSUED) }
        return invoiceId
    }

    private fun statusOf(invoiceId: Long): InvoiceStatus =
        QuarkusTransaction.requiringNew().call<InvoiceStatus> { em.find(Invoice::class.java, invoiceId).status }

    @Test
    fun `canCreateCreditNote is true for an ISSUED invoice through the real input ports`() {
        val invoiceId = sentInvoice("STF1")

        assertTrue(invoiceDetailQuery.canCreateCreditNote(invoiceId), "an ISSUED invoice with no credit note yet must be stornierbar")
    }

    @Test
    fun `canCreateCreditNote is false for a DRAFT invoice through the real input ports`() {
        val (senderId, recipientId) = QuarkusTransaction.requiringNew().call {
            val sender = aSenderProfile {
                phone = "+49 30 1234567"
                name = "STF2 Sender GmbH"
                invoicePrefix = "STF2-"
                registerCourt = "AG Musterstadt"
                email = Email.ofNullable("billing@STF2.example")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE111111111")
                iban = Iban.ofNullable("DE89370400440532013000")
            }
            sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
            val recipient = aRecipient {
                customerNumber = CustomerNumber("STF2-100")
                name = "STF2 Empfänger AG"
                email = Email.ofNullable("ap@STF2.example")
                vatId = VatId("DE222222222")
            }
            recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
            em.persist(sender)
            em.persist(recipient)
            em.flush()
            sender.id!! to recipient.id!!
        }
        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(senderId, recipientId).id!! }

        assertEquals(InvoiceStatus.DRAFT, statusOf(invoiceId))
        assertFalse(invoiceDetailQuery.canCreateCreditNote(invoiceId), "PO order 2026-08-31: only ISSUED invoices may be stornoed")
    }

    @Test
    fun `canCreateCreditNote is false without throwing for an unknown invoice id through the real input ports`() {
        assertFalse(invoiceDetailQuery.canCreateCreditNote(-1L))
    }

    @Test
    fun `createCreditNote creates a terminal credit note and moves the original to CANCELLED`() {
        val invoiceId = sentInvoice("STF3")

        val creditNote = changeInvoiceStatus.createCreditNote(invoiceId, "E2E Storno-Grund")

        assertNotEquals(invoiceId, creditNote.id, "the credit note is a NEW invoice, not the original one transitioning")
        assertEquals(InvoiceStatus.CANCELLED, statusOf(creditNote.id), "ST-3: the storno document is born CANCELLED, already terminal")
        assertEquals(InvoiceStatus.CANCELLED, statusOf(invoiceId), "ST-3: the storno moves the original from ISSUED to CANCELLED")

        // uq_invoice_single_credit_note: once one credit note exists, the gate must flip.
        assertFalse(
            invoiceDetailQuery.canCreateCreditNote(invoiceId),
            "an invoice that already has a credit note against it must no longer offer Stornieren",
        )
    }

    @Test
    fun `createCreditNote on an ineligible invoice throws InvoiceCancellationNotAllowedException through the real input ports`() {
        val invoiceId = sentInvoice("STF4")
        changeInvoiceStatus.createCreditNote(invoiceId, "erster Versuch")

        // Defensive path (buildCockpitHero.createCreditNoteAndNavigate's catch block): the button
        // is already gated by canCreateCreditNote, this proves the facade itself still enforces
        // the rule rather than trusting the UI's gate alone (e.g. a second, racing browser tab).
        assertThrows(InvoiceCancellationNotAllowedException::class.java) {
            changeInvoiceStatus.createCreditNote(invoiceId, "zweiter Versuch")
        }
    }

    // ── The cancellation reference (BT-22) follows the original's document_language, and the SAME
    // composed sentence (composed once, at cancellation time, and persisted — not re-derived per
    // output) must reach both the printed PDF's free-text block and BT-22 of the exported CII. ──

    private fun mapToDto(invoiceId: Long): InvoiceDto {
        val invoice = em.find(Invoice::class.java, invoiceId)!!
        return InvoiceDtoMapper().mapToDto(invoice)
    }

    private fun extractPdfText(pdfBytes: ByteArray): String =
        Loader.loadPDF(pdfBytes).use { doc -> PDFTextStripper().getText(doc) }

    @Test
    fun `an English original produces the English cancellation reference in both the printed PDF and BT-22`() {
        val invoiceId = sentInvoice("STF5", documentLanguage = "en")
        val (originalNumber, originalDate) = QuarkusTransaction.requiringNew().call {
            val original = em.find(Invoice::class.java, invoiceId)!!
            original.invoiceNumber.value to original.invoiceDate
        }

        val creditNote = changeInvoiceStatus.createCreditNote(invoiceId, "Customer cancelled the order.")

        val dto = QuarkusTransaction.requiringNew().call { mapToDto(creditNote.id) }
        val expectedSentence = "Reversal of invoice $originalNumber dated " +
            DocumentDates.format(originalDate, "en") +
            ". Reason: Customer cancelled the order."
        assertEquals(expectedSentence, dto.meta.freeText, "BT-22 (Invoice.freeText) must carry the composed English sentence")

        val xml = String(XmlExportServiceImpl(null).generate(dto), Charsets.UTF_8)
        val compactXml = Regex(">\\s+<").replace(xml, "><")
        assertTrue(
            compactXml.contains("<ram:IncludedNote><ram:Content>$expectedSentence</ram:Content></ram:IncludedNote>"),
            "BT-22 in the exported CII must carry the English reference: $compactXml",
        )

        val pdfText = extractPdfText(TypstContainerRenderer().render(dto))
        assertTrue(pdfText.contains(expectedSentence), "the printed PDF's free-text block must carry the same English reference: $pdfText")
    }

    @Test
    fun `a German original still produces the German cancellation reference in both outputs - golden, unchanged`() {
        val invoiceId = sentInvoice("STF6") // documentLanguage defaults "de"
        val (originalNumber, originalDate) = QuarkusTransaction.requiringNew().call {
            val original = em.find(Invoice::class.java, invoiceId)!!
            original.invoiceNumber.value to original.invoiceDate
        }

        val creditNote = changeInvoiceStatus.createCreditNote(invoiceId, "Kunde hat storniert.")

        val dto = QuarkusTransaction.requiringNew().call { mapToDto(creditNote.id) }
        val expectedSentence = "Stornierung der Rechnung $originalNumber vom " +
            DocumentDates.format(originalDate, "de") +
            ". Grund: Kunde hat storniert."
        assertEquals(expectedSentence, dto.meta.freeText, "a German storno document's BT-22 content must stay byte-identical to before IN-3")

        val xml = String(XmlExportServiceImpl(null).generate(dto), Charsets.UTF_8)
        val compactXml = Regex(">\\s+<").replace(xml, "><")
        assertTrue(
            compactXml.contains("<ram:IncludedNote><ram:Content>$expectedSentence</ram:Content></ram:IncludedNote>"),
            "BT-22 in the exported CII must carry the German reference unchanged: $compactXml",
        )

        val pdfText = extractPdfText(TypstContainerRenderer().render(dto))
        assertTrue(pdfText.contains(expectedSentence), "the printed PDF's free-text block must carry the same German reference: $pdfText")
    }

    // ── A MAX_LENGTH-character free text is stored whole, printed whole, and cancellation neither
    // fails on it nor caps what the server composes on top of the user's own entry. ──

    /** Numbered German prose of exactly [length] characters (umlauts and `€` included; the last
     *  sentence is closed by the token `ENDE`). */
    private fun freeTextOf(length: Int): String {
        val sb = StringBuilder()
        var n = 1
        while (sb.length < length) {
            sb.append("S${n.toString().padStart(3, '0')} Vielen Dank für Ihren Auftrag, die Arbeiten wurden für 1.234,56 € ausgeführt. ")
            n++
        }
        return sb.substring(0, length - 5).trimEnd().padEnd(length - 5, '.') + " ENDE"
    }

    @Test
    fun `cancellation of an invoice with a MAX_LENGTH character free text succeeds and leaves original and print whole`() {
        val freeText = freeTextOf(FreeTextMarkup.MAX_LENGTH)
        assertEquals(FreeTextMarkup.MAX_LENGTH, freeText.length)
        val invoiceId = sentInvoice("STF7", freeText = freeText)
        val (originalNumber, originalDate) = QuarkusTransaction.requiringNew().call {
            val original = em.find(Invoice::class.java, invoiceId)!!
            original.invoiceNumber.value to original.invoiceDate
        }

        val creditNote = changeInvoiceStatus.createCreditNote(invoiceId, "Kunde hat storniert.")

        val storedOriginal = QuarkusTransaction.requiringNew().call { em.find(Invoice::class.java, invoiceId)!!.texts.freeText }
        assertEquals(freeText, storedOriginal, "the MAX_LENGTH-character free text of the cancelled original must be stored whole")
        val creditNoteDto = QuarkusTransaction.requiringNew().call { mapToDto(creditNote.id) }
        assertEquals(
            "Stornierung der Rechnung $originalNumber vom " + DocumentDates.format(originalDate, "de") + ". Grund: Kunde hat storniert.",
            creditNoteDto.meta.freeText,
            "the credit note carries the composed reference, not a copy of the original's free text",
        )

        val pdfText = extractPdfText(TypstContainerRenderer().render(mapToDto(invoiceId)))
        assertTrue(pdfText.contains("S001 Vielen Dank"), "first sentence of the MAX_LENGTH-character free text must be printed")
        assertTrue(pdfText.contains("ENDE"), "the last sentence of the MAX_LENGTH-character free text must be printed")
    }

    @Test
    fun `a cancellation reason that pushes the composed free text past MAX_LENGTH characters is stored whole`() {
        val invoiceId = sentInvoice("STF8")
        val reason = freeTextOf(FreeTextMarkup.MAX_LENGTH)

        val creditNote = changeInvoiceStatus.createCreditNote(invoiceId, reason)

        val stored = QuarkusTransaction.requiringNew().call { em.find(Invoice::class.java, creditNote.id)!!.texts.freeText }
        assertTrue(stored!!.length > FreeTextMarkup.MAX_LENGTH, "reference sentence plus a MAX_LENGTH-character reason exceeds the UI limit: ${stored.length}")
        assertTrue(stored.startsWith("Stornierung der Rechnung "), "the composed reference leads the text")
        assertTrue(stored.endsWith("Grund: $reason"), "the reason is stored whole, nothing truncated")
    }
}
