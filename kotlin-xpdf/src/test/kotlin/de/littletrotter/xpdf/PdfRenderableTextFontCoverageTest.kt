package de.littletrotter.xpdf

import de.littletrotter.shared.documents.PdfRenderableText
import org.apache.fontbox.ttf.TTFParser
import org.apache.pdfbox.io.RandomAccessReadBuffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Drift guard: the code point table in [PdfRenderableText] is generated from the bundled fonts.
 * If a font file is replaced or a style added, the table must be regenerated, otherwise input
 * validation and the PDF engine would disagree about what can be shown.
 */
class PdfRenderableTextFontCoverageTest {

    private val fontFiles = listOf("Regular", "Bold", "Italic", "BoldItalic")

    private fun coverageIntersection(): List<IntRange> {
        val cmaps = fontFiles.map { style ->
            val bytes = javaClass.getResourceAsStream("/fonts/LiberationSans-$style.ttf")!!.use { it.readBytes() }
            TTFParser().parse(RandomAccessReadBuffer(bytes)).unicodeCmapLookup
        }
        val ranges = mutableListOf<IntRange>()
        var start = -1
        for (cp in 0..Character.MAX_CODE_POINT + 1) {
            val covered = cp <= Character.MAX_CODE_POINT && cmaps.all { it.getGlyphId(cp) != 0 }
            if (covered && start < 0) start = cp
            if (!covered && start >= 0) {
                ranges += start..<cp
                start = -1
            }
        }
        return ranges
    }

    @Test
    fun `table equals the glyph intersection of every bundled font style`() {
        assertThat(PdfRenderableText.glyphRanges()).isEqualTo(coverageIntersection())
    }
}
