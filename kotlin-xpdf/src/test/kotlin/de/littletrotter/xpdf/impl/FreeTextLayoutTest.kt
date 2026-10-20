package de.littletrotter.xpdf.impl

import de.littletrotter.shared.documents.FreeTextMarkup
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** T3 — the free-text height estimate that decides between the title band and the band after the items table. */
class FreeTextLayoutTest {

    private fun lines(count: Int): String = (1..count).joinToString("\n") { "Zeile $it" }

    @Test
    fun `height grows monotonically with the text length`() {
        val heights = listOf(0, 200, 1000, 2000, 4000).map { estimateFreeTextHeightPt(FreeTextProseFixtures.prose(it)) }

        assertEquals(heights.sorted(), heights)
        assertTrue(heights.last() > heights.first())
    }

    @Test
    fun `every explicit line break starts a new line`() {
        assertEquals(30, estimateFreeTextLineCount(lines(30)))
        assertEquals(3, estimateFreeTextLineCount("a\n\nb"), "an empty paragraph still occupies a line")
    }

    @Test
    fun `bold text is never estimated below the regular weight`() {
        val text = FreeTextProseFixtures.prose(2000)
        val bold = "**" + text.replace("\n", "**\n**") + "**"
        val regular = FreeTextMarkup.toPlain(text).split('\n').sumOf {
            wrappedLineCount(it, 466f, PDType1Font(Standard14Fonts.FontName.HELVETICA), 9.5f)
        }

        assertTrue(estimateFreeTextLineCount(bold) >= regular)
        assertEquals(estimateFreeTextLineCount(text), estimateFreeTextLineCount(bold), "markers are not counted as printed characters")
    }

    @Test
    fun `list indentation narrows the available width`() {
        // One word that fills 450 pt of the 466 pt line: alone it fits, behind two indent levels it does not.
        val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
        var content = "x"
        while (textWidthPt("- $content", bold, 9.5f) < 450f) content += "x"
        val level0 = estimateFreeTextLineCount("- $content")
        val level2 = estimateFreeTextLineCount(" ".repeat(8) + "- $content")

        assertEquals(1, level0)

        assertTrue(level2 > level0, "level 2 ($level2 lines) must wrap more than level 0 ($level0 lines)")
    }

    @Test
    fun `a single word wider than the line is broken into the lines it needs`() {
        // 4000 bold "x" are about 21 000 pt wide: about 45 lines of 466 pt.
        assertTrue(estimateFreeTextLineCount("x".repeat(4000)) in 40..50)
    }

    @Test
    fun `characters outside the base font encoding are measured instead of throwing`() {
        val text = "Fertig 😀 → ✓ Łódź"

        assertEquals(1, estimateFreeTextLineCount(text))
    }

    @Test
    fun `the placement flips exactly where the estimate crosses the inline maximum`() {
        assertFalse(printsFreeTextAfterItems(lines(24)), "24 lines estimate ${estimateFreeTextHeightPt(lines(24))} pt")
        assertTrue(printsFreeTextAfterItems(lines(25)), "25 lines estimate ${estimateFreeTextHeightPt(lines(25))} pt")
        assertFalse(printsFreeTextAfterItems(""))
        assertFalse(printsFreeTextAfterItems("  \n "))
    }

    @Test
    fun `a two-line text stays in the title band`() {
        assertFalse(printsFreeTextAfterItems(FreeTextProseFixtures.prose(200)))
        assertFalse(printsFreeTextAfterItems("Vielen Dank fuer Ihren Auftrag."))
    }
}
