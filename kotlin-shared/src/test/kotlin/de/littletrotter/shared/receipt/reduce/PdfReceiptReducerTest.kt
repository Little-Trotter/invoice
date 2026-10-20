package de.littletrotter.shared.receipt.reduce

import de.littletrotter.shared.receipt.DocumentSize
import de.littletrotter.shared.receipt.ReceiptPdfInspector
import de.littletrotter.shared.receipt.ReceiptRaster
import de.littletrotter.shared.receipt.ReceiptRasterKind
import de.littletrotter.shared.receipt.ReceiptSizePolicy
import de.littletrotter.shared.receipt.readability.ReadabilityPolicy
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSFloat
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.common.function.PDFunction
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType2
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
import org.junit.jupiter.api.Assertions.assertFalse
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
 * PDFBox-backed, black-box tests of [PdfReceiptReducer]'s public contract: every fixture is
 * built with PDFBox in the test itself (no external files), and `target` is chosen per scenario
 * instead of relying on `ReceiptSizePolicy.TARGET_RECEIPT_SIZE` everywhere, so the two-pages-per-
 * stage-with-noise construction stays cheap while one case ([reduce down-samples an oversized
 * embedded colour image, keeping text and colour]) still exercises the real 440 KiB target.
 */
class PdfReceiptReducerTest {

    private val reducer = PdfReceiptReducer()

    @Test
    fun `reduce returns a small PDF unchanged at stage NONE`() {
        val pdf = buildTextOnlyPdf("Kleinbetrag Taxi 12,40 EUR")

        val result = reducer.reduce(pdf, ReceiptSizePolicy.TARGET_RECEIPT_SIZE)

        assertTrue(result.meetsTarget)
        assertEquals(PdfReductionStage.NONE, result.stage)
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

        assertTrue(result.meetsTarget)
        assertEquals(PdfReductionStage.IMAGES_DOWNSAMPLED, result.stage)
        assertTrue(DocumentSize.ofBytes(result.bytes.size.toLong()) <= ReceiptSizePolicy.TARGET_RECEIPT_SIZE)

        Loader.loadPDF(result.bytes).use { document ->
            assertEquals(text, PDFTextStripper().getText(document).trim())
            val image = firstImageOf(document.getPage(0))
            assertNotEquals(1, image.colorSpace.numberOfComponents, "the source was colour, stage 1 must not grey it out")
        }
    }

    @Test
    fun `reduce measures and down-samples an image on a page that also paints vector graphics`() {
        val displayPoints = 5f * POINTS_PER_CM
        val pixelSize = (600f / POINTS_PER_INCH * displayPoints).roundToInt()
        val pdf = buildVectorGraphicsWithImagePdf(pixelSize, displayPoints)
        assertTrue(
            DocumentSize.ofBytes(pdf.size.toLong()) > ReceiptSizePolicy.TARGET_RECEIPT_SIZE,
            "fixture must start above the target for this test to mean anything",
        )

        val result = reducer.reduce(pdf, ReceiptSizePolicy.TARGET_RECEIPT_SIZE)

        assertTrue(result.meetsTarget)
        assertEquals(PdfReductionStage.IMAGES_DOWNSAMPLED, result.stage)
        Loader.loadPDF(result.bytes).use { document ->
            val image = firstImageOf(document.getPage(0))
            assertTrue(image.width < pixelSize, "the painted image is scaled to its displayed size, vector operators do not disturb that")
        }
    }

    @Test
    fun `reduce falls back to a grey rasterised page when downsampling images alone is not enough`() {
        val pageCount = 3
        val pdf = buildScanWithOrphanedImagePdf(pageCount)
        val target = DocumentSize.ofBytes(50L * 1024)

        val result = reducer.reduce(pdf, target)

        assertTrue(result.meetsTarget)
        assertEquals(PdfReductionStage.RASTERISED, result.stage)
        assertTrue(DocumentSize.ofBytes(result.bytes.size.toLong()) <= target)

        Loader.loadPDF(result.bytes).use { document ->
            assertEquals(pageCount, document.numberOfPages, "rasterising must not change the page count")
            val image = firstImageOf(document.getPage(0))
            assertEquals(1, image.colorSpace.numberOfComponents, "an all-grey rendered page is re-encoded as grey")
        }
    }

    @Test
    fun `reduce returns the smallest version when the target is unreachable`() {
        val pageCount = 12
        val pdf = buildBlankPagesPdf(pageCount)

        val result = reducer.reduce(pdf, DocumentSize.ofBytes(1L))

        assertFalse(result.meetsTarget)
        assertTrue(result.bytes.size <= pdf.size, "never larger than the input")
        assertTrue(result.size.bytes > 1L)
        assertEquals(pageCount, result.pageCount)
        Loader.loadPDF(result.bytes).use { assertEquals(pageCount, it.numberOfPages) }
    }

    @Test
    fun `a rasterised result is recognised as an image receipt by the inspector`() {
        val pdf = buildScanWithOrphanedImagePdf(3)

        val result = reducer.reduce(pdf, DocumentSize.ofBytes(50L * 1024))

        assertTrue(result.meetsTarget)
        val profile = ReceiptPdfInspector().inspect(result.bytes)
        assertEquals(ReceiptRasterKind.IMAGE_PDF, profile.rasterKind)
        assertEquals(3, profile.pageCount)
    }

    @Test
    fun `a raster below the readability floor is lifted to it`() {
        val pdf = buildPhotoPagePdf(4000, 3000)

        val result = PdfReceiptReducer(ReceiptRaster(dpi = 20f, jpegQuality = 0.75f)).reduce(pdf, DocumentSize.ofBytes(50L * 1024))

        assertEquals(ReadabilityPolicy.MIN_EFFECTIVE_DPI.toInt(), result.dpi)
    }

    @Test
    fun `reduce shrinks a phone photo whose page size comes from 72 dpi instead of enlarging it`() {
        val pdf = buildPhotoPagePdf(4000, 3000)

        val result = reducer.reduce(pdf, ReceiptSizePolicy.TARGET_RECEIPT_SIZE)

        assertTrue(result.bytes.size < pdf.size)
        Loader.loadPDF(result.bytes).use { document ->
            val image = firstImageOf(document.getPage(0))
            assertTrue(image.width < 1500, "about 150 dpi at the A4 display size, was ${image.width} px")
        }
    }

    @Test
    fun `reduce fits a 12 megapixel photo on a 4000 pt page within the target`() {
        val pdf = buildPhotoPagePdf(4000, 3000)
        assertTrue(
            DocumentSize.ofBytes(pdf.size.toLong()) > ReceiptSizePolicy.TARGET_RECEIPT_SIZE,
            "fixture must start above the target for this test to mean anything",
        )

        val result = reducer.reduce(pdf, ReceiptSizePolicy.TARGET_RECEIPT_SIZE)

        assertTrue(result.meetsTarget)
        assertTrue(result.stage != PdfReductionStage.NONE)
        assertTrue(DocumentSize.ofBytes(result.bytes.size.toLong()) <= ReceiptSizePolicy.TARGET_RECEIPT_SIZE)
        Loader.loadPDF(result.bytes).use { document ->
            assertEquals(4000f, document.getPage(0).mediaBox.width, 0.5f)
        }
    }

    @Test
    fun `reduce brings a 600 dpi A4 scan down to about 150 dpi`() {
        val pixelWidth = (600f / POINTS_PER_INCH * PDRectangle.A4.width).roundToInt()
        val pixelHeight = (600f / POINTS_PER_INCH * PDRectangle.A4.height).roundToInt()
        val pdf = buildFullPageImagePdf(PDRectangle.A4, pixelWidth, pixelHeight)

        val result = reducer.reduce(pdf, ReceiptSizePolicy.TARGET_RECEIPT_SIZE)

        assertTrue(result.meetsTarget)
        assertEquals(PdfReductionStage.IMAGES_DOWNSAMPLED, result.stage)
        Loader.loadPDF(result.bytes).use { document ->
            val image = firstImageOf(document.getPage(0))
            val page = document.getPage(0).mediaBox
            val scale = displayScale(document.getPage(0))
            val dpi = image.width / (page.width * scale / POINTS_PER_INCH)
            assertEquals(150f, dpi, 2f)
        }
    }

    @Test
    fun `a page without a usable size is left at scale 1`() {
        val page = PDPage(PDRectangle(0f, 0f))
        assertEquals(1f, displayScale(page))
        val rotated = PDPage(PDRectangle(300f, 500f)).also { it.rotation = 90 }
        assertEquals(1f, displayScale(rotated))
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
 * One page painting every path operator the measuring engine has to tolerate (rectangle, clip,
 * lines, all three Bezier forms, close, stroke, fill variants, an axial shading) before drawing a
 * large lossless image.
 */
private fun buildVectorGraphicsWithImagePdf(pixelSize: Int, displayPoints: Float): ByteArray {
    PDDocument().use { document ->
        val page = PDPage(PDRectangle.A4)
        document.addPage(page)
        val image = randomRgbImage(pixelSize, pixelSize, seed = 7L)
        PDPageContentStream(document, page).use { stream ->
            stream.addRect(5f, 5f, 580f, 830f)
            stream.clip()
            stream.moveTo(20f, 20f)
            stream.lineTo(120f, 20f)
            stream.curveTo(140f, 40f, 160f, 60f, 180f, 20f)
            stream.curveTo1(200f, 40f, 220f, 20f)
            stream.curveTo2(240f, 40f, 260f, 20f)
            stream.closePath()
            stream.stroke()
            stream.addRect(30f, 300f, 60f, 60f)
            stream.fill()
            stream.addRect(100f, 300f, 60f, 60f)
            stream.fillEvenOdd()
            stream.addRect(170f, 300f, 60f, 60f)
            stream.fillAndStroke()
            stream.shadingFill(axialShading())
            val imageXObject = LosslessFactory.createFromImage(document, image)
            stream.drawImage(imageXObject, 50f, 50f, displayPoints, displayPoints)
        }
        return document.toByteArray()
    }
}

private fun axialShading(): PDShadingType2 {
    val function = COSDictionary().apply {
        setInt(COSName.FUNCTION_TYPE, 2)
        setItem(COSName.DOMAIN, floatArray(0f, 1f))
        setItem(COSName.C0, floatArray(0f, 0f, 0f))
        setItem(COSName.C1, floatArray(1f, 1f, 1f))
        setInt(COSName.N, 1)
    }
    return PDShadingType2(COSDictionary()).apply {
        shadingType = PDShading.SHADING_TYPE2
        colorSpace = PDDeviceRGB.INSTANCE
        coords = floatArray(0f, 0f, 100f, 0f)
        this.function = PDFunction.create(function)
    }
}

private fun floatArray(vararg values: Float): COSArray = COSArray().apply { values.forEach { add(COSFloat(it)) } }

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

/** Gradient with weak per-pixel noise: large when stored losslessly, still compressible as JPEG. */
private fun noisyPhoto(width: Int, height: Int): BufferedImage {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val random = Random(7L)
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val noise = random.nextInt(NOISE_RANGE)
            val red = (x * 255 / width + noise).coerceAtMost(255)
            val green = (y * 255 / height + noise).coerceAtMost(255)
            val blue = ((x + y) * 255 / (width + height) + noise).coerceAtMost(255)
            pixels[y * width + x] = (red shl 16) or (green shl 8) or blue
        }
    }
    image.setRGB(0, 0, width, height, pixels, 0, width)
    return image
}

private const val NOISE_RANGE = 12

private fun buildFullPageImagePdf(size: PDRectangle, pixelWidth: Int, pixelHeight: Int): ByteArray {
    PDDocument().use { document ->
        val page = PDPage(size)
        document.addPage(page)
        val image = LosslessFactory.createFromImage(document, noisyPhoto(pixelWidth, pixelHeight))
        PDPageContentStream(document, page).use { it.drawImage(image, 0f, 0f, size.width, size.height) }
        return document.toByteArray()
    }
}

/** A photo exported with 72 dpi: one pixel per point, so the page is as large as the picture. */
private fun buildPhotoPagePdf(pixelWidth: Int, pixelHeight: Int): ByteArray =
    buildFullPageImagePdf(PDRectangle(pixelWidth.toFloat(), pixelHeight.toFloat()), pixelWidth, pixelHeight)
