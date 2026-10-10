package de.littletrotter.xpdf.impl

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import kotlin.math.abs

/** Test helper: the diagonal glyphs of a PDF — the watermark — in drawing order with the letter spacing collapsed. */
internal object WatermarkText {

    private const val MIN_SHEAR = 0.1f

    fun of(pdf: ByteArray): String {
        val rotated = StringBuilder()
        val stripper = object : PDFTextStripper() {
            override fun processTextPosition(text: TextPosition) {
                if (abs(text.textMatrix.shearY) > MIN_SHEAR) rotated.append(text.unicode)
            }
        }
        Loader.loadPDF(pdf).use { stripper.getText(it) }
        return rotated.toString().replace(Regex("\\s+"), "")
    }
}
