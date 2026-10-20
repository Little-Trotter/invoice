package de.littletrotter.shared.receipt

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException

class ReceiptPdfInspectorTest {

    private val inspector = ReceiptPdfInspector(ReceiptRaster(dpi = 150f, jpegQuality = 0.75f))

    // An A4 page is shown on the annex inside the margins and below the header band, so it is
    // displayed smaller than A4; the print raster counts pixels at that display size.
    private val a4Display = ReceiptDisplaySize.of(PDRectangle.A4.width, PDRectangle.A4.height)
    private val a4PrintPixels = Math.round(150f * a4Display.width / 72f)
    private val a4OnAnnex = a4Display.width.toDouble() * a4Display.height / (ReceiptDisplaySize.A4_SHORT_SIDE.toDouble() * ReceiptDisplaySize.A4_LONG_SIDE)

    private fun bytes(build: (PDDocument) -> Unit): ByteArray {
        PDDocument().use { document ->
            build(document)
            return ByteArrayOutputStream().also { document.save(it) }.toByteArray()
        }
    }

    private fun imagePage(document: PDDocument, size: PDRectangle, pixelsWide: Int, rotation: Int = 0, withText: Boolean = false) {
        val page = PDPage(size)
        page.rotation = rotation
        document.addPage(page)
        val pixelsHigh = (pixelsWide * size.height / size.width).toInt()
        val image = JPEGFactory.createFromImage(document, BufferedImage(pixelsWide, pixelsHigh, BufferedImage.TYPE_INT_RGB), 0.75f)
        PDPageContentStream(document, page).use { cs ->
            cs.drawImage(image, 0f, 0f, size.width, size.height)
            if (withText) {
                cs.beginText()
                cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 10f)
                cs.newLineAtOffset(10f, 10f)
                cs.showText("x")
                cs.endText()
            }
        }
    }

    private fun textPage(document: PDDocument, size: PDRectangle) {
        val page = PDPage(size)
        document.addPage(page)
        PDPageContentStream(document, page).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 10f)
            cs.newLineAtOffset(10f, 10f)
            cs.showText("receipt")
            cs.endText()
        }
    }

    @Test
    fun `an A4 page that is one JPEG at the print resolution is an image receipt`() {
        val pdf = bytes { imagePage(it, PDRectangle.A4, pixelsWide = a4PrintPixels) }

        val profile = inspector.inspect(pdf)

        assertEquals(1, profile.pageCount)
        assertEquals(a4OnAnnex, profile.a4Factor, 0.01)
        assertEquals(ReceiptRasterKind.IMAGE_PDF, profile.rasterKind)
    }

    @Test
    fun `an image above the print resolution is rendered at print, not copied`() {
        val pdf = bytes { imagePage(it, PDRectangle.A4, pixelsWide = 2400) }

        assertEquals(ReceiptRasterKind.DOCUMENT, inspector.inspect(pdf).rasterKind)
    }

    @Test
    fun `a full-bleed A4 scan at the print dpi is an image receipt and its annex weighs the receipt bytes`() {
        val pdf = bytes { imagePage(it, PDRectangle.A4, pixelsWide = 1240) }

        val profile = inspector.inspect(pdf)

        assertEquals(ReceiptRasterKind.IMAGE_PDF, profile.rasterKind)
        assertEquals(pdf.size.toLong(), profile.annexBytes)
    }

    @Test
    fun `a document receipt records the JPEG bytes of its pages rendered at the print raster`() {
        val pdf = bytes { textPage(it, PDRectangle.A4) }
        val raster = ReceiptRaster(dpi = 150f, jpegQuality = 0.75f)

        val measured = ReceiptPdfInspector(raster).inspect(pdf).annexBytes
        val lowerQuality = ReceiptPdfInspector(raster.copy(jpegQuality = 0.2f)).inspect(pdf).annexBytes

        assertTrue(measured > 0)
        assertTrue(lowerQuality < measured, "quality 0.2 gave $lowerQuality, 0.75 gave $measured")
    }

    @Test
    fun `a page with text on top of its image is a document`() {
        val pdf = bytes { imagePage(it, PDRectangle.A4, pixelsWide = a4PrintPixels, withText = true) }

        assertEquals(ReceiptRasterKind.DOCUMENT, inspector.inspect(pdf).rasterKind)
    }

    @Test
    fun `a rotated image page is a document because the copy would have to rotate it`() {
        val pdf = bytes { imagePage(it, PDRectangle.A4, pixelsWide = a4PrintPixels, rotation = 90) }

        assertEquals(ReceiptRasterKind.DOCUMENT, inspector.inspect(pdf).rasterKind)
    }

    @Test
    fun `a text receipt is a document`() {
        val pdf = bytes { textPage(it, PDRectangle.A4) }

        assertEquals(ReceiptRasterKind.DOCUMENT, inspector.inspect(pdf).rasterKind)
    }

    @Test
    fun `a receipt-roll slip counts as a fraction of an A4 page`() {
        val slip = PDRectangle(226f, 600f)
        val pdf = bytes { textPage(it, slip) }

        val expected = 226.0 * 600.0 / (ReceiptDisplaySize.A4_SHORT_SIDE.toDouble() * ReceiptDisplaySize.A4_LONG_SIDE)
        assertEquals(expected, inspector.inspect(pdf).a4Factor, 0.001)
    }

    @Test
    fun `a page above A4 counts at its display size, never more than A4`() {
        val pdf = bytes { textPage(it, PDRectangle(4000f, 5600f)) }

        assertEquals(0.74, inspector.inspect(pdf).a4Factor, 0.03)
    }

    @Test
    fun `a mixed receipt counts all pages and is a document unless every page is an image page`() {
        val pdf = bytes {
            imagePage(it, PDRectangle.A4, pixelsWide = a4PrintPixels)
            textPage(it, PDRectangle.A4)
        }

        val profile = inspector.inspect(pdf)

        assertEquals(2, profile.pageCount)
        assertEquals(2 * a4OnAnnex, profile.a4Factor, 0.02)
        assertEquals(ReceiptRasterKind.DOCUMENT, profile.rasterKind)
    }

    @Test
    fun `importableImage yields the JPEG of an image page and nothing for a text page`() {
        val pdf = bytes {
            imagePage(it, PDRectangle.A4, pixelsWide = a4PrintPixels)
            textPage(it, PDRectangle.A4)
        }

        Loader.loadPDF(pdf).use { document ->
            assertNotNull(inspector.importableImage(document.getPage(0)))
            assertNull(inspector.importableImage(document.getPage(1)))
        }
    }

    @Test
    fun `a PDF without pages and a file that is not a PDF are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { inspector.inspect(bytes { }) }
        assertThrows(IOException::class.java) { inspector.inspect(byteArrayOf(1, 2, 3)) }
    }
}
