package de.littletrotter.infrastructure.receipt

import de.littletrotter.application.invoice.port.outbound.ReceiptReduction
import de.littletrotter.domain.masterdata.ReceiptNormalisationStage
import de.littletrotter.shared.receipt.DocumentSize
import de.littletrotter.shared.receipt.ReceiptRaster
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Random

/**
 * The reduction itself is tested with the shared reducer; this checks only that the adapter maps
 * its outcome to the port's and passes the configured raster on.
 */
class PdfBoxReceiptPdfReducerTest {

    private val reducer = PdfBoxReceiptPdfReducer()

    @Test
    fun `a small PDF is returned unchanged at stage NONE`() {
        val pdf = scanPdf(20, 20, PDRectangle.A4)

        val result = reducer.reduce(pdf, DocumentSize.ofBytes(pdf.size.toLong()))

        check(result is ReceiptReduction.Reduced)
        assertEquals(ReceiptNormalisationStage.NONE, result.stage)
        assertEquals(0, result.dpi)
        assertTrue(pdf.contentEquals(result.bytes))
    }

    @Test
    fun `an oversized image is down-sampled and reported with the configured dpi`() {
        val pdf = scanPdf(1800, 2400, PDRectangle.A4)

        val result = PdfBoxReceiptPdfReducer(ReceiptRaster(dpi = 150f, jpegQuality = 0.75f))
            .reduce(pdf, DocumentSize.ofBytes(5L * 1024 * 1024))

        check(result is ReceiptReduction.Reduced)
        assertEquals(ReceiptNormalisationStage.IMAGES_DOWNSAMPLED, result.stage)
        assertEquals(150, result.dpi)
        assertTrue(result.bytes.size < pdf.size)
    }

    @Test
    fun `a rasterised page that fits is reported as RASTERISED`() {
        val pdf = orphanedImagePdf()

        val result = reducer.reduce(pdf, DocumentSize.ofBytes(50L * 1024))

        check(result is ReceiptReduction.Reduced)
        assertEquals(ReceiptNormalisationStage.RASTERISED, result.stage)
        assertEquals(150, result.dpi)
    }

    @Test
    fun `an unreachable target yields the smaller version with its page count`() {
        val pdf = scanPdf(1800, 2400, PDRectangle.A4)

        val result = reducer.reduce(pdf, DocumentSize.ofBytes(1L))

        check(result is ReceiptReduction.StillTooLarge)
        assertEquals(1, result.pageCount)
        assertEquals(result.resultingSize.bytes, result.bytes.size.toLong())
        assertTrue(result.bytes.size <= pdf.size, "the version kept is never larger than the upload")
    }

    private fun scanPdf(width: Int, height: Int, pageSize: PDRectangle): ByteArray {
        PDDocument().use { document ->
            val page = PDPage(pageSize)
            document.addPage(page)
            val image = noise(width, height)
            PDPageContentStream(document, page).use {
                it.drawImage(LosslessFactory.createFromImage(document, image), 0f, 0f, pageSize.width, pageSize.height)
            }
            return document.toByteArray()
        }
    }

    /** A grey painted page plus a large unpainted noise image: only rasterising drops the latter. */
    private fun orphanedImagePdf(): ByteArray {
        PDDocument().use { document ->
            val page = PDPage(PDRectangle(200f, 200f))
            document.addPage(page)
            PDPageContentStream(document, page).use {
                val grey = BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB)
                grey.setRGB(0, 0, 20, 20, IntArray(400) { 0xB4B4B4 }, 0, 20)
                it.drawImage(LosslessFactory.createFromImage(document, grey), 0f, 0f, 200f, 200f)
                page.resources.add(LosslessFactory.createFromImage(document, noise(600, 600)))
            }
            return document.toByteArray()
        }
    }

    private fun noise(width: Int, height: Int): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val random = Random(3L)
        image.setRGB(0, 0, width, height, IntArray(width * height) { random.nextInt(0x1000000) }, 0, width)
        return image
    }

    private fun PDDocument.toByteArray(): ByteArray = ByteArrayOutputStream().also { save(it) }.toByteArray()
}
