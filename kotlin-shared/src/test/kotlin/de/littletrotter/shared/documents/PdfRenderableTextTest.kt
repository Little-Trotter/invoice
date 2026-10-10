package de.littletrotter.shared.documents

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PdfRenderableTextTest {

    @Test
    fun `German typography and common symbols are renderable`() {
        assertThat(PdfRenderableText.isRenderable("Äöü ß € „Zitat“ – — … © ® ° ½ № é ñ")).isTrue()
    }

    @Test
    fun `line breaks and tabs are layout and allowed`() {
        assertThat(PdfRenderableText.isRenderable("a\r\nb\tc\nd")).isTrue()
    }

    @Test
    fun `null and empty text are renderable`() {
        assertThat(PdfRenderableText.isRenderable(null as String?)).isTrue()
        assertThat(PdfRenderableText.isRenderable("")).isTrue()
        assertThat(PdfRenderableText.firstOffending(null)).isEmpty()
    }

    @Test
    fun `emoji as surrogate pair is rejected and reported as one character`() {
        assertThat(PdfRenderableText.isRenderable("Danke 💩")).isFalse()
        assertThat(PdfRenderableText.firstOffending("Danke 💩💩")).containsExactly("💩")
    }

    @Test
    fun `CJK is rejected`() {
        assertThat(PdfRenderableText.firstOffending("漢字")).containsExactly("漢", "字")
    }

    @Test
    fun `control and invisible format characters are rejected with their code point`() {
        assertThat(PdfRenderableText.firstOffending("a\u0000b​c")).containsExactly("U+0000", "U+200B")
        assertThat(PdfRenderableText.isRenderable("a\u0007")).isFalse()
    }

    @Test
    fun `soft hyphen is allowed because the fonts carry it`() {
        assertThat(PdfRenderableText.isRenderable("Rech­nung")).isTrue()
    }

    @Test
    fun `offending list honours the limit and keeps order of appearance`() {
        assertThat(PdfRenderableText.firstOffending("😀a😁b😂", limit = 2)).containsExactly("😀", "😁")
    }

    @Test
    fun `table is ascending and non-overlapping`() {
        val ranges = PdfRenderableText.glyphRanges()
        assertThat(ranges).isNotEmpty()
        ranges.zipWithNext().forEach { (a, b) -> assertThat(a.last).isLessThan(b.first - 1) }
    }
}
