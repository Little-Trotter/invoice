package de.littletrotter.xpdf

import de.littletrotter.shared.pdf.HelveticaWidths
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pins [HelveticaWidths] (kept in pure Kotlin so `kotlin-shared`'s UI validator never needs a
 * PDFBox dependency) against PDFBox's own `Standard14Fonts` Helvetica metrics — the same AFM file
 * ([HelveticaWidths]'s own KDoc), read here through the one module that already depends on PDFBox.
 * Every character [HelveticaWidths] carries an exact table entry for (not [HelveticaWidths
 * .FALLBACK_WIDTH]'s approximation) must match PDFBox to the point; a mismatch here means either
 * table drifted from the real font metrics PDFBox uses for text layout.
 */
class HelveticaWidthsAfmParityTest {

    private val helvetica = PDType1Font(Standard14Fonts.FontName.HELVETICA)

    /** Every codepoint [HelveticaWidths] documents an exact entry for — the ASCII range plus the
     *  German umlauts/ß and the handful of typographic extras (see [HelveticaWidths]'s KDoc). Kept
     *  as an explicit list (not "iterate the map") so this test does not silently stop proving
     *  anything if [HelveticaWidths] ever switched its internal representation. */
    private val coveredChars: List<Char> =
        (0x20..0x7E).map { it.toChar() } +
            listOf('Ä', 'Ö', 'Ü', 'ä', 'ö', 'ü', 'ß', 'é', '‘', '’', '“', '”', '–', '—', '©', '®', '°', '€')

    @Test
    fun `every covered character matches PDFBox's own Helvetica metrics`() {
        coveredChars.forEach { ch ->
            val expected = helvetica.getStringWidth(ch.toString()).toInt()
            assertEquals(expected, HelveticaWidths.widthOf(ch), "width mismatch for '$ch' (U+${ch.code.toString(16)})")
        }
    }

    @Test
    fun `totalWidth sums PDFBox's own metrics for a representative footer line`() {
        val text = "Sitz: Müller & Söhne GmbH, Straße 1, 54550 Musterörtchen – www.beispiel.de"
        val expected = helvetica.getStringWidth(text).toInt()
        assertEquals(expected, HelveticaWidths.totalWidth(text))
    }
}
