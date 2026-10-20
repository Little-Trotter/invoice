package de.littletrotter.xpdf.impl

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import org.apache.pdfbox.util.Matrix
import java.awt.Color
import java.io.ByteArrayOutputStream
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Resolves the three marks per document language — stamps a diagonal, half-transparent
 * two-line mark (one primary line plus the project-link line, one centered rotated block) onto
 * every page of an already-finished PDF, as a pure post-render step: self-contained,
 * engine-agnostic (Mustang and the Factur-X engine both hand [PdfExportServiceImpl.completeExport]
 * ordinary finished PDF bytes by the time the stamp runs — that single call site is the only
 * caller), and it runs last, after the ZUGFeRD/Factur-X XML embedding, so the mark sits on the
 * exact document a customer receives.
 *
 * EVERY generated PDF carries exactly one of three fixed marks, first match wins ([Mark]):
 * the cancellation document itself ([CANCELLATION_MARK]), a non-finalized invoice ([DRAFT_MARK]), or the brand
 * mark ([BRAND_MARK] — "Little Trotter" large and diagonal, the project link smaller below)
 * for everything else. A cancelled original is never regenerated (the single-generation gate), so
 * its archived PDF simply keeps whichever mark it already carried when it was issued — there is no
 * separate "cancelled original" mark. [PdfExportServiceImpl.applyWatermark] resolves which row
 * applies; this class only draws whichever [Mark] it is given. Two of the three follow the
 * invoice's own [de.littletrotter.shared.InvoiceMetaDto.documentLanguage] rather than the UI
 * locale — see [Mark] — the brand mark is a product name and is therefore the one row that is
 * never translated. All texts are PO-mandated verbatim document content, not translatable UI
 * labels — plain constants, no i18n, no configuration.
 *
 * The one configurable addition is the deployment-wide demo text
 * (`littletrotter.pdf.watermark.text`, see [stampMark]'s `demoText`): a demo deployment must make
 * every PDF it hands out recognisable as demo output, whichever of the three marks applies. It is
 * drawn as one more line of the same rotated block (below the primary line(s), above the project
 * link, at the project link's size), never as a second overlapping stamp, so the mark table above
 * stays untouched. Unset or blank, the output is byte-for-byte what it was without the feature.
 * Where no mark applies at all, [stampDemoText] draws the demo text alone.
 *
 * The draft mark carries no wording beyond the word DRAFT/ENTWURF itself and the
 * project link: no "Little Trotter" branding, no legal reference. The § 14 UStG hint a
 * non-finalized document needs lives in UI messages (owned elsewhere), never in this stamp.
 *
 * Typography rule (PO 2026-09-04) — a mark constant stores its plain word(s) only
 * (`"CANCELLATION"`, not `"S T O R N O"`); the letter-spaced look (*gesperrte Schrift*) is applied here,
 * at render time, as real PDF typography via the `Tc` (character-spacing) text-state operator
 * ([PDPageContentStream.setCharacterSpacing]) — never by encoding spaces into the constant, which
 * would print a formatting instruction as document content. [Mark.letterSpaced] decides which
 * marks get it: [CANCELLATION_MARK] and [DRAFT_MARK] do, [BRAND_MARK] stays un-spaced, as before. The
 * secondary project-link line is never letter-spaced either way. See [charSpacingFor]/
 * [textWidth] for how that spacing folds into both the per-line width used to center the block
 * and the auto-fit sizing in [fontSizeFor].
 *
 * Embeds Liberation Sans Regular (full embed, no subset) rather than relying on a standard font;
 * a non-embedded font would violate PDF/A-3 on an already PDF/A-3-checked document.
 *
 * Transparency uses a [PDExtendedGraphicsState] constant alpha, not a lighter solid color:
 * PDF/A-3 (unlike PDF/A-1) explicitly permits transparency groups — `VeraPdfConformanceIT`'s
 * stamped-document cases prove this stays conformant for this project's own documents, this
 * class does not merely assume the spec allows it.
 */
object PdfWatermarkStamper {

    private const val ANGLE_DEGREES = 45.0
    private const val NON_STROKING_ALPHA = 0.35f
    private val WATERMARK_GRAY = Color(128, 128, 128)

    /** spec-draft-watermark — the secondary line's font size is a fixed fraction of the primary
     *  line's own size (PO: "≈ 40–50 %"), not sized independently against the page — this is what
     *  keeps the two lines visually paired as one block instead of two unrelated stamps. */
    private const val SECONDARY_FONT_SCALE = 0.45f

    /** spec-draft-watermark — the vertical gap between consecutive lines' baselines, in the same
     *  unrotated-text-space units as font size (points before rotation), a small fraction of the
     *  primary line's own size so the block reads as tightly paired, not as stray lines. */
    private const val LINE_GAP_SCALE = 0.3f

    /** [fontSizeFor] sizes the widest primary line to this multiple of the page's shorter side. */
    private const val TARGET_WIDTH_RATIO = 1.4f

    /** PDFBox/AFM glyph widths are expressed in 1000ths of the font's em size. */
    private const val FONT_METRIC_UNITS_PER_EM = 1000f

    /** Fallback size when every primary text is empty (zero measured width) — never reachable with
     *  a real mark, kept only so [fontSizeFor] cannot divide by zero. */
    private const val FALLBACK_FONT_SIZE_PT = 36f

    private const val MIN_FONT_SIZE_PT = 18f
    private const val MAX_FONT_SIZE_PT = 72f

    /** The secondary line's project link, identical wherever it appears; only the primary
     *  line ([Mark]) varies by row and, for two of the three rows, by language. [SECONDARY_TEXT] is
     *  the display text drawn on the page (no scheme, so it reads as a project reference rather
     *  than a raw address); [SECONDARY_URL] is the actual click target, wired up as a
     *  [PDAnnotationLink] with a [PDActionURI] in [stampPage] so the printed line is clickable in
     *  any PDF viewer, not just legible.
     *
     *  Not on every mark: [Mark.showsAttribution] is `false` for [CANCELLATION_MARK] (PO decision
     *  2026-09-05), so a Stornorechnung carries the word alone and no link, no annotation. */
    const val SECONDARY_TEXT: String = "little-trotter.github.io"

    /** See [SECONDARY_TEXT]. */
    const val SECONDARY_URL: String = "https://little-trotter.github.io"

    /** The configuration key of the optional demo text (env `LITTLETROTTER_PDF_WATERMARK_TEXT`);
     *  read by the two pipelines that hand out PDFs and passed to [stampMark]/[stampDemoText]. */
    const val DEMO_TEXT_PROPERTY: String = "littletrotter.pdf.watermark.text"

    /**
     * A mark's primary lines as a `"de"`/`"en"` pair of line lists, kept together so the
     * two languages can never drift apart. Modelled as a list (not a single `String`) so a future
     * mark can carry more than one primary line without a data-model change; every mark currently
     * defined carries exactly one. [linesFor] resolves the mark from
     * [de.littletrotter.shared.InvoiceMetaDto.documentLanguage] (`"de"`/`"en"`, frozen per
     * document) — never the UI locale and never `tr()`, because a German user issuing an English
     * invoice must still get the English mark. A `null` or unrecognised language falls back to
     * German, matching the renderer's own default.
     *
     * [letterSpaced] (PO typography rule 2026-09-04) — `true` when the primary line(s) are
     * drawn with the `Tc` character-spacing operator ([stampPage]) to produce the *gesperrte
     * Schrift* look; `de`/`en` always store the plain word(s), never literal spaced-out letters.
     */
    data class Mark(
        val de: List<String>,
        val en: List<String>,
        val letterSpaced: Boolean,
        /**
         * PO decision 2026-09-05 — whether this mark carries [SECONDARY_TEXT] below its primary
         * line(s). `false` for [CANCELLATION_MARK] only: a Stornorechnung is an accounting document that
         * annuls another one, and the project link is advertising with no place on it. Every
         * other mark keeps it, so this is a per-row property rather than a global switch.
         *
         * No default values: every caller of this (List-taking) primary constructor is the
         * secondary constructor below, which always supplies all four arguments explicitly — a
         * default here would only add an unused, unreachable `Mark$default` synthetic
         * constructor.
         */
        val showsAttribution: Boolean,
    ) {
        constructor(de: String, en: String, letterSpaced: Boolean = true, showsAttribution: Boolean = true) :
            this(listOf(de), listOf(en), letterSpaced, showsAttribution)

        fun linesFor(documentLanguage: String?): List<String> = if (documentLanguage == "en") en else de
    }

    /** Mark table row 1 — the cancellation document itself (`InvoiceDto.cancellation`, i.e.
     *  `typeCode == CORRECTED_INVOICE`, PO decision 1 (2026-09-02); previously `CREDIT_NOTE`).
     *  "Reversal" and not "credit note": the type code already appears as the page title, and
     *  "credit note" would say nothing new; "cancelled" alone is ambiguous with a cancelled
     *  order/payment. "Reversal" is the established international-accounting term for a document
     *  that annuls another in full (what SAP and most ERP systems call a Cancellation in English). */
    val CANCELLATION_MARK: Mark = Mark(de = "STORNO", en = "REVERSAL", showsAttribution = false)

    /** Mark table row 2 — a non-finalized invoice (`InvoiceDto.draft`). Replaces the
     *  former fixed-English-only draft wording (`DRAFT_PRIMARY_TEXT`): the mark now follows the
     *  document language like every other row instead of always reading "DRAFT". */
    val DRAFT_MARK: Mark = Mark(de = "ENTWURF", en = "DRAFT")

    /** Mark table row 3 — the brand mark every other PDF carries: the product name
     *  large and diagonal ("Little Trotter", large font, diagonal). A product name is never
     *  translated, so both sides of the pair are identical — the one row where [Mark.linesFor] is
     *  a no-op by design, not an oversight. Never letter-spaced. */
    val BRAND_MARK: Mark = Mark(de = "Little Trotter", en = "Little Trotter", letterSpaced = false)

    /**
     * Stamps [mark]'s lines for [documentLanguage] as the primary line(s), with [SECONDARY_TEXT]
     * directly below them, on every page of [pdfBytes]. A non-blank [demoText] (the deployment's
     * configured demo text) adds one more line between the two; `null` or blank adds nothing. See
     * [stampLines] for the shared geometry/byte contract.
     */
    fun stampMark(pdfBytes: ByteArray, mark: Mark, documentLanguage: String?, demoText: String? = null): ByteArray =
        stampLines(pdfBytes, markStamp(mark, documentLanguage, demoText))

    /**
     * For documents that carry none of the three marks (a covered output intent, the dispatch copy
     * of an offer): returns [pdfBytes] unchanged when [demoText] is unset or blank, otherwise stamps
     * the demo text alone as the primary line — no project link, not letter-spaced.
     */
    fun stampDemoText(pdfBytes: ByteArray, demoText: String?): ByteArray {
        val stamp = demoStamp(demoText) ?: return pdfBytes
        return stampLines(pdfBytes, stamp)
    }

    /** The content [stampMark] draws, for callers that stamp inside a document pass of their own. */
    fun markStamp(mark: Mark, documentLanguage: String?, demoText: String? = null): Stamp =
        Stamp(
            StampContent(
                mark.linesFor(documentLanguage),
                SECONDARY_TEXT.takeIf { mark.showsAttribution },
                mark.letterSpaced,
                normalizedDemoText(demoText),
            ),
        )

    /** The content [stampDemoText] draws, or `null` when [demoText] is unset or blank (nothing to stamp). */
    fun demoStamp(demoText: String?): Stamp? {
        val text = normalizedDemoText(demoText) ?: return null
        return Stamp(StampContent(listOf(text), secondary = null, letterSpaced = false, demo = null))
    }

    /** Draws [stamp] on every page of the already opened [document]; the caller saves it. */
    fun stamp(document: PDDocument, stamp: Stamp) {
        val font = loadFont(document)
        for (pageIndex in 0 until document.numberOfPages) {
            stampPage(document, document.getPage(pageIndex), font, stamp.content)
        }
    }

    /** Surrounding whitespace is never meant as document content, and a blank value means "off". */
    private fun normalizedDemoText(demoText: String?): String? = demoText?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Stamps the [stamp]'s primaries (all at the shared auto-fitted primary size, letter-spaced
     * when the mark is) with the optional demo line and the project link directly below them at
     * [SECONDARY_FONT_SCALE] of that size (never letter-spaced), all rotated and rendered as one block centered on each page
     * (existing content untouched — appended, not overwritten), and returns the re-saved bytes.
     * [Loader.loadPDF] + `save` preserves embedded files, XMP metadata and the OutputIntent
     * unchanged; only the new watermark content stream and the
     * (already-embedded-elsewhere-in-this-project) font resource are added.
     */
    private fun stampLines(pdfBytes: ByteArray, stamp: Stamp): ByteArray {
        return Loader.loadPDF(pdfBytes).use { document ->
            stamp(document, stamp)
            val out = ByteArrayOutputStream()
            document.save(out)
            out.toByteArray()
        }
    }

    /** What to stamp: opaque to callers, produced by [markStamp] or [demoStamp]. */
    class Stamp internal constructor(internal val content: StampContent)

    /** A single line to render: its text, font size, and whether it is drawn with `Tc` character
     *  spacing (typography rule — primary lines of a [Mark.letterSpaced] mark only, never
     *  the secondary project-link line). */
    private data class RenderLine(val text: String, val fontSize: Float, val letterSpaced: Boolean)

    /** [stampLines]' own resolved mark content, unchanged across every page it stamps — grouped
     *  so [stampPage] takes one parameter for it instead of four (LongParameterList). [demo] is
     *  the optional deployment-wide demo line, drawn between the primaries and [secondary]. */
    internal data class StampContent(
        val primaries: List<String>,
        val secondary: String?,
        val letterSpaced: Boolean,
        val demo: String?,
    )

    private fun stampPage(
        document: PDDocument,
        page: PDPage,
        font: PDType0Font,
        content: StampContent,
    ) {
        val (primaries, secondary, letterSpaced) = content
        val demo = content.demo
        val box = page.mediaBox
        val primaryFontSize = fontSizeFor(box, font, primaries, letterSpaced)
        val secondaryFontSize = primaryFontSize * SECONDARY_FONT_SCALE
        val angleRad = Math.toRadians(ANGLE_DEGREES)

        val centerX = box.width / 2f
        val centerY = box.height / 2f

        // `dir` is the unit vector "along the reading direction" of the rotated lines (the same
        // direction showText advances in). The anchor formulas below also use `dir` rotated -90°
        // — "downward", the direction from each line toward the next one below it, which is
        // (dirY, -dirX) and is inlined there (for plain horizontal text, angle 0, it correctly
        // reduces to straight down, (0, -1)).
        val dirX = cos(angleRad).toFloat()
        val dirY = sin(angleRad).toFloat()

        // Centered: all lines render as ONE block centered on the page's own center point — the
        // run of baselines (constant pitch, primary size + gap between every pair of neighbours)
        // is shifted so its middle sits exactly on the center, not just each line's own
        // (unrotated) start anchored there, which would only hold "centered" for a single, very
        // short line.
        // listOfNotNull, not a hardcoded second entry: a mark without a project link
        // ([Mark.showsAttribution]) renders its primary line(s) ALONE, and the centering below
        // then has to see a one-line block — appending an empty RenderLine instead would keep
        // reserving that line's height and push the mark visibly off-center.
        val lines = primaries.map { RenderLine(it, primaryFontSize, letterSpaced) } +
            listOfNotNull(demo?.let { RenderLine(it, secondaryFontSize, letterSpaced = false) }) +
            listOfNotNull(secondary?.let { RenderLine(it, secondaryFontSize, letterSpaced = false) })
        val pitch = primaryFontSize * (1f + LINE_GAP_SCALE)
        val firstBaselineOffset = -(lines.size - 1) * pitch / 2f

        PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            val transparency = PDExtendedGraphicsState()
            transparency.nonStrokingAlphaConstant = NON_STROKING_ALPHA
            cs.setGraphicsStateParameters(transparency)
            cs.setNonStrokingColor(WATERMARK_GRAY)

            lines.forEachIndexed { index, line ->
                val baselineOffset = firstBaselineOffset + index * pitch
                val charSpacing = if (line.letterSpaced) charSpacingFor(font, line.fontSize) else 0f
                val lineWidth = textWidth(font, line.text, line.fontSize, charSpacing)
                val baselineX = centerX + dirY * baselineOffset - dirX * (lineWidth / 2f)
                val baselineY = centerY - dirX * baselineOffset - dirY * (lineWidth / 2f)
                cs.beginText()
                cs.setFont(font, line.fontSize)
                cs.setCharacterSpacing(charSpacing)
                cs.setTextMatrix(Matrix.getRotateInstance(angleRad, baselineX, baselineY))
                cs.showText(line.text)
                cs.endText()

                // secondary is only non-null for a mark with Mark.showsAttribution -- the cancellation
                // mark's single line is always the LAST (and only) one, so this never fires for it.
                if (secondary != null && index == lines.lastIndex) {
                    val anchor = LineAnchor(dirX, dirY, baselineX, baselineY, line.fontSize, lineWidth)
                    addLinkAnnotation(document, page, font, anchor)
                }
            }
        }
    }

    /** The exact per-line geometry [stampPage] used to place and size a rotated line: [dirX]/
     *  [dirY] is the reading-direction unit vector, [baselineX]/[baselineY] the baseline's start
     *  point (`showText`'s origin), [fontSize] and [lineWidth] its glyph metrics.
     *  [addLinkAnnotation] derives the link hot area from this, not from the page bounds. */
    private data class LineAnchor(
        val dirX: Float,
        val dirY: Float,
        val baselineX: Float,
        val baselineY: Float,
        val fontSize: Float,
        val lineWidth: Float,
    )

    /**
     * ST-5, PDF/A-3 conformance — makes the just-drawn secondary line a real clickable link: a
     * [PDAnnotationLink] whose [PDActionURI] target is [SECONDARY_URL], with an invisible border
     * ([PDBorderStyleDictionary] width 0, no colour key) and an empty normal appearance stream so
     * PDF/A-3b viewers have nothing to paint beyond the glyphs [stampPage] already drew. The hot
     * area is derived from the exact same [anchor] and glyph metrics ([lineWidth],
     * [PDType0Font.getFontDescriptor]'s ascent/descent) `stampPage` used to place and size the
     * text, not from the page bounds — since the line is drawn rotated 45°, both `Rect` (the
     * axis-aligned bounding box the rotated corners fit into) and `QuadPoints` (the rotated
     * quadrilateral itself) are set, matching how
     * [org.apache.pdfbox.pdmodel.interactive.annotation.handlers.PDLinkAppearanceHandler] orders
     * an unrotated rectangle's own QuadPoints: bottom-left, bottom-right, top-right, top-left.
     *
     * ISO 19005-3's annotation rule requires every annotation to carry the `F` key with `Print`
     * set and `Hidden`/`Invisible`/`NoView` clear — set explicitly here rather than relying on the
     * PDFBox default (`0`, no flags at all), which would leave `Print` unset.
     */
    private fun addLinkAnnotation(
        document: PDDocument,
        page: PDPage,
        font: PDType0Font,
        anchor: LineAnchor,
    ) {
        val dirX = anchor.dirX
        val dirY = anchor.dirY
        val baselineX = anchor.baselineX
        val baselineY = anchor.baselineY
        val descriptor = font.fontDescriptor
        val ascent = descriptor.ascent / FONT_METRIC_UNITS_PER_EM * anchor.fontSize
        val descent = descriptor.descent / FONT_METRIC_UNITS_PER_EM * anchor.fontSize
        // "up", perpendicular to the reading direction `dir` -- for unrotated text (dir = (1,0))
        // this correctly reduces to straight up, (0, 1), matching stampPage's own comment on `dir`.
        val (upX, upY) = -dirY to dirX

        val bottomLeftX = baselineX + upX * descent
        val bottomLeftY = baselineY + upY * descent
        val bottomRightX = bottomLeftX + dirX * anchor.lineWidth
        val bottomRightY = bottomLeftY + dirY * anchor.lineWidth
        val topRightX = bottomRightX + upX * ascent
        val topRightY = bottomRightY + upY * ascent
        val topLeftX = bottomLeftX + upX * ascent
        val topLeftY = bottomLeftY + upY * ascent

        val minX = minOf(bottomLeftX, bottomRightX, topRightX, topLeftX)
        val maxX = maxOf(bottomLeftX, bottomRightX, topRightX, topLeftX)
        val minY = minOf(bottomLeftY, bottomRightY, topRightY, topLeftY)
        val maxY = maxOf(bottomLeftY, bottomRightY, topRightY, topLeftY)
        val rectangle = PDRectangle(minX, minY, maxX - minX, maxY - minY)

        val link = PDAnnotationLink()
        link.action = PDActionURI().apply { uri = SECONDARY_URL }
        link.rectangle = rectangle
        link.quadPoints = floatArrayOf(
            bottomLeftX, bottomLeftY,
            bottomRightX, bottomRightY,
            topRightX, topRightY,
            topLeftX, topLeftY,
        )
        link.borderStyle = PDBorderStyleDictionary().apply { width = 0f }
        link.isPrinted = true
        link.isHidden = false
        link.isInvisible = false
        link.isNoView = false
        link.page = page
        link.appearance = PDAppearanceDictionary().apply {
            setNormalAppearance(PDAppearanceStream(document).apply { bBox = rectangle })
        }

        page.annotations.add(link)
    }

    /** The `Tc` character-spacing amount for [fontSize], derived from the
     *  font's own space-glyph width so the gap between letters reads the same as the historical
     *  literal-space encoding did, but as real typography instead of printed content. */
    private fun charSpacingFor(font: PDType0Font, fontSize: Float): Float =
        font.getStringWidth(" ") / FONT_METRIC_UNITS_PER_EM * fontSize

    /** The rendered width of [text] at [fontSize], including [charSpacing] once per character —
     *  the PDF spec applies `Tc` after every glyph a `Tj`/`showText` shows, the final one
     *  included, so this must match that exactly for the centering math in [stampPage] to line up
     *  with what actually gets drawn. */
    private fun textWidth(font: PDType0Font, text: String, fontSize: Float, charSpacing: Float): Float =
        font.getStringWidth(text) / FONT_METRIC_UNITS_PER_EM * fontSize + charSpacing * text.length

    /** Sized so the WIDEST (unrotated) primary text run is a fixed multiple of the page's shorter
     *  side — clearly visible without swamping the page content behind it, on both portrait
     *  invoice pages and the (often landscape) rasterized receipt annex pages, and with every
     *  primary line of a multi-primary-line mark fitting at the one shared size. Clamped so the
     *  mark stays legible on pathological page sizes. [letterSpaced] folds the `Tc` contribution
     *  into the width this sizes against (both terms scale linearly with font size, so the same
     *  "width at a reference size of 1000" trick used for the raw glyph width still works for the
     *  combined width — see [textWidth]/[charSpacingFor]), so a letter-spaced mark is never
     *  auto-fitted as if it were narrower than it will actually render. */
    private fun fontSizeFor(box: PDRectangle, font: PDType0Font, texts: List<String>, letterSpaced: Boolean): Float {
        val targetWidth = min(box.width, box.height) * TARGET_WIDTH_RATIO
        val spaceWidthAt1000 = if (letterSpaced) font.getStringWidth(" ") else 0f
        val widthAt1000 = texts.maxOfOrNull { font.getStringWidth(it) + spaceWidthAt1000 * it.length } ?: 0f
        if (widthAt1000 <= 0f) return FALLBACK_FONT_SIZE_PT
        val size = targetWidth * FONT_METRIC_UNITS_PER_EM / widthAt1000
        return size.coerceIn(MIN_FONT_SIZE_PT, MAX_FONT_SIZE_PT)
    }

    /** `internal` (not `private`) with a defaulted [resourceLookup] — production always uses the
     *  default (real classloader), so the happy path stays covered exactly as before; a test can
     *  hand in a failing lookup to exercise the "bundled font missing from the JAR" branch below
     *  without corrupting the real classpath resource. */
    internal fun loadFont(
        document: PDDocument,
        resourceLookup: (String) -> java.io.InputStream? = { path -> javaClass.getResourceAsStream(path) },
    ): PDType0Font {
        val fontStream = resourceLookup("/fonts/LiberationSans-Regular.ttf")
            ?: error("Font resource /fonts/LiberationSans-Regular.ttf not found")
        return fontStream.use { PDType0Font.load(document, it) }
    }
}
