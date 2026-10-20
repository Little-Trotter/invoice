package de.littletrotter.shared.pdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HelveticaTextFitTest {

    @Test
    fun `widthOf returns the exact AFM width for a known character`() {
        assertEquals(667, HelveticaWidths.widthOf('A'))
        assertEquals(1015, HelveticaWidths.widthOf('@'))
        assertEquals(611, HelveticaWidths.widthOf('ß'))
        assertEquals(667, HelveticaWidths.widthOf('Ä'))
    }

    @Test
    fun `widthOf falls back for an uncovered character instead of throwing`() {
        assertEquals(HelveticaWidths.FALLBACK_WIDTH, HelveticaWidths.widthOf('漢'))
    }

    @Test
    fun `fitFontSize returns maxPt for a short text`() {
        assertEquals(7.5f, fitFontSize("Tel. 030 123"))
    }

    @Test
    fun `fitFontSize steps down to the largest size that still fits`() {
        // 40 chars of "n" (556/1000 em each) at 7.5pt is 40*556*7.5/1000 = 166.8pt > 135pt;
        // at 6.5pt it is 40*556*6.5/1000 = 144.56pt, still over; at 6.0pt it is 133.44pt, fits.
        val text = "n".repeat(40)
        assertEquals(6.0f, fitFontSize(text))
    }

    @Test
    fun `fitFontSize returns null when the text does not fit even at minPt`() {
        val text = "n".repeat(100)
        assertNull(fitFontSize(text))
    }

    @Test
    fun `fitFontSize respects a custom width budget`() {
        val text = "n".repeat(10)
        // 10 * 556 / 1000 = 5.56pt per pt of size; at maxPt (7.5) that's 41.7pt, under a
        // generous 50pt budget.
        assertEquals(7.5f, fitFontSize(text, widthPt = 50f))
        // Against a budget only the minimum size clears (33.36pt at 6.0pt, over at every
        // higher step), it must shrink all the way down instead of returning maxPt.
        assertEquals(6.0f, fitFontSize(text, widthPt = 35f))
    }
}
