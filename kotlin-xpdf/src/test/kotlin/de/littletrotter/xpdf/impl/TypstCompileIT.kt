package de.littletrotter.xpdf.impl

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import de.littletrotter.shared.InvoiceCashDiscountDto
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.Money
import de.littletrotter.shared.time.ClockTimeSource
import de.littletrotter.xpdf.PdfLayoutInspector
import de.littletrotter.xpdf.testsupport.TypstTestResource
import org.apache.pdfbox.Loader
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.graphics.image.PDImage
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.xmpbox.xml.DomXmpParser
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.awt.geom.GeneralPath
import java.awt.geom.Point2D
import java.math.BigDecimal
import java.time.Instant

/**
 * The shipped Typst template compiled by the pinned CLI from the renderer's own `data.json`,
 * checked for the properties the export pipeline depends on before Mustang attaches the
 * Factur-X XML: a PDF/A-3b identification, an output intent, every font embedded, byte-identical
 * output for identical input, and a table header that repeats on every page. Synthetic data only.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TypstCompileIT {

    @Container
    private val typst = TypstTestResource()

    private val creation: Instant = Instant.ofEpochSecond(1_790_000_000L)
    private val renderer = TypstInvoiceRenderer(TypstCompiler(), ClockTimeSource.fixedAt(creation), PaymentQrSetting.OFF)
    private val rendererOn = TypstInvoiceRenderer(TypstCompiler(), ClockTimeSource.fixedAt(creation), PaymentQrSetting.ON)

    private val template: String = renderer.bundledTemplate()

    /** The shipped template's own `data.json` for a synthetic invoice with [rows] plain items. */
    private fun dataJson(rows: Int): String =
        renderer.dataJson(TypstParityFixtures.invoice("TC-2026-%04d".format(rows), (1..rows).map { TypstParityFixtures.plainItem(it) }))

    private fun compileOk(rows: Int): ByteArray {
        val outcome = typst.compile(template, dataJson(rows), creation)
        assertEquals(0, outcome.exitCode, "typst ${typst.installedVersion()} failed:\n${outcome.stderr}")
        return checkNotNull(outcome.pdfBytes)
    }

    private fun compileOk(invoice: InvoiceDto): ByteArray {
        val outcome = typst.compile(template, renderer.dataJson(invoice), creation)
        assertEquals(0, outcome.exitCode, "typst ${typst.installedVersion()} failed:\n${outcome.stderr}")
        return checkNotNull(outcome.pdfBytes)
    }

    private fun <T> withDocument(pdf: ByteArray, block: (PDDocument) -> T): T = Loader.loadPDF(pdf).use(block)

    private fun pageTexts(pdf: ByteArray): List<String> = withDocument(pdf) { document ->
        (1..document.numberOfPages).map { page -> PDFTextStripper().apply { startPage = page; endPage = page }.getText(document) }
    }

    @Test
    fun `the container runs exactly the pinned release`() {
        assertTrue(typst.installedVersion().contains(TypstCompiler.DEFAULT_VERSION), typst.installedVersion())
    }

    @Test
    fun `the template compiles to a PDF-A-3b base document with an output intent and embedded fonts`() {
        val pdf = compileOk(rows = 3)

        withDocument(pdf) { document ->
            val xmp = document.documentCatalog.metadata.exportXMPMetadata().use { DomXmpParser().parse(it) }
            val pdfa = checkNotNull(xmp.pdfaIdentificationSchema) { "no pdfaid schema" }
            assertEquals(3, pdfa.part)
            assertEquals("B", pdfa.conformance)
            assertTrue(document.documentCatalog.outputIntents.isNotEmpty(), "no output intent")
            document.pages.forEach { page ->
                page.resources.fontNames.forEach { name ->
                    val font = page.resources.getFont(name)
                    assertTrue(font.isEmbedded, "font not embedded: ${font.name}")
                }
            }
        }
    }

    @Test
    fun `identical input yields byte-identical output`() {
        assertArrayEquals(compileOk(rows = 5), compileOk(rows = 5))
    }

    @Test
    fun `a long item list paginates and repeats the table header on the following page`() {
        // Enough rows that page 2 still carries items (page 1 holds about 22 rows of 16 pt).
        val pdf = compileOk(rows = 60)

        withDocument(pdf) { document ->
            assertTrue(document.numberOfPages >= 3, "expected pagination, got ${document.numberOfPages} page(s)")
            val secondPage = PDFTextStripper().apply { startPage = 2; endPage = 2 }.getText(document)
            assertTrue(secondPage.contains("Bezeichnung"), "header not repeated:\n$secondPage")
            assertTrue(secondPage.contains("Seite 2 / ${document.numberOfPages}"), "page footer missing:\n$secondPage")
        }
    }

    @Test
    fun `the payment block and the notes are never separated by a page break`() {
        // Every row count up to a comfortable two-page invoice: whichever page the notes land
        // on, the payment block is on it too, and the note and the tax note are on the same page.
        // With a summary this short, a page break inside it is never legitimate.
        for (rows in 1..30) {
            val invoice = TypstParityFixtures.invoice(
                "TC-2026-%04d".format(rows), (1..rows).map { TypstParityFixtures.plainItem(it) },
                reverseCharge = true, footerNote = "Vielen Dank für Ihren Auftrag.",
            )
            val outcome = typst.compile(template, renderer.dataJson(invoice), creation)
            assertEquals(0, outcome.exitCode, "typst failed at $rows rows:\n${outcome.stderr}")
            withDocument(checkNotNull(outcome.pdfBytes)) { document ->
                val pages = (1..document.numberOfPages).map { page ->
                    PDFTextStripper().apply { startPage = page; endPage = page }.getText(document)
                }
                val paymentPages = pages.indices.filter { "ZAHLUNGSBEDINGUNGEN" in pages[it] }
                val notePages = pages.indices.filter { "Vielen Dank für Ihren Auftrag." in pages[it] }
                val taxNotePages = pages.indices.filter { "STEUERHINWEIS" in pages[it] }
                assertEquals(listOf(pages.lastIndex), paymentPages, "payment block at $rows rows")
                assertEquals(listOf(pages.lastIndex), notePages, "note at $rows rows")
                assertEquals(listOf(pages.lastIndex), taxNotePages, "tax note at $rows rows")
            }
        }
    }

    @Test
    fun `a template error is a non-zero exit with source-located diagnostics`() {
        val outcome = typst.compile("#let data = json(\"data.json\")\n#data.missing_field", dataJson(rows = 1), creation)

        assertTrue(outcome.exitCode != 0)
        assertTrue(outcome.stderr.contains("main.typ"), outcome.stderr)
        assertEquals(null, outcome.pdfBytes)
    }

    // ── GiroCode: rendered, scanned and decoded ────────────────────────────────────

    private val qrInvoice = TypstParityFixtures.invoice("TC-QR-0001", (1..2).map { TypstParityFixtures.plainItem(it) })

    @Test
    fun `setting ON prints a GiroCode that decodes to the EPC payload the renderer built`() {
        val request = rendererOn.compileRequest(qrInvoice)
        val outcome = typst.compile(request.templateSource, request.dataJson, request.creationTimestamp, request.files)
        assertEquals(0, outcome.exitCode, "typst ${typst.installedVersion()} failed:\n${outcome.stderr}")
        val pdf = checkNotNull(outcome.pdfBytes)

        withDocument(pdf) { document ->
            val pages = (0 until document.numberOfPages).map { index ->
                PDFTextStripper().apply { startPage = index + 1; endPage = index + 1 }.getText(document)
            }
            val qrPageIndex = pages.indexOfFirst { it.contains("GiroCode") }
            assertTrue(qrPageIndex >= 0, "no page carries the GiroCode caption")

            val image = PDFRenderer(document).renderImageWithDPI(qrPageIndex, 200f)
            val bitmap = BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(image)))
            val result = MultiFormatReader().decode(bitmap, mapOf(DecodeHintType.TRY_HARDER to true))

            val expected = EpcQrPayload.build(
                EpcQrInput(
                    name = TypstParityFixtures.sender().name,
                    iban = TypstParityFixtures.sender().iban,
                    bic = TypstParityFixtures.sender().bic,
                    amount = qrInvoice.totals.grossAmount,
                    currency = qrInvoice.totals.currency,
                    remittance = qrInvoice.invoiceNumber.value,
                ),
            )
            assertEquals(expected, result.text)
        }
    }

    @Test
    fun `setting OFF prints no GiroCode caption and differs from the ON output, while OFF stays byte-identical across runs`() {
        val offRequest = renderer.compileRequest(qrInvoice)
        val onRequest = rendererOn.compileRequest(qrInvoice)

        val offOutcome1 = typst.compile(offRequest.templateSource, offRequest.dataJson, offRequest.creationTimestamp, offRequest.files)
        val offOutcome2 = typst.compile(offRequest.templateSource, offRequest.dataJson, offRequest.creationTimestamp, offRequest.files)
        val onOutcome = typst.compile(onRequest.templateSource, onRequest.dataJson, onRequest.creationTimestamp, onRequest.files)
        assertEquals(0, offOutcome1.exitCode, offOutcome1.stderr)
        assertEquals(0, offOutcome2.exitCode, offOutcome2.stderr)
        assertEquals(0, onOutcome.exitCode, onOutcome.stderr)
        val offPdf1 = checkNotNull(offOutcome1.pdfBytes)
        val offPdf2 = checkNotNull(offOutcome2.pdfBytes)
        val onPdf = checkNotNull(onOutcome.pdfBytes)

        val offText = withDocument(offPdf1) { PDFTextStripper().getText(it) }
        assertFalse(offText.contains("GiroCode"), "OFF must not print the GiroCode caption")
        assertArrayEquals(offPdf1, offPdf2, "OFF must stay byte-identical across runs (spec §11)")
        assertFalse(offPdf1.contentEquals(onPdf), "ON and OFF must not compile to the same PDF")
    }

    // ── tax-breakdown frame closes flush under its last row ────────────────────────

    /** An invoice whose items carry [rates] distinct VAT rates, so `taxBreakdown.rows` has one
     *  row per rate and `present` is true (more than one distinct rate). */
    private fun taxBreakdownPdf(rates: List<String>): ByteArray {
        val items = rates.mapIndexed { index, rate -> TypstParityFixtures.plainItem(index + 1, rate) }
        val invoice = TypstParityFixtures.invoice("TC-TAX-%04d".format(rates.size), items)
        val json = renderer.dataJson(invoice)
        val outcome = typst.compile(template, json, creation)
        assertEquals(0, outcome.exitCode, "typst ${typst.installedVersion()} failed:\n${outcome.stderr}")
        return checkNotNull(outcome.pdfBytes)
    }

    /** The tax-breakdown frame and the totals frame below it: both 270 pt wide, the tax-breakdown
     *  one always the topmost of the two. Collected from the page's vector paths (`re` + stroke),
     *  not guessed from the template source, per the module's measurement rule. */
    private fun taxAndTotalsFrames(pdf: ByteArray): Pair<PdfLayoutInspector.RectF, PdfLayoutInspector.RectF> =
        withDocument(pdf) { document ->
            val page = document.getPage(0)
            val extractor = RectPathExtractor(page)
            extractor.processPage(page)
            val candidates = extractor.rects.filter { it.width in 269f..271f && it.height in 30f..90f }.sortedBy { it.y }
            check(candidates.size >= 2) { "expected the tax-breakdown and totals frames, found ${candidates.size}: $candidates" }
            candidates[0] to candidates[1]
        }

    private fun lastTaxRowBottom(pdf: ByteArray, frame: PdfLayoutInspector.RectF): Float {
        val blocks = PdfLayoutInspector().inspect(pdf).pages[0].textBlocks
        val insideFrame = blocks.filter { block ->
            block.x >= frame.x - 1f && block.x <= frame.x + frame.width + 1f &&
                block.y >= frame.y + 1f && block.y <= frame.y + frame.height + 5f
        }
        check(insideFrame.isNotEmpty()) { "no text found inside the tax-breakdown frame $frame" }
        return insideFrame.maxOf { it.y + it.height }
    }

    private fun assertFrameFlushUnderLastRow(rates: List<String>) {
        val pdf = taxBreakdownPdf(rates)
        val (taxFrame, totalsFrame) = taxAndTotalsFrames(pdf)
        val lastRowBottom = lastTaxRowBottom(pdf, taxFrame)
        val frameBottom = taxFrame.y + taxFrame.height
        val gapToLastRow = frameBottom - lastRowBottom
        // The content-sized card (`card-inset` = 6 pt) closes at the last row's bottom plus its own
        // bottom padding, never above the text and never more than that one inset below it.
        assertTrue(
            gapToLastRow in -0.5f..(TAX_CARD_INSET_PT + 0.5f),
            "${rates.size} rates: frame bottom=$frameBottom, last row bottom=$lastRowBottom, gap=$gapToLastRow " +
                "(want 0..${TAX_CARD_INSET_PT}pt)",
        )
        assertTrue(
            frameBottom < totalsFrame.y,
            "${rates.size} rates: tax-breakdown frame bottom=$frameBottom must sit above the totals frame top=${totalsFrame.y}",
        )
    }

    @Test
    fun `the tax-breakdown frame closes flush under the last row with two VAT rates`() {
        assertFrameFlushUnderLastRow(listOf("19.00", "7.00"))
    }

    @Test
    fun `the tax-breakdown frame closes flush under the last row with four VAT rates`() {
        assertFrameFlushUnderLastRow(listOf("19.00", "7.00", "5.00", "10.00"))
    }

    // ── content-sized cards under wrapping values ───────────────────────────────────

    /** Every 270 pt-wide summary/tax card on page 1 closes at or below its own last text block —
     *  a wrapped value grows the card, so the frame drawn around it must still enclose it. */
    private fun assertCardsEncloseTheirText(pdf: ByteArray) {
        val cards = withDocument(pdf) { document ->
            val page = document.getPage(0)
            val extractor = RectPathExtractor(page)
            extractor.processPage(page)
            extractor.rects.filter { it.width in 269f..271f }
        }
        check(cards.isNotEmpty()) { "expected at least one summary card on page 1, found none" }
        val blocks = PdfLayoutInspector().inspect(pdf).pages[0].textBlocks
        cards.forEach { card ->
            val inside = blocks.filter { block ->
                block.x >= card.x - 1f && block.x <= card.x + card.width + 1f &&
                    block.y >= card.y + 1f && block.y <= card.y + card.height + 5f
            }
            check(inside.isNotEmpty()) { "no text found inside the card at y=${card.y}" }
            val lastBottom = inside.maxOf { it.y + it.height }
            assertTrue(
                card.y + card.height >= lastBottom - 0.5f,
                "card at y=${card.y} closes above its own last text block: frame bottom=${card.y + card.height}, text bottom=$lastBottom",
            )
        }
    }

    /** The subject line's text block on page 1, found by its own text. */
    private fun subjectBlock(pdf: ByteArray, subject: String): PdfLayoutInspector.TextBlock =
        PdfLayoutInspector().inspect(pdf).pages[0].textBlocks.first { it.text.trim() == subject }

    /** The subject line keeps `s3` clear of the head's info-card stack; the stack is the text right of
     *  the address column above the subject, and its last block's bottom approximates the card's. */
    private fun assertSubjectClearOfHeadStack(pdf: ByteArray, subject: String) {
        val subjectTop = subjectBlock(pdf, subject).y - TITLE_ASCENT_PT
        val stackBlocks = PdfLayoutInspector().inspect(pdf).pages[0].textBlocks
            .filter { it.x >= HEAD_STACK_X_MIN_PT && it.y < subjectTop }
        check(stackBlocks.isNotEmpty()) { "no text found in the head info-card column" }
        val stackBottom = stackBlocks.maxOf { it.y + it.height }
        assertTrue(
            subjectTop >= stackBottom + SUBJECT_STACK_GAP_PT - 1f,
            "subject line starts at y=$subjectTop, must stay ${SUBJECT_STACK_GAP_PT}pt below the info cards ending at y=$stackBottom",
        )
        assertTrue(subjectTop >= SUBJECT_MIN_TOP_PT - 1f, "subject line starts at y=$subjectTop, above its DIN 5008 position")
    }

    /** No text block of the flowed body — everything above the running footer's fixed band — may
     *  cross the page's right content edge; the footer's own columns are a pre-fitted, documented
     *  exception (a 60-char bank name may still overflow one of them, tracked separately). */
    private fun assertNoBodyTextRightOfMargin(pdf: ByteArray) {
        PdfLayoutInspector().inspect(pdf).pages.forEach { page ->
            page.textBlocks.filter { it.y < FOOTER_TOP_PT }.forEach { block ->
                assertTrue(
                    block.x + block.width <= BODY_RIGHT_EDGE_PT + 0.5f,
                    "p${page.pageIndex + 1}: text '${block.text.take(40)}' ends at x=${block.x + block.width}pt, " +
                        "right of the body edge (${BODY_RIGHT_EDGE_PT}pt)",
                )
            }
        }
    }

    @Test
    fun `wrapping values in the info cards and the payment card stay inside their cards and the page body`() {
        val customerNumber40 = "KD-".padEnd(40, '7')
        val buyerReference60 = "LEITWEG-".padEnd(60, '9')
        val bankName60 = "Volksbank Filiale ".padEnd(60, 'X')
        val base = TypstParityFixtures.invoice(
            "TC-WRAP-0001", listOf(TypstParityFixtures.plainItem(1), TypstParityFixtures.plainItem(2, "7.00")),
        )
        val invoice = base.copy(
            recipient = checkNotNull(base.recipient).copy(customerNumber = customerNumber40),
            references = base.references.copy(buyerReference = buyerReference60, orderReference = "ORD-2026-0099"),
            senderProfile = checkNotNull(base.senderProfile).copy(bankName = bankName60),
        )
        val model = InvoiceReportModelBuilder().build(invoice)
        assertEquals(8, model.rinfo.rows.size, "fixture must fill 8 invoiceInfo rows: ${model.rinfo.rows}")
        assertEquals(2, model.pinfo.rows.size, "fixture must fill 2 senderInfo (tax ID) rows: ${model.pinfo.rows}")

        val pdf = compileOk(invoice)

        val report = PdfLayoutInspector().inspect(pdf)
        assertTrue(report.violations.isEmpty(), report.violations.joinToString { "${it.rule}@p${it.page}: ${it.description}" })
        assertNoBodyTextRightOfMargin(pdf)
        assertCardsEncloseTheirText(pdf)
        assertSubjectClearOfHeadStack(pdf, model.header.betreff)
    }

    private fun noteBlocks(invoice: InvoiceDto): List<PdfLayoutInspector.TextBlock> =
        PdfLayoutInspector().inspect(compileOk(invoice)).pages[0].textBlocks

    private fun PdfLayoutInspector.TextBlock.bottom(): Float = y + height

    @Test
    fun `a consumer note alone prints the note box with only that paragraph`() {
        val invoice = TypstParityFixtures.invoice(
            "TC-NOTE-0001", listOf(TypstParityFixtures.plainItem(1)), consumerNote = "Hinweis fuer Privatkunden.",
        )
        val blocks = noteBlocks(invoice)
        val title = blocks.first { it.text.trim() == "HINWEIS" }
        val consumer = blocks.first { it.text.contains("Hinweis fuer Privatkunden.") }
        assertEquals(title.y, consumer.y, 2f)
        assertTrue(consumer.x > title.x, "paragraph sits right of the title column")
        assertTrue(consumer.bottom() < FOOTER_TOP_PT, "paragraph ends above the running footer")
    }

    @Test
    fun `seller note and consumer note are two paragraphs of one box, the consumer paragraph below and clear of the tax note`() {
        val invoice = TypstParityFixtures.invoice(
            "TC-NOTE-0002", listOf(TypstParityFixtures.plainItem(1)), reverseCharge = true,
            footerNote = "Vielen Dank fuer Ihren Auftrag.", consumerNote = "Hinweis fuer Privatkunden.",
        )
        val blocks = noteBlocks(invoice)
        val title = blocks.first { it.text.trim() == "HINWEIS" }
        val seller = blocks.first { it.text.contains("Vielen Dank fuer Ihren Auftrag.") }
        val consumer = blocks.first { it.text.contains("Hinweis fuer Privatkunden.") }
        val taxTitle = blocks.first { it.y > consumer.y && it.x == title.x && it.text.isNotBlank() }
        assertEquals(title.y, seller.y, 2f)
        assertEquals(seller.x, consumer.x, 0.5f)
        assertTrue(consumer.y >= seller.bottom(), "consumer paragraph starts below the seller paragraph")
        assertTrue(consumer.bottom() <= taxTitle.y, "no overlap with the following tax note")
        assertTrue(consumer.bottom() < FOOTER_TOP_PT)
    }

    @Test
    fun `the address prints as one closed block on a 16 pt pitch, a foreign one with its country as the last line`() {
        val base = TypstParityFixtures.invoice("TC-ADDR-0001", listOf(TypstParityFixtures.plainItem(1)))
        val foreign = base.copy(recipient = checkNotNull(base.recipient).copy(postalCode = "1010", city = "Wien", country = "AT"))

        val blocks = PdfLayoutInspector().inspect(compileOk(foreign)).pages[0].textBlocks
        fun baseline(text: String) = blocks.first { it.text.trim() == text }.y
        val lines = listOf("Nekar Supermarkt eG", "Marktplatz 1", "1010 Wien", "ÖSTERREICH").map(::baseline)

        lines.zipWithNext { upper, lower -> assertEquals(ADDRESS_LINE_PITCH_PT, lower - upper, 0.5f) }
    }

    @Test
    fun `with short info cards the subject line sits at its DIN 5008 position below the address field`() {
        val invoice = TypstParityFixtures.invoice("TC-SUBJ-0001", listOf(TypstParityFixtures.plainItem(1)))
        val subject = InvoiceReportModelBuilder().build(invoice).header.betreff

        val subjectTop = subjectBlock(compileOk(invoice), subject).y - TITLE_ASCENT_PT

        assertEquals(SUBJECT_MIN_TOP_PT, subjectTop, 1f)
    }

    // ── VAT column and TRIP purpose ───────────────────────────────────────────────

    @Test
    fun `a VAT rate prints as a single text block in the tax column, never split from its percent sign`() {
        val invoice = TypstParityFixtures.invoice(
            "TC-VAT-0001", listOf(TypstParityFixtures.plainItem(1, "19.00"), TypstParityFixtures.plainItem(2, "7.00")),
        )
        // The formatted percentage is Kotlin's own value, read from the same model the template's
        // `data.json` is built from — never guessed or hardcoded here.
        val expectedVat = InvoiceReportModelBuilder().build(invoice).items.first().vatPct
        val pdf = compileOk(invoice)
        val blocks = PdfLayoutInspector().inspect(pdf).pages[0].textBlocks
        // The item table's own VAT column sits at the table's right edge (`col-vat-min-plain` =
        // 48 pt, table right edge 538 pt); the tax-breakdown card below prints the same rate text
        // a second time, but inside its own (narrower, further left) rate column — x >= 480 keeps
        // only the item table's cell, so the two never get counted together.
        val inItemVatColumn = blocks.filter { it.x >= 480f }
        val matches = inItemVatColumn.filter { it.text.trim() == expectedVat }
        assertEquals(
            1, matches.size,
            "expected exactly one text block reading \"$expectedVat\" in the item table's VAT column, found: " +
                inItemVatColumn.filter { "%" in it.text }.map { it.text },
        )
        assertTrue(inItemVatColumn.none { it.text.trim() == "%" }, "the percent sign must never wrap onto its own line")
    }

    @Test
    fun `a TRIP item's purpose text prints exactly once, folded into its trip subline`() {
        val invoice = TypstParityFixtures.invoice("TC-TRIP-0001", InvoiceLayoutFixtures.job701Items())
        val pdf = compileOk(invoice)
        val occurrences = Regex("Reisefahrt").findAll(pageTexts(pdf).joinToString("")).count()
        assertEquals(
            1, occurrences,
            "\"Reisefahrt\" (the TRIP item's itemDescription) must print once, folded into the trip subline — " +
                "the plain description cell suppresses it a second time whenever a trip subline is present",
        )
    }

    // ── pagination with a full summary block ──────────────────────────────────────

    @Test
    fun `four VAT rates, 42 items, free text after the items and a footer note fill exactly three pages`() {
        val rates = listOf("19.00", "7.00", "5.00", "10.00")
        val items = (1..42).map { TypstParityFixtures.plainItem(it, rates[(it - 1) % rates.size]) }
        // Long enough that it does not fit page 2 next to the tax breakdown and totals, short
        // enough it still finishes there — pushing the payment card and notes onto their own
        // (third) page rather than splitting the free text itself across two pages.
        val freeText = (1..8).joinToString("\n") { i ->
            "Absatz $i: Diese Vereinbarung beschreibt Leistungsumfang, Abnahme und Gewährleistung in aller " +
                "Ausführlichkeit, damit der Freitext die Kopfzone der ersten Seite verlässt."
        }
        val invoice = TypstParityFixtures.invoice(
            "TC-PAGES-0001", items, footerNote = "Vielen Dank für Ihren Auftrag.", freeText = freeText,
        )
        val pdf = compileOk(invoice)

        withDocument(pdf) { document -> assertEquals(3, document.numberOfPages, "expected exactly 3 pages") }
        val pages = pageTexts(pdf)
        pages.filter { "Wartungsleistung Position" in it }.forEach { page ->
            assertTrue(page.contains("Bezeichnung"), "table header not repeated on a page carrying items:\n$page")
        }
        val paymentPage = pages.indexOfFirst { "ZAHLUNGSBEDINGUNGEN" in it }
        val notePage = pages.indexOfFirst { "Vielen Dank für Ihren Auftrag." in it }
        assertTrue(
            paymentPage >= 0 && paymentPage == notePage,
            "payment card (p${paymentPage + 1}) and the note (p${notePage + 1}) must share a page",
        )
    }

    // ── Cash discount (Skonto) sentence in the payment card ───────────────────────

    @Test
    fun `a computed cash discount prints its sentence on the payment page`() {
        val base = TypstParityFixtures.invoice("TC-SKONTO-0001", (1..2).map { TypstParityFixtures.plainItem(it) })
        val invoice = base.copy(
            cashDiscount = InvoiceCashDiscountDto(BigDecimal("2"), 7, "2026-10-05", Money.of("12.09"), Money.of("592.57")),
        )
        val pdf = compileOk(invoice)

        val pages = pageTexts(pdf)
        val paymentPage = pages.firstOrNull { "ZAHLUNGSBEDINGUNGEN" in it }
        assertTrue(paymentPage != null, "no payment page found")
        assertTrue(
            paymentPage!!.contains("Bei Zahlung bis"),
            "cash discount sentence missing from the payment page:\n$paymentPage",
        )
    }

    // ── Sender name, return line and footer contact fitting ──────────────────────

    @Test
    fun `an 80-character sender name fits into two letterhead lines above the address window`() {
        val name80 = "Handwerksbetrieb fuer Sanitaer- und Heizungstechnik Nordwest Bremen Nord eG GmbH"
        val base = TypstParityFixtures.invoice("TC-NAME-0001", listOf(TypstParityFixtures.plainItem(1)))
        val invoice = base.copy(senderProfile = checkNotNull(base.senderProfile).copy(name = name80))

        val pdf = compileOk(invoice)
        val report = PdfLayoutInspector().inspect(pdf)
        assertTrue(report.violations.isEmpty(), report.violations.joinToString { "${it.rule}@p${it.page}: ${it.description}" })

        // Only the rare, multi-syllable words: short ones ("Bremen", "GmbH", "Nord") also occur
        // elsewhere on the page (address, footer, register block) and would match there too.
        val nameWords = name80.split(" ").filter { it.length > 7 }.toSet()
        // The return line below repeats the same name as part of its own text (name · street ·
        // city), so restrict to blocks above it — the letterhead's own two printed lines.
        val nameBlocks = report.pages[0].textBlocks.filter { block ->
            block.fontSize < 16f && block.y < RETURN_LINE_Y_PT && nameWords.any { word -> block.text.contains(word) }
        }
        assertTrue(nameBlocks.isNotEmpty(), "no text block matched the sender name")
        val lines = nameBlocks.map { it.y }.distinct()
        assertTrue(lines.size <= 2, "sender name must span at most two lines, found y values $lines")
        nameBlocks.forEach { block ->
            assertTrue(
                block.x >= NAME_BOX_X_MIN_PT - 0.5f,
                "block '${block.text}' starts at x=${block.x}pt, left of the name box (${NAME_BOX_X_MIN_PT}pt)",
            )
            assertTrue(
                block.x + block.width <= NAME_BOX_X_MIN_PT + NAME_BOX_WIDTH_PT + 0.5f,
                "block '${block.text}' ends at x=${block.x + block.width}pt, right of the 241pt name box",
            )
            assertTrue(
                block.y + block.height <= RETURN_LINE_Y_PT + 0.5f,
                "block '${block.text}' ends at y=${block.y + block.height}pt, at or below the return line (${RETURN_LINE_Y_PT}pt)",
            )
        }
    }

    @Test
    fun `an overlong footer contact line shrinks and is shortened with an ellipsis inside its column`() {
        val email70 = "rechnung.abteilung.buchhaltung.zentrale@schmidt-sanitaertechnik-bremen.example".take(70)
        val base = TypstParityFixtures.invoice("TC-CONTACT-0001", listOf(TypstParityFixtures.plainItem(1)))
        val invoice = base.copy(senderProfile = checkNotNull(base.senderProfile).copy(email = email70))

        val pdf = compileOk(invoice)
        val report = PdfLayoutInspector().inspect(pdf)
        assertTrue(report.violations.isEmpty(), report.violations.joinToString { "${it.rule}@p${it.page}: ${it.description}" })

        val prefix = email70.take(10)
        val candidates = report.pages[0].textBlocks.filter { it.y >= FOOTER_TOP_PT && it.text.startsWith(prefix) }
        assertEquals(1, candidates.size, "expected exactly one contact block starting with '$prefix', found ${candidates.map { it.text }}")
        val block = candidates.single()
        assertTrue(block.width <= CONTACT_COLUMN_WIDTH_PT + 0.5f, "contact block width ${block.width}pt exceeds the 135pt column")
        assertTrue(block.text.endsWith("…"), "contact block '${block.text}' does not end with an ellipsis")
        assertEquals(6f, block.fontSize, 0.05f, "contact block font size ${block.fontSize}pt, want 6pt")
    }

    @Test
    fun `an overlong return address line shortens the name, keeps street and city, and stays above the address rule`() {
        val longName = "Musterfirma Handwerksbetrieb fuer Sanitaertechnik und Heizungsinstallationen GmbH & Co KG"
        val base = TypstParityFixtures.invoice("TC-RETURN-0001", listOf(TypstParityFixtures.plainItem(1)))
        val sender = checkNotNull(base.senderProfile).copy(name = longName)
        val invoice = base.copy(senderProfile = sender)
        val returnLine = listOf(sender.name.orEmpty(), sender.street.orEmpty(), "${sender.postalCode.orEmpty()} ${sender.city.orEmpty()}".trim())
            .filter { it.isNotBlank() }.joinToString(" · ")
        assertTrue(returnLine.length >= 120, "fixture return line must be >=120 chars, was ${returnLine.length}: $returnLine")

        val pdf = compileOk(invoice)
        val report = PdfLayoutInspector().inspect(pdf)
        assertTrue(report.violations.isEmpty(), report.violations.joinToString { "${it.rule}@p${it.page}: ${it.description}" })

        val prefix = longName.take(10)
        val candidates = report.pages[0].textBlocks.filter { it.y in 120f..140f && it.text.startsWith(prefix) }
        assertEquals(1, candidates.size, "expected exactly one return-line block starting with '$prefix', found ${candidates.map { it.text }}")
        val block = candidates.single()
        assertTrue(block.width <= NAME_BOX_WIDTH_PT + 0.5f, "return line block width ${block.width}pt exceeds 241.5pt")
        val address = " · ${sender.street} · ${sender.postalCode} ${sender.city}"
        assertTrue(block.text.endsWith("…$address"), "return line block '${block.text}' must shorten the name and end with '$address'")
        assertTrue(
            block.y + block.height <= ADDRESS_RULE_Y_PT + 0.5f,
            "return line block bottom ${block.y + block.height}pt must stay above the address rule (${ADDRESS_RULE_Y_PT}pt)",
        )
        assertEquals(6f, block.fontSize, 0.05f, "return line block font size ${block.fontSize}pt, want 6pt")
    }

    @Test
    fun `a return line whose address alone overflows is cut from the end, still one line inside the window width`() {
        val base = TypstParityFixtures.invoice("TC-RETURN-0002", listOf(TypstParityFixtures.plainItem(1)))
        val longStreet = "Industriegebiet Nord Gewerbepark ".repeat(6).trim()
        val invoice = base.copy(senderProfile = checkNotNull(base.senderProfile).copy(street = longStreet))

        val blocks = PdfLayoutInspector().inspect(compileOk(invoice)).pages[0].textBlocks
            .filter { it.y in 120f..140f && it.x < NAME_BOX_X_MIN_PT + NAME_BOX_WIDTH_PT }

        assertEquals(1, blocks.size, "expected one return-line block, found ${blocks.map { it.text }}")
        val block = blocks.single()
        assertTrue(block.text.endsWith("…"), "return line block '${block.text}' must be cut from the end")
        assertTrue(block.width <= NAME_BOX_WIDTH_PT + 0.5f, "return line block width ${block.width}pt exceeds 241.5pt")
    }

    // ── Design system (phase 2): scale, colour and alignment contracts ─────────────

    private fun qrCaptionBlocks(invoice: InvoiceDto): List<PdfLayoutInspector.TextBlock> {
        val request = rendererOn.compileRequest(invoice)
        val outcome = typst.compile(request.templateSource, request.dataJson, request.creationTimestamp, request.files)
        assertEquals(0, outcome.exitCode, "typst ${typst.installedVersion()} failed:\n${outcome.stderr}")
        val pdf = checkNotNull(outcome.pdfBytes)
        val caption = InvoiceReportModelBuilder().build(invoice).payment.labelQr
        return PdfLayoutInspector().inspect(pdf).pages[0].textBlocks.filter { it.text.trim() == caption }
    }

    @Test
    fun `the GiroCode caption prints as exactly one text block in German and in English`() {
        val de = TypstParityFixtures.invoice("TC-QR-DE-0001", (1..2).map { TypstParityFixtures.plainItem(it) })
        val en = TypstParityFixtures.invoice("TC-QR-EN-0001", (1..2).map { TypstParityFixtures.plainItem(it) }, language = "en")

        val deBlocks = qrCaptionBlocks(de)
        assertEquals(1, deBlocks.size, "German GiroCode caption must print as one text block, found: $deBlocks")

        val enBlocks = qrCaptionBlocks(en)
        assertEquals(1, enBlocks.size, "English GiroCode caption must print as one text block, found: $enBlocks")
    }

    @Test
    fun `the note and the tax note bodies start at the same x position`() {
        val invoice = TypstParityFixtures.invoice(
            "TC-NOTES-0001", (1..2).map { TypstParityFixtures.plainItem(it) },
            reverseCharge = true, footerNote = "Vielen Dank für Ihren Auftrag.",
        )
        val pdf = compileOk(invoice)
        val blocks = PdfLayoutInspector().inspect(pdf).pages.last().textBlocks

        val noteBlock = blocks.first { it.text.contains("Vielen Dank für Ihren Auftrag.") }
        val taxNoteBlock = blocks.first { it.text.contains("Steuerschuldnerschaft") }
        assertEquals(
            noteBlock.x, taxNoteBlock.x, 0.5f,
            "note body starts at x=${noteBlock.x}pt, tax-note body at x=${taxNoteBlock.x}pt — they must align",
        )
    }

    /** Collects the bounding box and fill colour of every rectangular path a page fills (not
     *  merely strokes) — used to confirm `teal` never fills an area outside the header bar and the
     *  item table's header row, whatever their exact position (letterhead height, column count and
     *  page count are all free to change without this test caring). */
    private class FillColorRectExtractor(private val page: PDPage) : PDFGraphicsStreamEngine(page) {
        val fills = mutableListOf<Pair<PdfLayoutInspector.RectF, Int>>()
        private var path = GeneralPath()

        override fun appendRectangle(p0: Point2D, p1: Point2D, p2: Point2D, p3: Point2D) {
            path.moveTo(p0.x, p0.y)
            path.lineTo(p1.x, p1.y)
            path.lineTo(p2.x, p2.y)
            path.lineTo(p3.x, p3.y)
            path.closePath()
        }

        override fun strokePath() {
            path = GeneralPath()
        }

        override fun fillPath(windingRule: Int) = recordAndReset()

        override fun fillAndStrokePath(windingRule: Int) = recordAndReset()

        override fun clip(windingRule: Int) {
            path = GeneralPath()
        }

        override fun moveTo(x: Float, y: Float) {
            path.moveTo(x.toDouble(), y.toDouble())
        }

        override fun lineTo(x: Float, y: Float) {
            path.lineTo(x.toDouble(), y.toDouble())
        }

        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            path.curveTo(x1.toDouble(), y1.toDouble(), x2.toDouble(), y2.toDouble(), x3.toDouble(), y3.toDouble())
        }

        override fun getCurrentPoint(): Point2D = path.currentPoint ?: Point2D.Float(0f, 0f)

        override fun closePath() {
            path.closePath()
        }

        override fun endPath() {
            path = GeneralPath()
        }

        override fun shadingFill(shadingName: COSName) = Unit

        override fun drawImage(pdImage: PDImage) = Unit

        private fun recordAndReset() {
            val bounds = path.bounds2D
            if (bounds.width > 0.1 && bounds.height > 0.1) {
                val pageHeight = page.mediaBox.height
                val rgb = graphicsState.nonStrokingColor.toRGB()
                fills.add(
                    PdfLayoutInspector.RectF(
                        x = bounds.x.toFloat(),
                        y = (pageHeight - bounds.y - bounds.height).toFloat(),
                        width = bounds.width.toFloat(),
                        height = bounds.height.toFloat(),
                    ) to rgb,
                )
            }
            path = GeneralPath()
        }
    }

    @Test
    fun `teal fill never appears outside the header bar and the item table's header row`() {
        val invoice = TypstParityFixtures.invoice("TC-TEAL-0001", (1..3).map { TypstParityFixtures.plainItem(it) })
        val pdf = compileOk(invoice)

        val tealRects = withDocument(pdf) { document ->
            val page = document.getPage(0)
            val extractor = FillColorRectExtractor(page)
            extractor.processPage(page)
            extractor.fills.filter { (_, rgb) -> rgb == TEAL_RGB }.map { (rect, _) -> rect }
        }
        check(tealRects.isNotEmpty()) { "expected at least one teal fill (the letterhead bar), found none" }
        // Every teal-filled rectangle sits at one of exactly two y-positions: the letterhead bar
        // (one rect spanning the full body width) or the item table's header row (one rect per
        // column, all sharing the header row's own y) — never a third band.
        val bands = tealRects.map { Math.round(it.y) }.toSet()
        assertTrue(bands.size <= 2, "teal fill appears at ${bands.size} distinct y-positions, want at most 2: $bands")
    }

    /** Collects the bounding box of every filled or stroked rectangular path on a page, in the
     *  same top-down coordinate system [PdfLayoutInspector.TextBlock] uses, by tracking the
     *  content stream's path-construction and paint operators directly (PDFBox has no higher-level
     *  API for a drawn rectangle's geometry). */
    private class RectPathExtractor(private val page: PDPage) : PDFGraphicsStreamEngine(page) {
        val rects = mutableListOf<PdfLayoutInspector.RectF>()
        private var path = GeneralPath()

        override fun appendRectangle(p0: Point2D, p1: Point2D, p2: Point2D, p3: Point2D) {
            path.moveTo(p0.x, p0.y)
            path.lineTo(p1.x, p1.y)
            path.lineTo(p2.x, p2.y)
            path.lineTo(p3.x, p3.y)
            path.closePath()
        }

        override fun strokePath() = recordAndReset()

        override fun fillPath(windingRule: Int) = recordAndReset()

        override fun fillAndStrokePath(windingRule: Int) = recordAndReset()

        override fun clip(windingRule: Int) {
            path = GeneralPath()
        }

        override fun moveTo(x: Float, y: Float) {
            path.moveTo(x.toDouble(), y.toDouble())
        }

        override fun lineTo(x: Float, y: Float) {
            path.lineTo(x.toDouble(), y.toDouble())
        }

        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            path.curveTo(x1.toDouble(), y1.toDouble(), x2.toDouble(), y2.toDouble(), x3.toDouble(), y3.toDouble())
        }

        override fun getCurrentPoint(): Point2D = path.currentPoint ?: Point2D.Float(0f, 0f)

        override fun closePath() {
            path.closePath()
        }

        override fun endPath() {
            path = GeneralPath()
        }

        override fun shadingFill(shadingName: COSName) = Unit

        override fun drawImage(pdImage: PDImage) = Unit

        private fun recordAndReset() {
            val bounds = path.bounds2D
            if (bounds.width > 0.1 && bounds.height > 0.1) {
                val pageHeight = page.mediaBox.height
                rects.add(PdfLayoutInspector.RectF(
                    x = bounds.x.toFloat(),
                    y = (pageHeight - bounds.y - bounds.height).toFloat(),
                    width = bounds.width.toFloat(),
                    height = bounds.height.toFloat(),
                ))
            }
            path = GeneralPath()
        }
    }

    private companion object {
        /** The template's `teal` token (`#16A085`) as a packed RGB int, to spot a fill outside
         *  its two sanctioned places (header bar, table header row). */
        const val TEAL_RGB = 0x16A085

        /** `card-inset`: the padding a content-sized card keeps on every side, so the tax-breakdown/
         *  totals frame bottom sits at most this far below its last row's text. */
        const val TAX_CARD_INSET_PT = 6f

        /** Page body: 57 pt left margin, `body-width` 481 pt wide. */
        const val BODY_RIGHT_EDGE_PT = 57f + 481f

        /** `footer-top`: the running footer's fixed band start, already a page-absolute y (it is
         *  drawn in the page foreground, not the body flow) — a text block above it belongs to the
         *  flowed body. */

        /** The head's info-card stack sits at local x = 265 pt inside the `head` block, whose own
         *  origin is the page's left margin (`head` is the first thing in the page-1 flow). */
        const val HEAD_STACK_X_MIN_PT = 57f + 265f

        /** DIN 5008 form B subject position, two blank lines below the 90 mm address field: local
         *  y = 228 pt inside `head`, whose origin is the page's top margin. */
        const val SUBJECT_MIN_TOP_PT = 51f + 228f

        /** `s3`: the clearance the subject line keeps below the info-card stack when that pushes it down. */
        const val SUBJECT_STACK_GAP_PT = 12f

        /** Ascent of the 14 pt subject line (0.905 em): text blocks report their baseline. */
        const val FOOTER_TOP_PT = 764f
        const val TITLE_ASCENT_PT = 14f * 0.905f

        /** The sender name/return line box: local x = 0 inside `head`, so page-absolute x is the
         *  57 pt left margin; `wbox`/`fit-line` width is 241 pt. */
        const val NAME_BOX_X_MIN_PT = 57f
        const val NAME_BOX_WIDTH_PT = 241f

        /** `at(0pt, 77pt, ...)` for the return line — page-absolute y once the 51 pt top margin
         *  is added, the bound a wrapped/fitted line must stay above. */
        const val RETURN_LINE_Y_PT = 51f + 77f

        /** `at(0pt, 90pt, line(...))` — the address-window rule the return line must clear. */
        const val ADDRESS_RULE_Y_PT = 51f + 90f

        /** The recipient lines' pitch in the address window (DIN 5008, no blank line before the city). */
        const val ADDRESS_LINE_PITCH_PT = 16f

        /** Footer contact column width: the register column starts at local x = 135 pt. */
        const val CONTACT_COLUMN_WIDTH_PT = 135f
    }
}
