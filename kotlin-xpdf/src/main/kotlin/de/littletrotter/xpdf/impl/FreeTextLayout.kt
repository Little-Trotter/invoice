package de.littletrotter.xpdf.impl

import de.littletrotter.shared.documents.FreeTextMarkup
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts

/**
 * Where the invoice free text is printed (T3).
 *
 * Up to [FREETEXT_INLINE_MAX_PT] it sits in the title band, behind the subject line. Longer text
 * is placed after the items table (`FREETEXT_AFTER_ITEMS`), where page breaks naturally follow
 * the table. The threshold leaves the title band at least 150 pt of slack before the footer; the
 * estimate need not be exact, and conservative sizing (bold metrics, one spare line) ensures the
 * block is always placed in a valid position.
 */
internal const val FREETEXT_INLINE_MAX_PT = 300f

/** Text width of the free-text element: 481 pt column, 13 pt left indent, 2 pt orange rule. */
private const val FREETEXT_WIDTH_PT = 466f
private const val FREETEXT_FONT_SIZE_PT = 9.5f

/** Floor for the width left after a (pathologically) deep indentation. */
private const val MIN_TEXT_WIDTH_PT = 50f

/** Measured line pitch of 9.5 pt Liberation Sans in the rendered PDFs is 10.9 pt; 11.4 keeps a margin. */
private const val FREETEXT_LINE_PT = 11.4f

/** The element's `spacingBefore`. */
private const val FREETEXT_SPACING_PT = 8f

/** Bold advance widths are the widest of the two weights, so bold-heavy text is never under-counted. */
private val FREETEXT_FONT = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)

/**
 * Lines the printed free text occupies: markup markers are stripped ([FreeTextMarkup.toPlain]),
 * each source line wraps on its own, and a list line's indentation narrows its available width.
 */
internal fun estimateFreeTextLineCount(freeText: String): Int {
    val spaceWidth = textWidthPt(" ", FREETEXT_FONT, FREETEXT_FONT_SIZE_PT)
    return FreeTextMarkup.toPlain(freeText).split('\n').sumOf { line ->
        val content = line.trimStart(' ')
        val indentPt = (line.length - content.length) * spaceWidth
        val widthPt = (FREETEXT_WIDTH_PT - indentPt).coerceAtLeast(MIN_TEXT_WIDTH_PT)
        wrappedLineCount(content, widthPt, FREETEXT_FONT, FREETEXT_FONT_SIZE_PT)
    }
}

/** Estimated height of the free-text element, including the spacing and one spare line. */
internal fun estimateFreeTextHeightPt(freeText: String): Float =
    (estimateFreeTextLineCount(freeText) + 1) * FREETEXT_LINE_PT + FREETEXT_SPACING_PT

internal fun printsFreeTextAfterItems(freeText: String): Boolean =
    freeText.isNotBlank() && estimateFreeTextHeightPt(freeText) > FREETEXT_INLINE_MAX_PT

/** PDFBox/AFM glyph widths are expressed in 1000ths of the font's em size. */
private const val FONT_METRIC_UNITS_PER_EM = 1000f

/**
 * Width of [s] in [font] at [sizePt]. A character the standard-14 encoding cannot express (an
 * emoji, an arrow, a letter outside WinAnsi) makes PDFBox throw; it is measured as a full em
 * instead — an over-estimate, and an estimate must never be able to prevent the invoice.
 */
internal fun textWidthPt(s: String, font: PDType1Font, sizePt: Float): Float {
    val units = runCatching { font.getStringWidth(s) }.getOrNull()
        ?: s.codePoints().toArray().sumOf { cp ->
            runCatching { font.getStringWidth(String(Character.toChars(cp))) }
                .getOrDefault(FONT_METRIC_UNITS_PER_EM)
                .toDouble()
        }.toFloat()
    return units / FONT_METRIC_UNITS_PER_EM * sizePt
}

/**
 * Lines one paragraph (no `\n`) occupies in a field [fieldWidthPt] wide, wrapped at spaces like the
 * engine does; a single word wider than the field is broken at character level by the engine, so
 * it adds the lines its width needs.
 */
internal fun wrappedLineCount(paragraph: String, fieldWidthPt: Float, font: PDType1Font, fontSizePt: Float): Int {
    if (paragraph.isEmpty()) return 1
    val spaceWidth = textWidthPt(" ", font, fontSizePt)

    var lines = 1
    var lineWidth = 0f
    for (word in paragraph.split(" ")) {
        val wordWidth = textWidthPt(word, font, fontSizePt)
        val candidateWidth = if (lineWidth == 0f) wordWidth else lineWidth + spaceWidth + wordWidth
        if (lineWidth > 0f && candidateWidth > fieldWidthPt) {
            lines++
            lineWidth = wordWidth
        } else {
            lineWidth = candidateWidth
        }
        if (lineWidth > fieldWidthPt) {
            val broken = (lineWidth / fieldWidthPt).toInt()
            lines += broken
            lineWidth -= broken * fieldWidthPt
        }
    }
    return lines
}
