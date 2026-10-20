package de.littletrotter.xpdf.impl

import com.google.zxing.common.BitMatrix
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** [QrSvg]: ZXing matrix in, run-length-encoded SVG out. */
class QrSvgTest {

    @Test
    fun `happy path - encode produces a square matrix`() {
        val matrix = QrSvg.encode("BCD")

        assertThat(matrix.width).isEqualTo(matrix.height)
    }

    @Test
    fun `happy path - a version-1 payload encodes to 21 modules and renders a matching SVG`() {
        val matrix = QrSvg.encode("BCD")
        val svg = QrSvg.render(matrix)

        assertThat(matrix.width).isEqualTo(21)
        assertThat(svg).startsWith(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 21 21\" shape-rendering=\"crispEdges\">",
        )
        assertThat(svg).endsWith("</svg>")
        val tags = Regex("</?[a-zA-Z]+").findAll(svg).map { it.value }.toSet()
        assertThat(tags).isSubsetOf(setOf("<svg", "</svg", "<rect"))
    }

    @Test
    fun `happy path - contiguous dark modules per row collapse into one rect each`() {
        val matrix = BitMatrix(4, 4)
        matrix.set(0, 0)
        matrix.set(1, 0)
        matrix.set(2, 0)
        matrix.set(1, 2)

        val svg = QrSvg.render(matrix)

        assertThat(svg).contains("<rect x=\"0\" y=\"0\" width=\"3\" height=\"1\" fill=\"#000\"/>")
        assertThat(svg).contains("<rect x=\"1\" y=\"2\" width=\"1\" height=\"1\" fill=\"#000\"/>")
        assertThat(Regex("<rect").findAll(svg).count()).isEqualTo(2)
    }

    @Test
    fun `boundary - an all-light matrix renders no rect`() {
        val matrix = BitMatrix(4, 4)

        val svg = QrSvg.render(matrix)

        assertThat(svg).doesNotContain("<rect")
        assertThat(svg).contains("viewBox=\"0 0 4 4\"")
    }
}
