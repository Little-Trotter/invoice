package de.littletrotter.xpdf.impl

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/** [PdfWatermarkStamper] unit tests: all three marks (cancellation,
 *  draft, brand) land on every page in both document languages, with the existing content
 *  preserved and the secondary line rendering smaller than the primary. The typography rule
 *  (PO 2026-09-04) additionally pins that the mark constants themselves store plain words only and
 *  that the letter-spaced look is real `Tc` typography applied at render time, never a printed
 *  formatting hint. PDF/A-3 conformance survival after stamping is proven separately, with the
 *  real veraPDF CLI, in [VeraPdfConformanceIT]. */
class PdfWatermarkStamperTest {

    /** A real, multi-page PDF with actual content on every page (each page numbered in its own
     *  text so a stripped page can be told apart from the others). */
    private fun multiPagePdf(pages: Int): ByteArray {
        PDDocument().use { doc ->
            repeat(pages) { index ->
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                    cs.newLineAtOffset(50f, 700f)
                    cs.showText("Original page content ${index + 1}")
                    cs.endText()
                }
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    private fun textOfPage(pdfBytes: ByteArray, pageNumberOneBased: Int): String =
        Loader.loadPDF(pdfBytes).use { doc ->
            val stripper = PDFTextStripper()
            stripper.startPage = pageNumberOneBased
            stripper.endPage = pageNumberOneBased
            stripper.getText(doc)
        }

    /** [PDFTextStripper]'s line-grouping heuristic keys on each glyph's vertical position, which
     *  a diagonal (~45°) run defeats — every rotated character lands on its own "line", so the
     *  extracted text comes back interleaved with stray whitespace/newlines even though the
     *  glyphs themselves are intact. Collapsing all whitespace before a `contains` check is the
     *  correct fix here, not a workaround for a stamping bug: [PdfWatermarkStamper] draws the
     *  watermark as one unbroken `showText` run either way. */
    private fun collapseWhitespace(text: String): String = text.replace(Regex("\\s+"), "")

    /** A single blank page (no content stream of its own) so [extractTfFontSizes] sees only the
     *  `Tf` operators [PdfWatermarkStamper] itself emits, not also whatever original content a
     *  fixture like [multiPagePdf] would carry (a page's content streams all parse as one logical
     *  stream, so an original `Tf` would otherwise be indistinguishable from the stamp's own). */
    private fun blankPagePdf(mediaBox: PDRectangle = PDRectangle.LETTER): ByteArray = PDDocument().use { doc ->
        doc.addPage(PDPage(mediaBox))
        val out = ByteArrayOutputStream()
        doc.save(out)
        out.toByteArray()
    }

    /** Reads back the real `Tf` (set-font-size) operators [PdfWatermarkStamper] emits for a page's
     *  content stream — the exact operator [PDPageContentStream.setFont] writes — in document
     *  order: the operand immediately preceding a `Tf` operator token is that operator's font
     *  size. The primary line(s) always come first, the secondary link line last. */
    private fun extractTfFontSizes(pdfBytes: ByteArray, pageIndex: Int): List<Float> =
        Loader.loadPDF(pdfBytes).use { doc ->
            val tokens = org.apache.pdfbox.pdfparser.PDFStreamParser(doc.getPage(pageIndex)).parse()
            val sizes = mutableListOf<Float>()
            for (i in tokens.indices) {
                val token = tokens[i]
                if (token is org.apache.pdfbox.contentstream.operator.Operator && token.name == "Tf") {
                    val size = (tokens[i - 1] as? org.apache.pdfbox.cos.COSNumber)?.floatValue()
                    if (size != null) sizes.add(size)
                }
            }
            sizes
        }

    /** The typography rule — reads back the real `Tc` (character-spacing) operators
     *  [PdfWatermarkStamper] emits for a page's content stream, in document order: one value per
     *  `beginText`/`showText`/`endText` block, the same way [extractTfFontSizes] reads `Tf`. A
     *  line with no letter-spacing still emits `Tc 0` explicitly (the stamper resets it for every
     *  line, never relying on a graphics-state default carried over from the previous line). */
    private fun extractTcValues(pdfBytes: ByteArray, pageIndex: Int): List<Float> =
        Loader.loadPDF(pdfBytes).use { doc ->
            val tokens = org.apache.pdfbox.pdfparser.PDFStreamParser(doc.getPage(pageIndex)).parse()
            val values = mutableListOf<Float>()
            for (i in tokens.indices) {
                val token = tokens[i]
                if (token is org.apache.pdfbox.contentstream.operator.Operator && token.name == "Tc") {
                    val value = (tokens[i - 1] as? org.apache.pdfbox.cos.COSNumber)?.floatValue()
                    if (value != null) values.add(value)
                }
            }
            values
        }

    // ── all three marks land on every page, in both document languages ───────────────────

    private data class MarkCase(
        val label: String,
        val mark: PdfWatermarkStamper.Mark,
        val documentLanguage: String?,
        val expectedPrimaries: List<String>,
        /** PO decision 2026-09-05 — false for the cancellation rows only; see
         *  [PdfWatermarkStamper.Mark.showsAttribution]. */
        val expectsProjectLink: Boolean = true,
    )

    private val markCases = listOf(
        MarkCase("storno/de", PdfWatermarkStamper.CANCELLATION_MARK, "de", listOf("STORNO"), expectsProjectLink = false),
        MarkCase("storno/en", PdfWatermarkStamper.CANCELLATION_MARK, "en", listOf("REVERSAL"), expectsProjectLink = false),
        MarkCase("draft/de", PdfWatermarkStamper.DRAFT_MARK, "de", listOf("ENTWURF")),
        MarkCase("draft/en", PdfWatermarkStamper.DRAFT_MARK, "en", listOf("DRAFT")),
        MarkCase("brand/de", PdfWatermarkStamper.BRAND_MARK, "de", listOf("LittleTrotter")),
        MarkCase("brand/en", PdfWatermarkStamper.BRAND_MARK, "en", listOf("LittleTrotter")),
    )

    @Test
    fun `stampMark adds the resolved primary and secondary line to every page while keeping the original content`() {
        markCases.forEach { case ->
            val original = multiPagePdf(3)

            val stamped = PdfWatermarkStamper.stampMark(original, case.mark, case.documentLanguage)

            Loader.loadPDF(stamped).use { doc ->
                assertEquals(3, doc.numberOfPages, "${case.label}: stamping must not add or remove pages")
            }
            for (page in 1..3) {
                val text = collapseWhitespace(textOfPage(stamped, page))
                case.expectedPrimaries.forEach { expectedPrimary ->
                    assertTrue(
                        text.contains(expectedPrimary),
                        "${case.label}: page $page must carry the primary line '$expectedPrimary', got: $text",
                    )
                }
                if (case.expectsProjectLink) {
                    assertTrue(
                        text.contains("little-trotter.github.io"),
                        "${case.label}: page $page must carry the secondary project-link line, got: $text",
                    )
                    assertFalse(
                        text.contains("github.com"),
                        "${case.label}: page $page must not carry the old github.com wording, got: $text",
                    )
                } else {
                    assertFalse(
                        text.contains("github"),
                        "${case.label}: page $page must NOT carry the project link, got: $text",
                    )
                }
                assertTrue(
                    textOfPage(stamped, page).contains("Original page content $page"),
                    "${case.label}: page $page's original content must survive stamping",
                )
            }
        }
    }

    @Test
    fun `boundary - a mark with no primary lines at all still stamps without throwing, at the default font size`() {
        // fontSizeFor's auto-fit divides by the widest primary line's width; an empty `texts` list
        // (no Mark in production ever has one — de/en always carry at least one line) is the only
        // way widthAt1000 can be <= 0, exercising the guard that falls back to a fixed 36pt size
        // instead of a division-by-zero-shaped NaN/Infinity result.
        val emptyMark = PdfWatermarkStamper.Mark(de = emptyList(), en = emptyList(), letterSpaced = false, showsAttribution = false)

        val stamped = PdfWatermarkStamper.stampMark(multiPagePdf(1), emptyMark, "de")

        Loader.loadPDF(stamped).use { doc ->
            assertEquals(1, doc.numberOfPages)
        }
        assertTrue(
            textOfPage(stamped, 1).contains("Original page content 1"),
            "original content must survive stamping even with an empty mark",
        )
    }

    @Test
    fun `boundary - loadFont throws when the bundled font is missing from the classpath`() {
        PDDocument().use { doc ->
            val ex = assertThrows(IllegalStateException::class.java) {
                PdfWatermarkStamper.loadFont(doc, resourceLookup = { null })
            }
            assertTrue(ex.message!!.contains("Font resource"), "was: ${ex.message}")
        }
    }

    @Test
    fun `stampMark's null or unrecognised language falls back to German`() {
        listOf(null, "fr", "").forEach { language ->
            val stamped = PdfWatermarkStamper.stampMark(multiPagePdf(1), PdfWatermarkStamper.CANCELLATION_MARK, language)
            val text = collapseWhitespace(textOfPage(stamped, 1))
            assertTrue(text.contains("STORNO"), "language='$language' must fall back to German, got: $text")
            assertFalse(text.contains("REVERSAL"), "language='$language' must not resolve to English, got: $text")
        }
    }

    /**
     * PO decision 2026-09-05 — "Das Wasserzeichen für CANCELLATION hat nie den Link auf das GitHub-Repo."
     * A Stornorechnung annuls another document; the project link is advertising and has no place
     * on it. Asserted in BOTH document languages and against the bare substring "github", so a
     * changed URL cannot slip the link back in unnoticed.
     *
     * The neighbouring rows are asserted here too: this is a per-mark rule, not a global switch,
     * and a fix that silently dropped the link everywhere would otherwise pass.
     */
    @Test
    fun `the cancellation mark never carries the project link, while draft and brand still do`() {
        listOf("de", "en", null).forEach { language ->
            val cancellation = collapseWhitespace(
                textOfPage(PdfWatermarkStamper.stampMark(multiPagePdf(1), PdfWatermarkStamper.CANCELLATION_MARK, language), 1),
            )
            assertFalse(cancellation.contains("github"), "storno/$language must not carry the project link, got: $cancellation")
            assertFalse(
                cancellation.contains("littletrotter", ignoreCase = true),
                "storno/$language must not carry the project link in any spelling, got: $cancellation",
            )

            listOf(PdfWatermarkStamper.DRAFT_MARK, PdfWatermarkStamper.BRAND_MARK).forEach { mark ->
                val other = collapseWhitespace(
                    textOfPage(PdfWatermarkStamper.stampMark(multiPagePdf(1), mark, language), 1),
                )
                assertTrue(
                    other.contains("little-trotter.github.io"),
                    "the link must stay on the other marks, got: $other",
                )
            }
        }
    }

    /** PO 31.08.2026 — the draft mark carries exactly the word DRAFT/ENTWURF and the project link,
     *  and nothing else: no "Little Trotter" product name, no legal wording. */
    @Test
    fun `the draft mark carries no wording beyond DRAFT ENTWURF and the project link`() {
        val stamped = PdfWatermarkStamper.stampMark(multiPagePdf(1), PdfWatermarkStamper.DRAFT_MARK, "en")

        val text = collapseWhitespace(textOfPage(stamped, 1))
        assertTrue(text.contains("DRAFT"), "must still carry the primary draft line, got: $text")
        assertFalse(
            text.contains("LittleTrotter", ignoreCase = true),
            "the draft mark must never carry the product name, got: $text",
        )
    }

    @Test
    fun `stampMark on a single page document works and stays a valid PDF`() {
        val original = multiPagePdf(1)

        val stamped = PdfWatermarkStamper.stampMark(original, PdfWatermarkStamper.DRAFT_MARK, "de")

        assertTrue(stamped.isNotEmpty())
        Loader.loadPDF(stamped).use { doc ->
            assertEquals(1, doc.numberOfPages)
        }
        val text = collapseWhitespace(textOfPage(stamped, 1))
        assertTrue(text.contains("ENTWURF"))
        assertTrue(text.contains("little-trotter.github.io"))
    }

    @Test
    fun `stampMark's secondary line renders smaller than the primary line`() {
        val stamped = PdfWatermarkStamper.stampMark(blankPagePdf(), PdfWatermarkStamper.BRAND_MARK, "de")

        val fontSizes = extractTfFontSizes(stamped, pageIndex = 0)
        assertEquals(2, fontSizes.size, "expected exactly two Tf (set-font-size) operators on the stamped page")
        val (primarySize, secondarySize) = fontSizes
        assertTrue(
            secondarySize < primarySize,
            "secondary font size ($secondarySize) must be smaller than the primary ($primarySize)",
        )
        val ratio = secondarySize / primarySize
        assertTrue(
            ratio in 0.35..0.55,
            "secondary/primary font-size ratio ($ratio) must be roughly the spec's 40-50 % band",
        )
    }

    // ── PDF/A-3 — the secondary line is a real, PDF/A-conformant clickable link ──────────────

    /** [PDActionURI] (and every other value behind an indirect PDF reference) resolves lazily, so
     *  the whole read -- including [PDAnnotationLink.getAction] -- must happen while [doc] is
     *  still open; a caller that closed the document first before reading the action would always
     *  see it resolve to nothing. */
    private fun <T> withLinkAnnotationsOf(pdfBytes: ByteArray, pageIndex: Int, block: (List<PDAnnotationLink>) -> T): T =
        Loader.loadPDF(pdfBytes).use { doc ->
            block(doc.getPage(pageIndex).annotations.filterIsInstance<PDAnnotationLink>())
        }

    @Test
    fun `stampMark adds exactly one Link annotation to the project URL on every page that carries the secondary line`() {
        listOf(PdfWatermarkStamper.BRAND_MARK, PdfWatermarkStamper.DRAFT_MARK).forEach { mark ->
            val stamped = PdfWatermarkStamper.stampMark(multiPagePdf(3), mark, "de")

            for (pageIndex in 0..2) {
                withLinkAnnotationsOf(stamped, pageIndex) { links ->
                    assertEquals(
                        1, links.size,
                        "${mark.de}: page ${pageIndex + 1} must carry exactly one Link annotation, got: $links",
                    )

                    val link = links.single()
                    val action = link.action as? PDActionURI
                    assertEquals(
                        PdfWatermarkStamper.SECONDARY_URL, action?.uri,
                        "${mark.de}: the Link annotation's URI must be the project URL",
                    )
                    assertTrue(link.isPrinted, "${mark.de}: PDF/A-3 requires the Print flag set")
                    assertFalse(link.isHidden, "${mark.de}: PDF/A-3 requires the Hidden flag clear")
                    assertFalse(link.isNoView, "${mark.de}: PDF/A-3 requires the NoView flag clear")
                }
            }
        }
    }

    @Test
    fun `the cancellation mark leaves every page without any annotation`() {
        listOf("de", "en").forEach { language ->
            val stamped = PdfWatermarkStamper.stampMark(multiPagePdf(3), PdfWatermarkStamper.CANCELLATION_MARK, language)

            Loader.loadPDF(stamped).use { doc ->
                for (pageIndex in 0..2) {
                    val annotations = doc.getPage(pageIndex).annotations
                    assertTrue(
                        annotations.isEmpty(),
                        "storno/$language: page ${pageIndex + 1} must carry no annotations, got: $annotations",
                    )
                }
            }
        }
    }

    @Test
    fun `the link annotation's rectangle covers the drawn glyphs, not the whole page`() {
        val stamped = PdfWatermarkStamper.stampMark(blankPagePdf(), PdfWatermarkStamper.BRAND_MARK, "de")
        val box = PDRectangle.LETTER

        withLinkAnnotationsOf(stamped, pageIndex = 0) { links ->
            val rect = requireNotNull(links.single().rectangle) { "the Link annotation must carry a Rect" }

            assertTrue(rect.width > 0f && rect.height > 0f, "the link rectangle must have a positive area, got: $rect")
            assertTrue(
                rect.width < box.width * 0.9f && rect.height < box.height * 0.9f,
                "the link rectangle must be sized to the text, not the page, got: $rect vs page $box",
            )
            assertTrue(
                box.contains(rect.lowerLeftX, rect.lowerLeftY) && box.contains(rect.upperRightX, rect.upperRightY),
                "the link rectangle must lie on the page, got: $rect vs page $box",
            )

            val rectCenterX = rect.lowerLeftX + rect.width / 2f
            val rectCenterY = rect.lowerLeftY + rect.height / 2f
            assertTrue(
                Math.abs(rectCenterX - box.width / 2f) < box.width * 0.25f &&
                    Math.abs(rectCenterY - box.height / 2f) < box.height * 0.25f,
                "the link rectangle must sit near the centred watermark block, got center " +
                    "($rectCenterX, $rectCenterY) on page $box",
            )
        }
    }

    // ── legibility floor for the widest remaining mark ────────────────────────────────────────

    /** The spec's own floor, re-anchored after the former widest mark (the cancelled-original mark)
     *  was removed: on A4 portrait, whichever of the two remaining letter-spaced marks (cancellation/draft)
     *  renders widest ([fontSizeFor][PdfWatermarkStamper] auto-fits off the widest primary line,
     *  so a wider mark simply renders smaller — the smaller resulting font size identifies the
     *  wider mark without hardcoding which German word happens to be wider) must stay at least
     *  40 % of the brand mark's size on the same page geometry, or a reader simply cannot make it
     *  out. */
    @Test
    fun `RS-2 - the widest remaining mark stays at least 40 percent of the brand mark's size on A4 portrait`() {
        val a4 = blankPagePdf(PDRectangle.A4)

        val brandSize = extractTfFontSizes(
            PdfWatermarkStamper.stampMark(a4, PdfWatermarkStamper.BRAND_MARK, "de"),
            pageIndex = 0,
        ).first()
        val widestMarkSize = listOf(PdfWatermarkStamper.CANCELLATION_MARK, PdfWatermarkStamper.DRAFT_MARK)
            .map { mark -> extractTfFontSizes(PdfWatermarkStamper.stampMark(a4, mark, "de"), pageIndex = 0).first() }
            .min()

        val floor = brandSize * 0.4f
        assertTrue(
            widestMarkSize >= floor,
            "the widest remaining mark's primary font size ($widestMarkSize) must be at least 40 % " +
                "of the brand mark's ($brandSize -> floor $floor) on A4 portrait, or it becomes unreadable",
        )
    }

    // ── typography rule (PO 2026-09-04) — plain-word constants, letter spacing is real Tc ──────

    /** The historical model encoded *gesperrte Schrift* as literal spaces inside the constant
     *  (`"S T O R N O"`) and printed "GESPERRT" as if it were a word. This fixes that: every mark
     *  constant stores its plain word(s) only, with no internal single-letter spacing and no
     *  formatting-hint token anywhere in it. */
    @Test
    fun `RS-2 - mark constants store plain words only, never a letter-spaced literal or a formatting-hint token`() {
        val allLines = listOf(
            PdfWatermarkStamper.CANCELLATION_MARK.de, PdfWatermarkStamper.CANCELLATION_MARK.en,
            PdfWatermarkStamper.DRAFT_MARK.de, PdfWatermarkStamper.DRAFT_MARK.en,
            PdfWatermarkStamper.BRAND_MARK.de, PdfWatermarkStamper.BRAND_MARK.en,
        ).flatten()

        assertEquals(listOf("STORNO"), PdfWatermarkStamper.CANCELLATION_MARK.de)
        assertEquals(listOf("REVERSAL"), PdfWatermarkStamper.CANCELLATION_MARK.en)
        assertEquals(listOf("ENTWURF"), PdfWatermarkStamper.DRAFT_MARK.de)
        assertEquals(listOf("DRAFT"), PdfWatermarkStamper.DRAFT_MARK.en)
        assertEquals(listOf("Little Trotter"), PdfWatermarkStamper.BRAND_MARK.de)
        assertEquals(listOf("Little Trotter"), PdfWatermarkStamper.BRAND_MARK.en)

        allLines.forEach { line ->
            assertFalse(line.contains("GESPERRT"), "'$line' must never carry the formatting-hint word GESPERRT")
            assertFalse(line.contains("\n"), "'$line' must never carry a real line-feed character")
            assertFalse(line.contains("\\n"), "'$line' must never carry a literal backslash-n")
            assertFalse(
                // A run of 3+ standalone single-letter "words" (word-boundary-delimited, so this
                // does not false-positive on ordinary multi-letter words like "Little Trotter",
                // which never contains a lone single-letter word) is exactly the old
                // "S T O R N O"-style literal-space encoding of gesperrte Schrift.
                Regex("\\b(?:[A-Za-z]\\s){2,}[A-Za-z]\\b").containsMatchIn(line),
                "'$line' looks letter-spaced (single letters separated by literal spaces) -- gesperrte Schrift " +
                    "must be applied at render time via Tc, never baked into the constant",
            )
        }
    }

    /** DRAFT is [PdfWatermarkStamper.Mark.letterSpaced] and still carries the project link: its
     *  primary line must carry a positive `Tc` character-spacing value, the secondary line must
     *  not (`Tc 0`, explicitly reset, not merely absent). */
    @Test
    fun `RS-2 - the draft mark applies positive character spacing to the primary line only`() {
        val stamped = PdfWatermarkStamper.stampMark(blankPagePdf(), PdfWatermarkStamper.DRAFT_MARK, "de")
        val tcValues = extractTcValues(stamped, pageIndex = 0)
        assertEquals(2, tcValues.size, "expected exactly two Tc operators (primary + secondary line)")
        val (primaryTc, secondaryTc) = tcValues
        assertTrue(primaryTc > 0f, "the letter-spaced primary line's Tc ($primaryTc) must be positive")
        assertEquals(0f, secondaryTc, "the secondary project-link line must never be letter-spaced")
    }

    /**
     * CANCELLATION is letter-spaced like DRAFT, but since the PO decision of 2026-09-05 it has no
     * secondary line at all — so exactly ONE text run reaches the page, not two. Asserted on the
     * operator counts rather than only on the extracted text: an empty second line would still be
     * invisible in the text layer while occupying its slot in the centred block and pushing the
     * mark off-centre.
     */
    @Test
    fun `RS-2 - the cancellation mark draws a single, letter-spaced line and nothing below it`() {
        val stamped = PdfWatermarkStamper.stampMark(blankPagePdf(), PdfWatermarkStamper.CANCELLATION_MARK, "de")

        val tcValues = extractTcValues(stamped, pageIndex = 0)
        assertEquals(1, tcValues.size, "the storno mark must draw exactly one text run, got Tc values: $tcValues")
        assertTrue(tcValues.single() > 0f, "the storno line stays letter-spaced (Tc ${tcValues.single()})")
        assertEquals(
            1, extractTfFontSizes(stamped, pageIndex = 0).size,
            "exactly one Tf operator — a second one would mean a line is still being reserved",
        )
    }

    /** [PdfWatermarkStamper.BRAND_MARK] stays un-spaced, as before: both its primary and
     *  secondary line carry `Tc 0`. */
    @Test
    fun `RS-2 - the brand mark applies no character spacing to either line`() {
        val stamped = PdfWatermarkStamper.stampMark(blankPagePdf(), PdfWatermarkStamper.BRAND_MARK, "de")
        val tcValues = extractTcValues(stamped, pageIndex = 0)
        assertEquals(listOf(0f, 0f), tcValues, "the brand mark's primary and secondary line must both be un-spaced")
    }

    /** A repo-wide-in-spirit check at the unit level: no stamped mark's extracted page
     *  text ever contains a formatting-hint token, in either document language. */
    @Test
    fun `RS-2 - no stamped mark ever prints a formatting-hint token as document content`() {
        markCases.forEach { case ->
            val stamped = PdfWatermarkStamper.stampMark(multiPagePdf(1), case.mark, case.documentLanguage)
            val rawText = textOfPage(stamped, 1)
            assertFalse(rawText.contains("GESPERRT"), "${case.label}: GESPERRT must never appear as printed content, got: $rawText")
            assertFalse(rawText.contains("\\n"), "${case.label}: a literal backslash-n must never appear as printed content, got: $rawText")
            assertFalse(
                rawText.contains("linefeed", ignoreCase = true),
                "${case.label}: the word 'linefeed' must never appear as printed content, got: $rawText",
            )
        }
    }

    // ── the configured demo text ─────────────────────────────────────────────────────────

    private val demoText = "Little Trotter \u00B7 Demo"

    @Test
    fun `an unset or blank demo text leaves the mark exactly as it is without one`() {
        val original = multiPagePdf(2)
        val reference = PdfWatermarkStamper.stampMark(original, PdfWatermarkStamper.DRAFT_MARK, "de")

        listOf(null, "", "   ").forEach { unset ->
            val stamped = PdfWatermarkStamper.stampMark(original, PdfWatermarkStamper.DRAFT_MARK, "de", unset)
            for (pageIndex in 0..1) {
                assertEquals(extractTfFontSizes(reference, pageIndex), extractTfFontSizes(stamped, pageIndex), "demo='$unset'")
                assertEquals(textOfPage(reference, pageIndex + 1), textOfPage(stamped, pageIndex + 1), "demo='$unset'")
            }
            assertSame(original, PdfWatermarkStamper.stampDemoText(original, unset), "demo='$unset' must return the input untouched")
        }
    }

    @Test
    fun `a demo text joins every mark as its own line on every page, between primary line and link`() {
        listOf(PdfWatermarkStamper.CANCELLATION_MARK, PdfWatermarkStamper.DRAFT_MARK, PdfWatermarkStamper.BRAND_MARK).forEach { mark ->
            val stamped = PdfWatermarkStamper.stampMark(multiPagePdf(3), mark, "de", demoText)

            for (pageIndex in 0..2) {
                val text = collapseWhitespace(textOfPage(stamped, pageIndex + 1))
                assertTrue(text.contains("LittleTrotter\u00B7Demo"), "${mark.de}: page ${pageIndex + 1} lacks the demo text: $text")
                assertTrue(text.contains("Originalpagecontent${pageIndex + 1}"), "${mark.de}: original content lost")

                // drop(1): the fixture's own page text sets its font first, ahead of the stamp
                val sizes = extractTfFontSizes(stamped, pageIndex).drop(1)
                val expectedLines = 2 + if (mark.showsAttribution) 1 else 0
                assertEquals(expectedLines, sizes.size, "${mark.de}: primary + demo (+ link) lines, got $sizes")
                assertTrue(sizes[1] < sizes[0], "${mark.de}: the demo line is smaller than the primary line, got $sizes")
                withLinkAnnotationsOf(stamped, pageIndex) { links ->
                    assertEquals(if (mark.showsAttribution) 1 else 0, links.size, "${mark.de}: link count unchanged by the demo line")
                }
            }
        }
    }

    @Test
    fun `stampDemoText draws the trimmed demo text alone on every page, without a link`() {
        val stamped = PdfWatermarkStamper.stampDemoText(multiPagePdf(3), "  $demoText  ")

        Loader.loadPDF(stamped).use { doc ->
            for (pageIndex in 0..2) {
                assertTrue(doc.getPage(pageIndex).annotations.isEmpty(), "page ${pageIndex + 1} must carry no annotation")
            }
        }
        for (pageIndex in 0..2) {
            // drop(1): the fixture's own page text sets its font first, ahead of the stamp
            assertEquals(1, extractTfFontSizes(stamped, pageIndex).drop(1).size, "one line only on page ${pageIndex + 1}")
            assertEquals(listOf(0f), extractTcValues(stamped, pageIndex), "the demo text is never letter-spaced")
        }
        assertEquals("LittleTrotter\u00B7Demo".repeat(3), WatermarkText.of(stamped))
    }
}
