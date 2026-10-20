package de.littletrotter.infrastructure.receipt

import de.littletrotter.shared.receipt.ReceiptDisplaySize
import de.littletrotter.shared.receipt.ReceiptPageProfile
import de.littletrotter.shared.receipt.ReceiptRasterKind
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class PdfBoxReceiptPageInspectorTest {

    private val inspector = PdfBoxReceiptPageInspector()

    private fun blankPdf(pages: Int): ByteArray =
        PDDocument().use { doc ->
            repeat(pages) { doc.addPage(PDPage()) }
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }

    @Test
    fun `inspect reports page count and A4 factor of a blank two-page receipt`() {
        val profile = inspector.inspect(blankPdf(2))

        assertEquals(2, profile.pageCount)
        val shown = ReceiptDisplaySize.of(612f, 792f)
        assertEquals(2.0 * shown.width * shown.height / (595.2756 * 841.8898), profile.a4Factor, 0.01)
        assertEquals(ReceiptRasterKind.DOCUMENT, profile.rasterKind)
    }

    @Test
    fun `inspect falls back to the unknown profile for a file it cannot read`() {
        assertEquals(ReceiptPageProfile.unmeasured(3), inspector.inspect(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `first page preview is a PNG for a readable file and null for a broken one`() {
        val png = inspector.firstPagePng(blankPdf(1))

        assertNotNull(png)
        assertTrue(png!!.size > 8 && png[1] == 'P'.code.toByte() && png[2] == 'N'.code.toByte())
        assertNull(inspector.firstPagePng(byteArrayOf(1, 2, 3)))
    }
}
