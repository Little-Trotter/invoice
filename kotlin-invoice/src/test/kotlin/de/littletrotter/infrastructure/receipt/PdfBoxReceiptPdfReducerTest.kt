package de.littletrotter.infrastructure.receipt

import de.littletrotter.application.invoice.port.outbound.ReceiptReduction
import de.littletrotter.domain.masterdata.ReceiptNormalisationStage
import de.littletrotter.shared.receipt.DocumentSize
import de.littletrotter.shared.receipt.ReceiptSizePolicy
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Random
import kotlin.math.roundToInt

private const val POINTS_PER_INCH = 72f
private const val POINTS_PER_CM = POINTS_PER_INCH / 2.54f

/**
 * PDFBox-backed, black-box tests of [PdfBoxReceiptPdfReducer]'s public contract: every fixture is
 * built with PDFBox in the test itself (no external files), and `target` is chosen per scenario
 * instead of relying on `ReceiptSizePolicy.TARGET_RECEIPT_SIZE` everywhere, so the two-pages-per-
 * stage-with-noise construction stays cheap while one case ([reduce down-samples an oversized
 * embedded colour image, keeping text and colour]) still exercises the real 768 KiB target.
 */
class PdfBoxReceiptPdfReducerTest {

    private val reducer = PdfBoxReceiptPdfReducer()

    @Test
    fun `reduce returns a small PDF unchanged at stage NONE`() {
        val pdf = buildTextOnlyPdf("Kleinbetrag Taxi 12,40 EUR")

        val result = reducer.reduce(pdf, ReceiptSizePolicy.TARGET_RECEIPT_SIZE)

        check(result is ReceiptReduction.Reduced)
        assertEquals(ReceiptNormalisationStage.NONE, result.stage)
        assertTrue(pdf.contentEquals(result.bytes))
    }

    @Test
    fun `reduce down-samples an oversized embedded colour image, keeping text and colour`() {
        val displayPoints = 5f * POINTS_PER_CM
        val pixelSize = (600f / POINTS_PER_INCH * displayPoints).roundToInt()
        val text = "Hotel Berlin Rechnung 2026-03-14"
        val pdf = buildTextWithLosslessImagePdf(text, pixelSize, displayPoints)
        assertTrue(
            DocumentSize.ofBytes(pdf.size.toLong()) > ReceiptSizePolicy.TARGET_RECEIPT_SIZE,
            "fixture must start above the target for this test to mean anything",
        )

        val result = reducer.reduce(pdf, ReceiptSizePolicy.TARGET_RECEIPT_SIZE)

        check(result is ReceiptReduction.Reduced)
        assertEquals(ReceiptNormalisationStage.IMAGES_DOWNSAMPLED, result.stage)
        assertTrue(DocumentSize.ofBytes(result.bytes.size.toLong()) <= ReceiptSizePolicy.TARGET_RECEIPT_SIZE)

        Loader.loadPDF(result.bytes).use { document ->
            assertEquals(text, PDFTextStripper().getText(document).trim())
            val image = firstImageOf(document.getPage(0))
            assertNotEquals(1, image.colorSpace.numberOfComponents, "the source was colour, stage 1 must not grey it out")
        }
    }

    @Test
    fun `reduce falls back to a grey rasterised page when downsampling images alone is not enough`() {
        val pageCount = 3
        val pdf = buildScanWithOrphanedImagePdf(pageCount)
        val target = DocumentSize.ofBytes(50L * 1024)

        val result = reducer.reduce(pdf, target)

        check(result is ReceiptReduction.Reduced)
        assertEquals(ReceiptNormalisationStage.RASTERISED, result.stage)
        assertTrue(DocumentSize.ofBytes(result.bytes.size.toLong()) <= target)

        Loader.loadPDF(result.bytes).use { document ->
            assertEquals(pageCount, document.numberOfPages, "rasterising must not change the page count")
            val image = firstImageOf(document.getPage(0))
            assertEquals(1, image.colorSpace.numberOfComponents, "an all-grey rendered page is re-encoded as grey")
        }
    }

    @Test
    fun `reduce rejects a document that stays too large after every stage, with its page count`() {
        val pageCount = 12
        val pdf = buildBlankPagesPdf(pageCount)

        val result = reducer.reduce(pdf, DocumentSize.ofBytes(1L))

        check(result is ReceiptReduction.StillTooLarge)
        assertEquals(pageCount, result.pageCount)
        assertTrue(result.resultingSize.bytes > 1L)
    }

    private fun firstImageOf(page: PDPage): PDImageXObject {
        val resources = page.resources
        val name = resources.xObjectNames.first()
        return resources.getXObject(name) as PDImageXObject
    }
}

private fun randomRgbImage(width: Int, height: Int, seed: Long): BufferedImage {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val random = Random(seed)
    val pixels = IntArray(width * height) { random.nextInt(0x1000000) }
    image.setRGB(0, 0, width, height, pixels, 0, width)
    return image
}

private fun uniformGreyImage(width: Int, height: Int, grey: Int): BufferedImage {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val rgb = (grey shl 16) or (grey shl 8) or grey
    val pixels = IntArray(width * height) { rgb }
    image.setRGB(0, 0, width, height, pixels, 0, width)
    return image
}

private fun buildTextOnlyPdf(text: String): ByteArray {
    PDDocument().use { document ->
        val page = PDPage(PDRectangle.A4)
        document.addPage(page)
        PDPageContentStream(document, page).use { contentStream ->
            contentStream.beginText()
            contentStream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 14f)
            contentStream.newLineAtOffset(50f, 700f)
            contentStream.showText(text)
            contentStream.endText()
        }
        return document.toByteArray()
    }
}

/** One page: [text] plus a random-noise colour image embedded losslessly at [pixelSize]x[pixelSize]
 *  pixels but displayed at [displayPoints]x[displayPoints] points — the mismatch is what gives it
 *  an effective DPI stage 1 needs to react to. Lossless + noise means the *embedded* bytes barely
 *  compress, which is what makes the fixture reliably start out oversized regardless of the JPEG
 *  library's exact compression ratio. */
private fun buildTextWithLosslessImagePdf(text: String, pixelSize: Int, displayPoints: Float): ByteArray {
    PDDocument().use { document ->
        val page = PDPage(PDRectangle.A4)
        document.addPage(page)
        val image = randomRgbImage(pixelSize, pixelSize, seed = 42L)
        PDPageContentStream(document, page).use { contentStream ->
            contentStream.beginText()
            contentStream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 14f)
            contentStream.newLineAtOffset(50f, 750f)
            contentStream.showText(text)
            contentStream.endText()
            val imageXObject = LosslessFactory.createFromImage(document, image)
            contentStream.drawImage(imageXObject, 50f, 50f, displayPoints, displayPoints)
        }
        return document.toByteArray()
    }
}

/**
 * [pageCount] pages, each covered edge-to-edge by a tiny uniform-grey image (so a 150 dpi render
 * of what is actually *painted* compresses to almost nothing) plus a large, lossless, high-entropy
 * image that is added to the page's resources but never painted via a `Do` operator. Stage 1 only
 * reacts to images it finds while walking the content stream's paint operators ([ImageSizeCollector]),
 * so the orphaned resource survives stage 1 completely unchanged and keeps the file oversized;
 * stage 2 discards it entirely because it renders only what a viewer would actually see.
 */
private fun buildScanWithOrphanedImagePdf(pageCount: Int): ByteArray {
    val pageSize = 200f
    PDDocument().use { document ->
        repeat(pageCount) { pageIndex ->
            val page = PDPage(PDRectangle(pageSize, pageSize))
            document.addPage(page)
            PDPageContentStream(document, page).use { contentStream ->
                val painted = uniformGreyImage(width = 20, height = 20, grey = 180)
                val paintedXObject = LosslessFactory.createFromImage(document, painted)
                contentStream.drawImage(paintedXObject, 0f, 0f, pageSize, pageSize)

                val orphan = randomRgbImage(width = 600, height = 600, seed = 1000L + pageIndex)
                val orphanXObject = LosslessFactory.createFromImage(document, orphan)
                page.resources.add(orphanXObject)
            }
        }
        return document.toByteArray()
    }
}

private fun buildBlankPagesPdf(pageCount: Int): ByteArray {
    PDDocument().use { document ->
        repeat(pageCount) { document.addPage(PDPage(PDRectangle.A4)) }
        return document.toByteArray()
    }
}

private fun PDDocument.toByteArray(): ByteArray {
    val out = ByteArrayOutputStream()
    save(out)
    return out.toByteArray()
}
