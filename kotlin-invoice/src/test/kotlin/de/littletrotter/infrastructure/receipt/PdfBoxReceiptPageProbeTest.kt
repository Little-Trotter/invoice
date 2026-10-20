package de.littletrotter.infrastructure.receipt

import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSFloat
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.ConvolveOp
import java.awt.image.Kernel
import java.io.ByteArrayOutputStream

/** [PdfBoxReceiptPageProbe] on PDFs built in the test: a sharp scan, a coarse one, a blurred one and a text page. */
class PdfBoxReceiptPageProbeTest {

    private val probe = PdfBoxReceiptPageProbe()

    private fun textBitmap(pixelWidth: Int): BufferedImage {
        val height = (pixelWidth * A4_RATIO).toInt()
        val image = BufferedImage(pixelWidth, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, pixelWidth, height)
        g.color = Color.BLACK
        val fontPixels = pixelWidth / FONT_DIVISOR
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, fontPixels)
        var y = fontPixels * 2
        while (y < height - fontPixels) {
            g.drawString("Summe 12,50 EUR Kasse 3 Beleg 4711", fontPixels, y)
            y += fontPixels * 2
        }
        g.dispose()
        return image
    }

    private fun blurred(image: BufferedImage): BufferedImage {
        val size = 9
        val kernel = Kernel(size, size, FloatArray(size * size) { 1f / (size * size) })
        return ConvolveOp(kernel, ConvolveOp.EDGE_NO_OP, null).filter(image, null)
    }

    private fun pdfWithFullPageImage(bitmap: BufferedImage): ByteArray = PDDocument().use { doc ->
        val page = PDPage(PDRectangle.A4)
        doc.addPage(page)
        PDPageContentStream(doc, page).use { it.drawImage(LosslessFactory.createFromImage(doc, bitmap), 0f, 0f, page.mediaBox.width, page.mediaBox.height) }
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    private fun textOnlyPdf(): ByteArray = PDDocument().use { doc ->
        val page = PDPage(PDRectangle.A4)
        doc.addPage(page)
        PDPageContentStream(doc, page).use {
            it.beginText()
            it.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 10f)
            it.newLineAtOffset(50f, 700f)
            it.showText("Summe 12,50 EUR")
            it.endText()
        }
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    @Test
    fun `sharp scan at 150 dpi is not reported`() {
        val pages = probe.probe(pdfWithFullPageImage(textBitmap(1060)), 10)
        assertEquals(1, pages.size)
        assertEquals(150.0, pages.single().readability.effectiveDpi!!, 1.0)
        assertFalse(pages.single().readability.suspect)
        assertNull(pages.single().previewPng)
    }

    @Test
    fun `coarse scan is reported with a preview`() {
        val page = probe.probe(pdfWithFullPageImage(textBitmap(400)), 10).single()
        assertTrue(page.readability.lowResolution)
        assertNotNull(page.previewPng)
        assertTrue(page.previewPng!!.size > 8)
    }

    @Test
    fun `blurred scan is reported as blurred`() {
        val page = probe.probe(pdfWithFullPageImage(blurred(textBitmap(1060))), 10).single()
        assertTrue(page.readability.blurred, "variance ${page.readability.laplaceVariance}")
        assertNotNull(page.previewPng)
    }

    @Test
    fun `text page has nothing to measure`() {
        val page = probe.probe(textOnlyPdf(), 10).single()
        assertNull(page.readability.effectiveDpi)
        assertNull(page.readability.laplaceVariance)
        assertFalse(page.readability.suspect)
    }

    @Test
    fun `a page with paths, clips and a shading fill is read without error and has nothing to measure`() {
        val bytes = PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val shading = PDShadingType2(COSDictionary()).apply {
                shadingType = 2
                colorSpace = PDDeviceRGB.INSTANCE
                coords = COSArray().apply { listOf(0f, 0f, 1f, 1f).forEach { add(COSFloat(it)) } }
            }
            PDPageContentStream(doc, page).use {
                it.addRect(10f, 10f, 100f, 100f)
                it.clip()
                it.moveTo(0f, 0f)
                it.lineTo(50f, 50f)
                it.curveTo(60f, 60f, 70f, 70f, 80f, 80f)
                it.curveTo1(90f, 90f, 100f, 100f)
                it.curveTo2(110f, 110f, 120f, 120f)
                it.closePath()
                it.fillAndStroke()
                it.moveTo(0f, 0f)
                it.lineTo(5f, 5f)
                it.stroke()
                it.moveTo(0f, 0f)
                it.lineTo(5f, 5f)
                it.fill()
                it.moveTo(0f, 0f)
                it.lineTo(5f, 5f)
                it.clip()
                it.shadingFill(shading)
            }
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }

        val page = probe.probe(bytes, 10).single()

        assertNull(page.readability.effectiveDpi)
        assertNull(page.previewPng)
    }

    @Test
    fun `a page without a valid size is reported as not measurable`() {
        val bytes = PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle(0f, 0f)))
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }

        val page = probe.probe(bytes, 10).single()

        assertEquals(1, page.readability.pageNumber)
        assertNull(page.readability.effectiveDpi)
        assertNull(page.previewPng)
    }

    @Test
    fun `a small image on a text page does not count as the scan`() {
        val bytes = PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use {
                it.drawImage(LosslessFactory.createFromImage(doc, textBitmap(200)), 0f, 0f, 40f, 40f)
            }
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }

        assertNull(probe.probe(bytes, 10).single().readability.effectiveDpi)
    }

    @Test
    fun `page limit caps the pages measured`() {
        val two = PDDocument().use { doc ->
            repeat(2) { doc.addPage(PDPage(PDRectangle.A4)) }
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
        assertEquals(1, probe.probe(two, 1).size)
    }

    @Test
    fun `bytes that are not a pdf yield no pages`() {
        assertTrue(probe.probe("not a pdf".toByteArray(), 10).isEmpty())
    }

    private companion object {
        const val A4_RATIO = 1.414
        const val FONT_DIVISOR = 55
    }
}
