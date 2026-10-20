package de.littletrotter.xpdf.impl

import de.littletrotter.xpdf.testsupport.PdfExportServiceImplTestFactory
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.InvoiceDatesDto
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceItemDto
import de.littletrotter.shared.InvoiceNumberDto
import de.littletrotter.shared.InvoiceTotalsDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.Money
import de.littletrotter.shared.PartyDto
import de.littletrotter.shared.ValidationToolchain
import de.littletrotter.shared.VeraPdfResult
import de.littletrotter.xpdf.b2bInvoiceDto
import de.littletrotter.xpdf.testsupport.OutputIntentTestKeys
import de.littletrotter.xpdf.testsupport.TypstContainerRenderer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * V2-T04…T09 — the **real** PDF/A-3B conformance proof: the documents this project actually
 * exports, checked by the pinned veraPDF service.
 *
 * Two rules this test lives by:
 *
 * 1. **It never passes without an engine.** The service comes from a pinned [VeraPdfTestResource]
 *    container rather than the host, so this proof does not depend on what happens to be
 *    installed where it runs — a container that fails to start is a red test with a clear
 *    message, never a silent skip. A conformance claim nobody verified must not look verified —
 *    that is the whole reason this stayed formally unbelegt so far.
 * 2. **It checks the export's own output**, not a hand-built PDF: the same
 *    [PdfExportServiceImpl] path that produces what customers receive, so the answer covers the
 *    document as shipped.
 *
 * Results are written to a results artifact, from which the release
 * report takes its PDF/A section — the report never carries hand-typed conformance data.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VeraPdfConformanceIT {

    private val mapper = ObjectMapper()
    private val recordedCases = mutableListOf<Triple<String, VeraPdfResult, String>>()

    // Typst is the only engine — this conformance proof runs against the document customers
    // actually receive.
    private val typstRenderer = TypstContainerRenderer()

    /** Runs the export's PDF through the containerized veraPDF service with the client production uses. */
    private fun validate(pdf: ByteArray): VeraPdfResult = veraPdf.client().validate(pdf)

    // ── document production (the real export path) ───────────────────────────

    private class CapturingStore(private val seeded: Map<String, ByteArray> = emptyMap()) : DocumentStore {
        val written = linkedMapOf<String, ByteArray>()
        override fun put(objectKey: String, data: ByteArray, contentType: String) { written[objectKey] = data }
        override fun get(objectKey: String): ByteArray =
            written[objectKey] ?: seeded[objectKey] ?: error("not found: $objectKey")
        override fun exists(objectKey: String): Boolean = written.containsKey(objectKey) || seeded.containsKey(objectKey)
        override fun enableImmutability(objectKey: String) {}
        override fun delete(objectKey: String) { written.remove(objectKey) }
    }

    private fun invoice(number: String): InvoiceDto {
        val sender = PartyDto(
            name = "Little Trotter Transport GmbH",
            street = "Hafenstraße 12", postalCode = "28195", city = "Bremen", country = "Deutschland",
            email = "buchhaltung@little-trotter.de", vatId = "DE123456789",
            iban = "DE89370400440532013000",
        )
        val recipient = PartyDto(
            name = "Beispiel-Kunde AG",
            street = "Musterstraße 42", postalCode = "10115", city = "Berlin",
            customerNumber = "K-0042",
        )
        val items = (1..2).map { i ->
            InvoiceItemDto(
                positionId = i.toString(),
                description = "Transportleistung Tour $i",
                quantity = java.math.BigDecimal("1.00"),
                unitPriceNet = Money(java.math.BigDecimal("100.00")),
                netAmount = Money(java.math.BigDecimal("100.00")),
                vatAmount = Money(java.math.BigDecimal("19.00")),
                taxRatePercent = java.math.BigDecimal("19.00"),
            )
        }
        return InvoiceDto(
            invoiceNumber = InvoiceNumberDto(number),
            dates = InvoiceDatesDto(invoiceDate = "2026-08-01", dueDate = "2026-08-15"),
            senderProfile = sender,
            recipient = recipient,
            items = items,
            totals = InvoiceTotalsDto(
                netAmount = Money(java.math.BigDecimal("200.00")),
                vatAmount = Money(java.math.BigDecimal("38.00")),
                grossAmount = Money(java.math.BigDecimal("238.00")),
            ),
        )
    }

    /** A foreign document, standard font, no PDF/A — the shape of a deliberately
     *  non-conformant fixture ("V2-T07" below is this helper's only remaining caller). */
    private fun foreignPdf(text: String): ByteArray = PDDocument().use { doc ->
        val page = PDPage()
        doc.addPage(page)
        PDPageContentStream(doc, page).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
            cs.newLineAtOffset(72f, 700f)
            cs.showText(text)
            cs.endText()
        }
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    /** Runs the real export and returns the produced invoice PDF. spec-draft-watermark's/ST-5's
     *  own cases are the only callers passing `invoice.draft`/`cancellation` — the real `completeExport`
     *  routing decides which of the three marks to stamp from those flags, not a separate code
     *  path. */
    private fun exportPdf(
        invoice: InvoiceDto,
        seeded: Map<String, ByteArray> = emptyMap(),
        demoWatermarkText: String? = null,
        outputIntent: String? = null,
    ): ByteArray {
        val store = CapturingStore(seeded)
        val reportRenderer = typstRenderer
        val basePdfStage = BasePdfStage(reportRenderer, PdfA3MinimalCheck(), ReceiptAnnexBuilder(store), ExportStageTimers(SimpleMeterRegistry()))
        val service = PdfExportServiceImplTestFactory.create(
            XmlExportServiceImpl(store),
            reportRenderer,
            store,
            EmbeddedKositValidator().apply { init() },
            basePdfStage,
            outputIntentVerifier = OutputIntentTestKeys.verifier,
            demoWatermarkText = demoWatermarkText,
        )
        service.performExport(invoice, "verapdf-it", outputIntent = outputIntent)
        val key = store.written.keys.first { it.endsWith(".pdf") }
        return store.written.getValue(key)
    }

    private fun record(caseId: String, result: VeraPdfResult, note: String = "") {
        recordedCases.add(Triple(caseId, result, note))
    }

    // ── cases ────────────────────────────────────────────────────────────────

    @Test
    fun `V2-T04 - an invoice is PDF A-3B conformant`() {
        val pdf = exportPdf(invoice("V2-T04-0001"))

        val result = validate(pdf)
        record("basis-rechnung", result)

        assertEquals(
            KositOutcome.VALID, result.outcome,
            "Die Rechnung muss PDF/A-3B-konform sein. Befunde: " +
                result.findings.joinToString("; ") { "${it.ruleId}: ${it.message}" },
        )
    }

    /**
     * Receipt pages are rasterised and appended behind the invoice: the result must stay PDF/A-3B
     * conformant (no foreign fonts or colour spaces), still carry exactly the one embedded XML,
     * and keep conformance when the draft mark is stamped over the receipt page too.
     */
    @Test
    fun `combined document - receipt pages behind the invoice stay PDF A-3B conformant with one embedded file`() {
        val seeded = mapOf("receipt-pool/a.pdf" to foreignPdf("Taxi 12,50 EUR"))
        val receipts = listOf(de.littletrotter.shared.ReceiptDto(fileName = "a.pdf", objectKey = "receipt-pool/a.pdf"))
        val pdf = exportPdf(invoice("RE-ANNEX-0001").copy(receipts = receipts, draft = true), seeded)

        val result = validate(pdf)
        record("kombi-dokument", result)

        assertEquals(
            KositOutcome.VALID, result.outcome,
            "Das Kombi-Dokument muss PDF/A-3B-konform sein. Befunde: " +
                result.findings.joinToString("; ") { "${it.ruleId}: ${it.message}" },
        )
        Loader.loadPDF(pdf).use { document ->
            assertTrue(document.numberOfPages >= 2, "Rechnungsseite(n) plus Belegseite erwartet")
            assertEquals(1, document.documentCatalog.names?.embeddedFiles?.names?.size, "genau eine eingebettete Datei")
            val last = org.apache.pdfbox.text.PDFTextStripper().apply { startPage = document.numberOfPages }.getText(document)
            assertTrue(last.contains("Anlage 1/1 – a.pdf"), "Kopfband der Belegseite: $last")
        }
    }

    // The mark is drawn in the same PDFBox pass that appends the receipt pages, before the ZUGFeRD
    // embedding, so each of the four stamp cases must survive the PDF/A-3 conversion together with
    // receipt pages. The delivered PDF is the one validated, hence one assertion per case.

    private fun assertCombinedConformant(caseId: String, pdf: ByteArray, expectedWatermark: String) {
        val result = validate(pdf)
        record(caseId, result)

        assertEquals(
            KositOutcome.VALID, result.outcome,
            "Kombi-Dokument ($caseId) muss PDF/A-3B-konform sein. Befunde: " +
                result.findings.joinToString("; ") { "${it.ruleId}: ${it.message}" },
        )
        Loader.loadPDF(pdf).use { document ->
            assertEquals(2, document.numberOfPages, "Rechnungsseite plus Belegseite erwartet")
            assertEquals(1, document.documentCatalog.names?.embeddedFiles?.names?.size, "genau eine eingebettete Datei")
            assertTrue(WatermarkText.of(pdf).contains(expectedWatermark), "Wasserzeichen $expectedWatermark fehlt")
        }
    }

    private val combinedSeed = mapOf("receipt-pool/a.pdf" to foreignPdf("Taxi 12,50 EUR"))
    private val combinedReceipts = listOf(de.littletrotter.shared.ReceiptDto(fileName = "a.pdf", objectKey = "receipt-pool/a.pdf"))

    @Test
    fun `combined document with the cancellation mark stays PDF A-3B conformant`() {
        val pdf = exportPdf(invoice("RE-STORNO-ANNEX").copy(receipts = combinedReceipts, cancellation = true), combinedSeed)

        assertCombinedConformant("kombi-storno", pdf, "STORNO")
    }

    @Test
    fun `combined document with the draft mark stays PDF A-3B conformant on both pages`() {
        val pdf = exportPdf(invoice("RE-DRAFT-ANNEX").copy(receipts = combinedReceipts, draft = true), combinedSeed)

        assertCombinedConformant("kombi-entwurf", pdf, "ENTWURF")
    }

    @Test
    fun `combined document with the brand mark stays PDF A-3B conformant`() {
        val pdf = exportPdf(invoice("RE-BRAND-ANNEX").copy(receipts = combinedReceipts), combinedSeed)

        assertCombinedConformant("kombi-marke", pdf, "LittleTrotter")
    }

    @Test
    fun `combined document with only the demo text where a grant covers the seller stays PDF A-3B conformant`() {
        val covered = OutputIntentTestKeys.sellerInvoice(invoice("RE-SUPPORT-ANNEX")).copy(receipts = combinedReceipts)
        val pdf = exportPdf(
            covered, combinedSeed,
            demoWatermarkText = "Little Trotter \u00B7 Demo",
            outputIntent = OutputIntentTestKeys.grantFor(covered),
        )

        assertCombinedConformant("kombi-support-demo", pdf, "LittleTrotter\u00B7Demo")
    }

    /**
     * The draft mark ([PdfWatermarkStamper.DRAFT_MARK]) is one of the
     * three fixed marks every PDF now carries (cancellation/draft/brand — every OTHER case
     * in this IT that carries neither flag therefore already proves the brand mark's conformance
     * implicitly): a post-render, PDF/A-3-aware modification
     * of the already-finished document. This proves stamping the two draft lines still doesn't
     * cost the export its conformance, exercised through [InvoiceDto.draft] and
     * [PdfExportServiceImpl]'s real routing in `completeExport` — the same path a non-finalized
     * invoice's print job reaches, not a hand-built PDF.
     */
    @Test
    fun `spec-draft-watermark - a draft-marked invoice stays PDF A-3B conformant`() {
        val pdf = exportPdf(invoice("DRAFT-MARK-0001").copy(draft = true))

        val result = validate(pdf)
        record("entwurfsmarke", result)

        assertEquals(
            KositOutcome.VALID, result.outcome,
            "Ein Beleg mit Entwurfsmarke muss weiterhin PDF/A-3B-konform sein. Befunde: " +
                result.findings.joinToString("; ") { "${it.ruleId}: ${it.message}" },
        )

        // The two-line stamp only ever appends a content stream and (already-shared) font
        // resource per page, so the embedded ZUGFeRD XML attachment PdfA3MinimalCheck's own
        // `checkAttachment` looks for must still be there afterwards, under its exact filename
        // (`factur-x.xml` or `xrechnung.xml` — this fixture's own `resolveProfile` outcome
        // decides which, not this test).
        Loader.loadPDF(pdf).use { document ->
            val embeddedFiles = document.documentCatalog.names?.embeddedFiles?.names
            assertNotNull(embeddedFiles, "die eingebettete ZUGFeRD-XML-Anlage muss das Stempeln der Entwurfsmarke überstehen")
            assertTrue(
                embeddedFiles!!.keys.any { it == "factur-x.xml" || it == "xrechnung.xml" },
                "erwartete ZUGFeRD/XRechnung-Anlage fehlt nach dem Stempeln der Entwurfsmarke (vorhanden: ${embeddedFiles.keys})",
            )
        }
    }

    /**
     * The cancellation mark ([PdfWatermarkStamper.CANCELLATION_MARK]) is a new primary-line text this
     * project never stamped before; its own conformance must be proven, not inherited from the
     * draft-mark case above (a different mark could, in principle, sit differently against the
     * page geometry or interact differently with the transparency group). Exercised through
     * [InvoiceDto.cancellation] and the real `completeExport` routing, the same path a cancellation document's
     * print job reaches.
     */
    @Test
    fun `ST-5 - a cancellation-marked invoice stays PDF A-3B conformant`() {
        val pdf = exportPdf(invoice("STORNO-MARK-0001").copy(cancellation = true))

        val result = validate(pdf)
        record("storno-marke", result)

        assertEquals(
            KositOutcome.VALID, result.outcome,
            "Ein Beleg mit Storno-Marke muss weiterhin PDF/A-3B-konform sein. Befunde: " +
                result.findings.joinToString("; ") { "${it.ruleId}: ${it.message}" },
        )

        Loader.loadPDF(pdf).use { document ->
            val embeddedFiles = document.documentCatalog.names?.embeddedFiles?.names
            assertNotNull(embeddedFiles, "the embedded ZUGFeRD XML attachment must survive stamping the storno mark")
            assertTrue(
                embeddedFiles!!.keys.any { it == "factur-x.xml" || it == "xrechnung.xml" },
                "erwartete ZUGFeRD/XRechnung-Anlage fehlt nach dem Stempeln der Storno-Marke (vorhanden: ${embeddedFiles.keys})",
            )
        }
    }

    /**
     * The configured demo text adds one more line, with a character (the middle dot) none of
     * the fixed marks uses, to the stamped block. The draft mark carries the most lines (primary,
     * demo, project link), so this is the fullest block a demo deployment ever stamps; its
     * conformance is proven here, not inherited from the cases above.
     */
    @Test
    fun `demo watermark - a draft with the configured demo text stays PDF A-3B conformant`() {
        val pdf = exportPdf(invoice("DEMO-MARK-0001").copy(draft = true), demoWatermarkText = "Little Trotter \u00B7 Demo")

        val result = validate(pdf)
        record("demo-wasserzeichen", result)

        assertEquals(
            KositOutcome.VALID, result.outcome,
            "Ein Beleg mit Demo-Wasserzeichen muss weiterhin PDF/A-3B-konform sein. Befunde: " +
                result.findings.joinToString("; ") { "${it.ruleId}: ${it.message}" },
        )
        Loader.loadPDF(pdf).use { document ->
            val perPage = (1..document.numberOfPages).map { page ->
                val stripper = PDFTextStripper()
                stripper.startPage = page
                stripper.endPage = page
                stripper.getText(document).replace(Regex("\\s+"), "")
            }
            assertTrue(perPage.all { it.contains("LittleTrotter\u00B7Demo") }, "Demo-Text fehlt auf mindestens einer Seite: $perPage")
        }
    }

    // The former cancelled-original conformance case is gone: a cancelled original is
    // never regenerated (the single-generation gate), so its archived PDF simply keeps
    // whichever mark it already carried; there is no separate mark, and therefore no separate
    // conformance case, for it any more.

    /**
     * T7 — the PDF/A part of the B2B export proof, on the same document
     * ([de.littletrotter.xpdf.b2bInvoiceDto]) that the XML and KoSIT proofs use: the captured B2B
     * values, the billing period (BT-73/74) and the line item period (BT-134/135) must not change
     * the document's conformance.
     *
     * Only runs with the veraPDF CLI — without it, the case is skipped, not passed (see
     * [requireEngine]). The always-running, weaker part lives in [PdfA3MinimalCheckTest].
     */
    @Test
    fun `T7 - a B2B invoice with captured profile values stays PDF A-3B conformant`() {
        val pdf = exportPdf(b2bInvoiceDto("T7-B2B-0001"))

        val result = validate(pdf)
        record("b2b-profil", result)

        assertEquals(
            KositOutcome.VALID, result.outcome,
            "Ein B2B-Beleg muss so konform sein wie jeder andere. Befunde: " +
                result.findings.joinToString("; ") { "${it.ruleId}: ${it.message}" },
        )
    }

    @Test
    fun `V2-T07 - a deliberately broken document is INVALID with a named clause`() {
        // A test that cannot fail proves nothing: this document violates PDF/A on purpose (no XMP
        // identification, non-embedded standard font), so a VALID here would mean the engine is
        // not actually checking.
        val broken = foreignPdf("Kein PDF/A: Standardfont, keine XMP-Identifikation")

        val result = validate(broken)
        record("absichtlich-verletzt", result)

        assertEquals(KositOutcome.INVALID, result.outcome, "war: ${result.summary()}")
        assertTrue(result.findings.isNotEmpty(), "eine Verletzung ohne benannten Befund ist unbrauchbar")
        assertNotNull(result.findings.first().ruleId, "jeder Befund trägt seine ISO-Klausel")
        assertTrue(
            result.findings.all { it.stage == "PDFA" && it.validator!!.startsWith("veraPDF ") },
            "Befunde müssen im F1-Vertrag stehen",
        )
    }

    /** V2-T09 — the artifact the release report reads. Written only when the engine actually ran. */
    @AfterAll
    fun writeArtifact() {
        if (recordedCases.isEmpty()) return

        val root = File(System.getProperty("user.dir")).let { if (it.name == "kotlin-xpdf") it.parentFile else it }
        val target = File(root, "docs/compliance-artifacts/verapdf-results.json")
        target.parentFile.mkdirs()

        val out: ObjectNode = mapper.createObjectNode()
        out.put("generatedAt", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        out.put("validator", "veraPDF ${ValidationToolchain.VERAPDF_DEFAULT_VERSION}")
        out.put("profile", VeraPdfReportParser.PROFILE)
        out.put("producedBy", "VeraPdfConformanceIT (kotlin-xpdf)")

        val cases: ArrayNode = out.putArray("cases")
        recordedCases.forEach { (id, result, note) ->
            val node = cases.addObject()
            node.put("id", id)
            node.put("outcome", result.outcome.name)
            node.put("conformant", result.conformant)
            node.put("failedRules", result.failedRuleCount)
            node.put("failedChecks", result.failedCheckCount)
            node.put("summary", result.summary())
            if (note.isNotBlank()) node.put("note", note)
            val findings = node.putArray("findings")
            result.findings.take(20).forEach { f ->
                findings.addObject().apply {
                    put("ruleId", f.ruleId)
                    put("message", f.message)
                }
            }
        }

        target.writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out) + "\n")
    }

    companion object {
        @Container
        @JvmStatic
        val veraPdf: VeraPdfTestResource = VeraPdfTestResource()
    }
}
