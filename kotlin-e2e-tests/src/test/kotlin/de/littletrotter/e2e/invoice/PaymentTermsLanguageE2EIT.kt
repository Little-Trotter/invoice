package de.littletrotter.e2e.invoice

import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.Language
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.DiscountPercent
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
 * The payment-terms wording (BT-20 on the wire, the "payment due" line on the PDF) follows
 * the document language, end to end against a real Postgres and the real Typst
 * renderer/[XmlExportServiceImpl]. Mirrors
 * [SellerInvoiceNoteE2EIT]'s own shape and golden-invariant coverage.
 *
 * PO decisions of 06.09.2026 reshaped this package after the original work order: the chosen
 * [PaymentTerm]'s own reference-data name (resolved for the document language, EN missing falls
 * back to DE) is now the text on BOTH outputs whenever the invoice has a payment term at all — the
 * `DocumentTexts` date-composed sentences are the fallback for the one remaining case, an invoice
 * with no payment term. Since [InvoiceLifecycleService.create] always assigns one (the sender's
 * own default or, failing that, the global default row), that fallback path needs its own explicit
 * "no payment term" fixture below — the payment-term-name path is what a real invoice actually
 * takes, and is the direct regression test for the package's original defect
 * ("payment_term.english_name never reached a document").
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class PaymentTermsLanguageE2EIT {

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

    /** Whitespace-collapsed text of every page — same idiom as [SellerInvoiceNoteE2EIT.pageTexts]. */
    private fun pageTexts(pdfBytes: ByteArray): List<String> = Loader.loadPDF(pdfBytes).use { doc ->
        (0 until doc.numberOfPages).map { index ->
            val stripper = PDFTextStripper().apply { startPage = index + 1; endPage = index + 1 }
            stripper.getText(doc).replace(Regex("\\s+"), "")
        }
    }

    private data class Parties(val senderId: Long, val recipientId: Long, val taxRateId: Long)

    private fun buildParties(tag: String): Parties = QuarkusTransaction.requiringNew().call {
        val sender = SenderProfile().apply {
            name = "$tag Sender GmbH"
            invoicePrefix = "$tag-"
            registerCourt = "AG Musterstadt"
            email = Email.ofNullable("billing@$tag.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE111111111")
            iban = Iban.ofNullable("DE89370400440532013000")
        }
        sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
        val recipient = Recipient().apply {
            customerNumber = CustomerNumber("$tag-100")
            name = "$tag Empfänger AG"
            email = Email.ofNullable("ap@$tag.example")
            vatId = VatId("DE222222222")
        }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
        em.persist(sender)
        em.persist(recipient)

        val taxRate = TaxRate().apply {
            code = "${tag}_STD19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isSelectable = true; isActive = true
        }
        em.persist(taxRate)
        em.flush()
        Parties(sender.id!!, recipient.id!!, taxRate.id!!)
    }

    /** Builds and persists a [PaymentTerm] with the given German (fallback) name and, optionally,
     *  an English one — same construction [PaymentTermsTab.applyFormData] uses in production.
     *  [discountPercent]/[discountDays] are optional Skonto conditions (BR-DE-18); PaymentTerm's
     *  own invariant requires both set or neither. */
    private fun persistPaymentTerm(
        germanName: String,
        englishName: String?,
        dueInDays: Int = 30,
        discountPercent: BigDecimal? = null,
        discountDays: Int? = null,
    ): Long =
        QuarkusTransaction.requiringNew().call {
            val german = em.find(Language::class.java, 1)!!
            val term = PaymentTerm().apply {
                setName(german, germanName)
                englishName?.let { setName(em.find(Language::class.java, 2)!!, it) }
                this.dueInDays = dueInDays
                discountPercent?.let { this.discountPercent = DiscountPercent(it) }
                this.discountDays = discountDays
            }
            em.persist(term)
            em.flush()
            term.id!!
        }

    private data class ExportResult(val pages: List<String>, val xml: String)

    private fun buildInvoiceAndExport(
        tag: String,
        documentLanguage: String,
        paymentTermId: Long?,
        clearPaymentTermsDaysAndDueDate: Boolean = false,
    ): ExportResult {
        val parties = buildParties(tag)
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
        // SellerInvoiceNoteE2EIT/InvoiceLifecycleWatermarkE2EIT use for documentLanguage.
        // InvoiceLifecycleService.create() always assigns SOME payment term (sender default, or
        // failing that the global default row) — paymentTermId overrides it directly to the
        // fixture built by persistPaymentTerm, or to null for the "no payment term at all"
        // fallback-path tests, which also need paymentTermsDays/dueDate cleared (PO decisions of
        // 06.09.2026 only compose a date-based sentence when there truly is no payment term; a
        // real invoice always carries a derived, non-null dueDate otherwise).
        QuarkusTransaction.requiringNew().run {
            em.find(Invoice::class.java, invoiceId)!!.apply {
                this.documentLanguage = documentLanguage
                this.paymentTerm = paymentTermId?.let { em.find(PaymentTerm::class.java, it) }
                if (clearPaymentTermsDaysAndDueDate) {
                    this.paymentTermsDays = 30
                    this.dueDate = null
                }
            }
        }

        val dto = QuarkusTransaction.requiringNew().call { mapToDto(invoiceId) }
        val pages = pageTexts(exportPdf(dto, "$tag-pt"))
        val xml = String(XmlExportServiceImpl(null).generate(dto), Charsets.UTF_8)
        return ExportResult(pages, xml)
    }

    // ── PO decision 2/4 (06.09.2026) — the chosen payment term's own name IS the text, on both
    //    outputs, and it actually reaches the document (the package's original defect). ──────────

    @Test
    fun `a chosen payment term's English name reaches both BT-20 and the printed line on an English document`() {
        val termId = persistPaymentTerm(germanName = "IN4E1 30 Tage netto", englishName = "IN4E1 Net 30 days")
        val result = buildInvoiceAndExport(tag = "IN4E1", documentLanguage = "en", paymentTermId = termId)

        assertTrue(result.xml.contains("IN4E1 Net 30 days"), "BT-20 must carry the term's English name verbatim")
        assertTrue(result.pages.any { it.contains("IN4E1Net30days") }, "the printed line must carry the term's English name, got: ${result.pages}")
        assertFalse(result.xml.contains("IN4E1 30 Tage netto"), "the German name must not also appear on an English document")
    }

    @Test
    fun `a chosen payment term's German name reaches both BT-20 and the printed line on a German document`() {
        val termId = persistPaymentTerm(germanName = "IN4E2 30 Tage netto", englishName = "IN4E2 Net 30 days")
        val result = buildInvoiceAndExport(tag = "IN4E2", documentLanguage = "de", paymentTermId = termId)

        assertTrue(result.xml.contains("IN4E2 30 Tage netto"), "BT-20 must carry the term's German name verbatim")
        assertTrue(result.pages.any { it.contains("IN4E230Tagenetto") }, "the printed line must carry the term's German name, got: ${result.pages}")
    }

    @Test
    fun `an English document falls back to the German name when the payment term has no English one (PaymentTerm-nameFor's own rule, reaching the document)`() {
        // This is the direct regression test for the package's original defect: the term's
        // English name (when captured) is what used to never reach a document at all; here the
        // absence of one and the fallback it triggers are both exercised end to end.
        val termId = persistPaymentTerm(germanName = "IN4E3 Sofort fällig", englishName = null)
        val result = buildInvoiceAndExport(tag = "IN4E3", documentLanguage = "en", paymentTermId = termId)

        assertTrue(result.xml.contains("IN4E3 Sofort fällig"), "BT-20 must fall back to the German name, got: ${result.xml}")
        assertTrue(result.pages.any { it.contains("IN4E3Sofortfällig") }, "the printed line must fall back to the German name, got: ${result.pages}")
    }

    // ── PO decision 3 (06.09.2026) — the DocumentTexts fallback (no payment term at all) keeps
    //    the PDF's pre-existing German wording without "von"; the wire follows the print image. ──

    @Test
    fun `an invoice with no payment term at all falls back to the composed sentence, identical on both outputs`() {
        val result = buildInvoiceAndExport(tag = "IN4E4", documentLanguage = "de", paymentTermId = null, clearPaymentTermsDaysAndDueDate = true)

        assertTrue(result.xml.contains("Zahlbar innerhalb 30 Tagen netto"), "BT-20 fallback text, no \"von\" (PO decision 3), got: ${result.xml}")
        assertTrue(result.pages.any { it.contains("Zahlbarinnerhalb30Tagennetto") }, "the printed fallback line must match BT-20 exactly, got: ${result.pages}")
    }

    // ── Golden — the realistic default (a payment term IS chosen) leaves everything else alone ──

    @Test
    fun `the German item description and totals are unaffected by IN-4 (golden)`() {
        val termId = persistPaymentTerm(germanName = "IN4E5 30 Tage netto", englishName = "IN4E5 Net 30 days")
        val result = buildInvoiceAndExport(tag = "IN4E5", documentLanguage = "de", paymentTermId = termId)

        assertTrue(result.pages.any { it.contains("IN4E5Beratung") }, "the item description must render unchanged, got: ${result.pages}")
        assertTrue(result.pages.any { it.contains("200,00") }, "the German amount grouping (2 x 100,00) must render unchanged, got: ${result.pages}")
    }

    // ── PO decision 1 (06.09.2026) — the term name is byte-identical between the PDF and the
    //    wire; a cash discount (BR-DE-18) is where the two texts diverge on purpose: BT-20 gets a
    //    structured #SKONTO# line, the PDF its own human-readable Skonto sentence. ──────────────

    @Test
    fun `the printed payment line and BT-20 both begin with the term name, and BT-20 carries the BR-DE-18 Skonto line`() {
        val termId = persistPaymentTerm(
            germanName = "IN4E6 14 Tage netto",
            englishName = "IN4E6 Net 14 days",
            discountPercent = BigDecimal("2"),
            discountDays = 7,
        )
        val result = buildInvoiceAndExport(tag = "IN4E6", documentLanguage = "en", paymentTermId = termId)

        // Scoped to the SpecifiedTradePaymentTerms wrapper: ram:Description is not a unique tag
        // name in this document (item descriptions use it too).
        val paymentTermsBlock = Regex(
            "<ram:SpecifiedTradePaymentTerms>.*?</ram:SpecifiedTradePaymentTerms>",
            RegexOption.DOT_MATCHES_ALL,
        ).find(result.xml)?.value
        val bt20 = paymentTermsBlock?.let { Regex("<ram:Description>([^<]*)</ram:Description>").find(it)?.groupValues?.get(1) }
        assertEquals(
            "IN4E6 Net 14 days\n#SKONTO#TAGE=7#PROZENT=2.00#\n",
            bt20,
            "BT-20 must be the term's resolved name plus the BR-DE-18 Skonto line, xml: ${result.xml}",
        )
        assertTrue(result.pages.any { it.contains("IN4E6Net14days") }, "the printed line must begin with the exact same term name, got: ${result.pages}")
        assertTrue(
            result.pages.any { it.contains("discount") },
            "the printed page must carry the English Skonto sentence (paymentTerms.cashDiscount), got: ${result.pages}",
        )
    }
}
