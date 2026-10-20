package de.littletrotter.e2e.invoice

import de.littletrotter.e2e.receipts.receiptUploadService
import de.littletrotter.application.invoice.port.outbound.InvoiceLookupRepository
import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceCancellationService
import de.littletrotter.application.invoice.ReceiptBudgetService
import de.littletrotter.application.invoice.ReceiptLinkService
import de.littletrotter.application.invoice.ReceiptPoolService
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.value.VatId
import de.littletrotter.e2e.E2eBusinessDay
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import de.littletrotter.e2e.einvoice.EInvoiceXml
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
import de.littletrotter.shared.BasePdfRendererPort
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.KositValidatorPort
import de.littletrotter.shared.Money
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.resolveOutputIntentSubject
import de.littletrotter.xpdf.impl.OutputIntentVerifier
import de.littletrotter.xpdf.impl.XmlExportServiceImpl
import de.littletrotter.xpdf.testsupport.PdfExportServiceImplTestFactory
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.time.Instant
import java.time.LocalDate
import java.util.Base64

/**
 * Catalogue of the business invariants of an exported invoice document, each one a fact whose
 * silent loss would go unnoticed by every layout-oriented test: receipt pages are appended to the
 * PDF, the same receipts are listed as supporting documents in the embedded XML, and every
 * document carries exactly the stamp its state and deployment call for.
 *
 * The invoice base page is a stub renderer's single blank page — the subject is what the export
 * pipeline adds around it (receipt annex pages, BG-24 entries, the stamp), all of it real. Where
 * another suite already asserts one of these facts in passing, it is repeated here on purpose so
 * the catalogue stays complete in one place.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class ExportInvariantsE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    @Inject
    lateinit var cancellationService: InvoiceCancellationService

    @Inject
    lateinit var receiptRepository: ReceiptRepository

    @Inject
    lateinit var invoiceRepository: InvoiceRepository

    @Inject
    lateinit var invoiceLookup: InvoiceLookupRepository

    private class InMemoryDocumentStore : DocumentStore {
        val objects = mutableMapOf<String, ByteArray>()
        override fun put(objectKey: String, data: ByteArray, contentType: String) { objects[objectKey] = data }
        override fun get(objectKey: String): ByteArray = objects[objectKey] ?: error("not stored: $objectKey")
        override fun exists(objectKey: String): Boolean = objects.containsKey(objectKey)
        override fun enableImmutability(objectKey: String) {}
        override fun delete(objectKey: String) { objects.remove(objectKey) }
    }

    /** A receipt to upload: [pages] genuinely distinct pages so a dropped page changes the count. */
    private data class ReceiptSpec(val fileName: String, val pages: Int)

    private data class Fixture(val invoiceId: Long, val store: InMemoryDocumentStore)

    private fun pdf(pages: Int, label: String): ByteArray {
        PDDocument().use { doc ->
            repeat(pages) { index ->
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                    cs.newLineAtOffset(50f, 700f)
                    cs.showText("E2E export invariants $label page ${index + 1}")
                    cs.endText()
                }
            }
            val os = ByteArrayOutputStream()
            doc.save(os)
            return os.toByteArray()
        }
    }

    private fun blankInvoicePage(): ByteArray = pdf(1, "base")

    private fun mapToDto(invoiceId: Long): InvoiceDto = QuarkusTransaction.requiringNew().call {
        InvoiceDtoMapper(receiptRepository = receiptRepository).mapToDto(em.find(Invoice::class.java, invoiceId)!!)
    }

    /**
     * Builds an invoice with one service item and the given receipts assigned at invoice level;
     * ISSUED when [issue] is set, otherwise left as the numbered DRAFT it is born as. Parties carry
     * the tax data the e-invoice schema needs.
     */
    private fun buildInvoice(tag: String, receipts: List<ReceiptSpec> = emptyList(), issue: Boolean = true): Fixture {
        val store = InMemoryDocumentStore()
        val upload = receiptUploadService(receiptRepository, store, de.littletrotter.shared.time.ClockTimeSource.fixedAt(Instant.parse("2026-06-15T10:00:00Z")))
        val link = ReceiptLinkService(receiptRepository, invoiceRepository, invoiceLookup, ReceiptPoolService(receiptRepository, store), ReceiptBudgetService(receiptRepository))

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
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, invoiceId)!!.edit { deliveryDate = E2eBusinessDay.today() } }
        if (receipts.isNotEmpty()) {
            val ids = receipts.map { spec ->
                QuarkusTransaction.requiringNew().call {
                    upload.upload(spec.fileName, "Beleg ${spec.fileName}", pdf(spec.pages, spec.fileName), LocalDate.of(2026, 3, 14)).id!!
                }
            }
            QuarkusTransaction.requiringNew().run { link.addReceiptsToInvoice(invoiceId, ids) }
        }
        if (issue) {
            QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
            QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.ISSUED) }
        }
        return Fixture(invoiceId, store)
    }

    private fun exportPdf(
        dto: InvoiceDto,
        store: InMemoryDocumentStore,
        jobId: String,
        demoText: String? = null,
        grants: Pair<OutputIntentVerifier, String>? = null,
    ): ByteArray {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(blankInvoicePage())
        val kosit = object : KositValidatorPort {
            override fun validate(xml: String, timeoutMs: Long) = KositValidationResult(status = KositOutcome.VALID)
        }
        val service = if (grants == null) {
            PdfExportServiceImplTestFactory.create(
                xmlExportService = XmlExportServiceImpl(store),
                reportRenderer = renderer,
                documentStore = store,
                kositValidator = kosit,
                demoWatermarkText = demoText,
            )
        } else {
            PdfExportServiceImplTestFactory.create(
                xmlExportService = XmlExportServiceImpl(store),
                reportRenderer = renderer,
                documentStore = store,
                kositValidator = kosit,
                outputIntentVerifier = grants.first,
                demoWatermarkText = demoText,
            )
        }
        val key = service.performExport(dto, jobId, outputIntent = grants?.second).objectKey
        return store.get(key)
    }

    /** Per page, the text drawn axis-aligned (invoice and annex content), whitespace-normalised. */
    private fun bodyTexts(pdfBytes: ByteArray): List<String> = pageTexts(pdfBytes, rotated = false)

    /** Per page, the text drawn with a rotated text matrix — the diagonal stamp and nothing else —
     *  with all whitespace removed because letter-spaced marks extract with stray gaps. */
    private fun stampTexts(pdfBytes: ByteArray): List<String> = pageTexts(pdfBytes, rotated = true)

    private fun pageTexts(pdfBytes: ByteArray, rotated: Boolean): List<String> = Loader.loadPDF(pdfBytes).use { doc ->
        (0 until doc.numberOfPages).map { index ->
            val stripper = object : PDFTextStripper() {
                val picked = StringBuilder()

                override fun processTextPosition(text: TextPosition) {
                    val isRotated = kotlin.math.abs(text.textMatrix.shearY) > 0.01f
                    if (isRotated == rotated) picked.append(text.unicode)
                }
            }
            stripper.startPage = index + 1
            stripper.endPage = index + 1
            stripper.getText(doc)
            val raw = stripper.picked.toString()
            if (rotated) raw.replace(Regex("\\s+"), "") else raw.replace(Regex("\\s+"), " ").trim()
        }
    }

    private fun extractEmbeddedXml(pdfBytes: ByteArray): String = Loader.loadPDF(pdfBytes).use { doc ->
        val names = doc.documentCatalog.names?.embeddedFiles?.names ?: emptyMap()
        val spec = names["factur-x.xml"] ?: names["xrechnung.xml"] ?: error("no embedded invoice XML, found ${names.keys}")
        String(spec.embeddedFile.toByteArray(), StandardCharsets.UTF_8)
    }

    @Nested
    inner class ReceiptPages {

        private val receipts = listOf(
            ReceiptSpec("taxi.pdf", pages = 1),
            ReceiptSpec("hotel.pdf", pages = 3),
            ReceiptSpec("fahrt.pdf", pages = 1),
        )

        @Test
        fun `PDF holds the base page plus one annex page per receipt page`() {
            val fx = buildInvoice("EXI1", receipts)
            val pdfBytes = exportPdf(mapToDto(fx.invoiceId), fx.store, "exi1-pages")

            val basePages = 1
            assertEquals(basePages + receipts.sumOf { it.pages }, bodyTexts(pdfBytes).size, "base page plus one page per receipt page")
        }

        @Test
        fun `every annex page carries its numbered header, multi-page receipts with the page suffix`() {
            val fx = buildInvoice("EXI2", receipts)
            val pages = bodyTexts(exportPdf(mapToDto(fx.invoiceId), fx.store, "exi2-header")).drop(1)

            val headers = pages.map { page -> Regex("Anlage \\d+/\\d+ – \\S+(?: \\(Seite \\d+/\\d+\\))?").find(page)?.value }
            assertTrue(headers.none { it == null }, "each annex page needs a header, got: $pages")
            val byName = headers.filterNotNull().groupBy { header -> receipts.single { header.contains(it.fileName) }.fileName }
            assertEquals(receipts.map { it.fileName }.toSet(), byName.keys, "every receipt must appear on an annex page")
            receipts.forEach { spec ->
                val own = byName.getValue(spec.fileName)
                assertEquals(spec.pages, own.size, "${spec.fileName}: one annex page per receipt page")
                if (spec.pages == 1) {
                    assertFalse(own.single().contains("(Seite"), "${spec.fileName}: a one-page receipt carries no page suffix")
                } else {
                    (1..spec.pages).forEach { p ->
                        assertTrue(own.any { it.endsWith("(Seite $p/${spec.pages})") }, "${spec.fileName}: missing page suffix $p/${spec.pages}, got $own")
                    }
                }
            }
            val total = receipts.size
            assertEquals(
                (1..total).toSet(),
                headers.filterNotNull().map { Regex("Anlage (\\d+)/$total").find(it)!!.groupValues[1].toInt() }.toSet(),
                "annex numbers must run 1..$total with the receipt count as denominator",
            )
        }
    }

    @Nested
    inner class SupportingDocuments {

        @Test
        fun `embedded XML lists exactly one BG-24 attachment per receipt with its file name`() {
            val receipts = listOf(ReceiptSpec("taxi.pdf", 1), ReceiptSpec("hotel.pdf", 3), ReceiptSpec("fahrt.pdf", 1))
            val fx = buildInvoice("EXI3", receipts)
            val xml = extractEmbeddedXml(exportPdf(mapToDto(fx.invoiceId), fx.store, "exi3-bg24"))

            val attachments = EInvoiceXml.textList(
                xml,
                "//ram:ApplicableHeaderTradeAgreement/ram:AdditionalReferencedDocument[ram:TypeCode='916']/ram:IssuerAssignedID",
            )
            assertEquals(receipts.size, attachments.size, "one BG-24 entry per receipt, not per page")
            assertEquals(receipts.map { it.fileName }.toSet(), attachments.toSet())
        }

        @Test
        fun `an invoice without receipts has no BG-24 attachment and no annex page`() {
            val fx = buildInvoice("EXI4")
            val pdfBytes = exportPdf(mapToDto(fx.invoiceId), fx.store, "exi4-none")

            assertEquals(1, bodyTexts(pdfBytes).size)
            assertTrue(
                EInvoiceXml.textList(
                    extractEmbeddedXml(pdfBytes),
                    "//ram:ApplicableHeaderTradeAgreement/ram:AdditionalReferencedDocument[ram:TypeCode='916']/ram:IssuerAssignedID",
                ).isEmpty(),
            )
        }
    }

    @Nested
    inner class WatermarkMatrix {

        private val demoText = "Testbetrieb Demo"

        private fun allPages(texts: List<String>, predicate: (String) -> Boolean) = texts.isNotEmpty() && texts.all(predicate)

        @Test
        fun `cancelled - the credit note carries CANCELLATION on every page and no brand mark`() {
            val original = buildInvoice("EXW1", listOf(ReceiptSpec("beleg.pdf", 1)))
            val creditNoteId = QuarkusTransaction.requiringNew().call { cancellationService.createCreditNote(original.invoiceId, "E2E Storno-Grund").id!! }
            val marks = stampTexts(exportPdf(mapToDto(creditNoteId), original.store, "exw1-storno"))

            assertTrue(allPages(marks) { it.contains("STORNO") }, "STORNO on every page incl. annex, got $marks")
            assertTrue(marks.none { it.contains("LittleTrotter") || it.contains("little-trotter") }, "a reversal carries neither brand nor project link, got $marks")
        }

        @Test
        fun `draft - ENTWURF on every page`() {
            val fx = buildInvoice("EXW2", listOf(ReceiptSpec("beleg.pdf", 1)), issue = false)
            val marks = stampTexts(exportPdf(mapToDto(fx.invoiceId), fx.store, "exw2-draft"))

            assertTrue(allPages(marks) { it.contains("ENTWURF") }, "ENTWURF on every page incl. annex, got $marks")
        }

        @Test
        fun `issued - brand mark and no draft or reversal mark`() {
            val fx = buildInvoice("EXW3")
            val marks = stampTexts(exportPdf(mapToDto(fx.invoiceId), fx.store, "exw3-issued"))

            assertTrue(allPages(marks) { it.contains("LittleTrotter") }, "got $marks")
            assertTrue(marks.none { it.contains("ENTWURF") || it.contains("STORNO") }, "got $marks")
        }

        private fun grantFor(dto: InvoiceDto): Pair<OutputIntentVerifier, String> {
            val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val verifier = OutputIntentVerifier(listOf(keyPair.public))
            val hash = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(checkNotNull(resolveOutputIntentSubject(dto)).toByteArray(StandardCharsets.UTF_8)),
            )
            val payload = "oi1.$hash.-"
            val signature = Signature.getInstance("Ed25519").run {
                initSign(keyPair.private)
                update(payload.toByteArray(StandardCharsets.UTF_8))
                sign()
            }
            return verifier to (payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature))
        }

        @Test
        fun `support with text - covered seller shows the configured text alone`() {
            val fx = buildInvoice("EXW4", listOf(ReceiptSpec("beleg.pdf", 1)))
            val dto = mapToDto(fx.invoiceId)
            val marks = stampTexts(exportPdf(dto, fx.store, "exw4-support-text", demoText = demoText, grants = grantFor(dto)))

            val expected = demoText.replace(" ", "")
            assertTrue(allPages(marks) { it == expected }, "the configured text and nothing else on every page, got $marks")
        }

        @Test
        fun `support without text - covered seller is left without any stamp`() {
            val fx = buildInvoice("EXW5", listOf(ReceiptSpec("beleg.pdf", 1)))
            val dto = mapToDto(fx.invoiceId)
            val marks = stampTexts(exportPdf(dto, fx.store, "exw5-support-plain", grants = grantFor(dto)))

            assertTrue(marks.isNotEmpty() && marks.all { it.isEmpty() }, "no stamp on any page, got $marks")
        }

        @Test
        fun `demo instance - the configured text joins the brand mark on every page`() {
            val fx = buildInvoice("EXW6", listOf(ReceiptSpec("beleg.pdf", 1)))
            val marks = stampTexts(exportPdf(mapToDto(fx.invoiceId), fx.store, "exw6-demo", demoText = demoText))

            assertTrue(
                allPages(marks) { it.contains("LittleTrotter") && it.contains(demoText.replace(" ", "")) },
                "brand mark plus demo text on every page, got $marks",
            )
        }

        @Test
        fun `demo instance - a draft keeps its ENTWURF mark next to the demo text`() {
            val fx = buildInvoice("EXW7", issue = false)
            val marks = stampTexts(exportPdf(mapToDto(fx.invoiceId), fx.store, "exw7-demo-draft", demoText = demoText))

            assertTrue(allPages(marks) { it.contains("ENTWURF") && it.contains(demoText.replace(" ", "")) }, "got $marks")
        }
    }
}
