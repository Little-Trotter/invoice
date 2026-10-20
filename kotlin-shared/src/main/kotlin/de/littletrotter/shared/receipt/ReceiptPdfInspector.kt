package de.littletrotter.shared.receipt

import org.apache.pdfbox.Loader
import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSNumber
import org.apache.pdfbox.pdfparser.PDFStreamParser
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.rendering.PDFRenderer
import kotlin.math.abs

/**
 * Reads a receipt PDF's [ReceiptPageProfile] from its page boxes and page structure. A page counts as an image page only if it is nothing but one plain JPEG drawn
 * over the whole page at a resolution the print raster does not exceed; only then the print can
 * copy the JPEG as it is. Everything else is a document that gets rendered at print time; its
 * pages are rendered once here, exactly as the print will, to record what they weigh in the annex.
 *
 * Shared by the upload path (which persists the profile) and the export worker (which re-checks
 * the structure before importing), so both sides use the same rule.
 */
class ReceiptPdfInspector(private val raster: ReceiptRaster = ReceiptRaster.DEFAULT) {

    private val rasterizer = ReceiptPageRasterizer(raster)

    /**
     * @throws java.io.IOException when [pdf] cannot be parsed
     * @throws IllegalArgumentException when [pdf] has no page or a page has no valid size
     */
    fun inspect(pdf: ByteArray): ReceiptPageProfile =
        Loader.loadPDF(pdf).use { document ->
            val pageCount = document.numberOfPages
            require(pageCount >= 1) { "The PDF has no pages." }
            var area = 0.0
            var allImages = true
            for (page in document.pages) {
                val display = displaySize(page)
                area += display.width.toDouble() * display.height / A4_AREA
                if (allImages && importableImage(page) == null) allImages = false
            }
            if (allImages) {
                ReceiptPageProfile(pageCount, area, ReceiptRasterKind.IMAGE_PDF, pdf.size.toLong())
            } else {
                ReceiptPageProfile(pageCount, area, ReceiptRasterKind.DOCUMENT, renderedAnnexBytes(document))
            }
        }

    /** The JPEG total of all pages rendered at the print raster, one page in memory at a time. */
    private fun renderedAnnexBytes(document: PDDocument): Long {
        val renderer = PDFRenderer(document)
        var total = 0L
        for ((index, page) in document.pages.withIndex()) {
            PDDocument().use { scratch ->
                val image = rasterizer.render(renderer, index, displaySize(page), page)
                total += rasterizer.jpegBytes(rasterizer.encode(scratch, image))
            }
        }
        return maxOf(total, 1L)
    }

    /** The page's only JPEG when the page can be imported without rendering, otherwise `null`. */
    fun importableImage(page: PDPage): PDImageXObject? {
        val image = singlePlainJpeg(page) ?: return null
        val box = page.cropBox
        // Measured against the page's own size, not the shrunk display size: a full-bleed scan at
        // the print dpi is copied as it is instead of being re-rendered at a lower resolution.
        val effectiveDpi = image.width / (box.width / POINTS_PER_INCH)
        val coversPage = drawnOverWholePage(page, box)
        return image.takeIf { page.rotation == 0 && coversPage && effectiveDpi <= raster.dpi * DPI_TOLERANCE }
    }

    private fun displaySize(page: PDPage): ReceiptDisplaySize =
        page.cropBox.let { ReceiptDisplaySize.ofRotated(it.width, it.height, page.rotation) }

    private fun singlePlainJpeg(page: PDPage): PDImageXObject? {
        val resources = page.resources ?: return null
        val names = resources.xObjectNames.toList()
        val image = names.singleOrNull()?.let { resources.getXObject(it) } as? PDImageXObject
        val plain = image != null && !resources.fontNames.iterator().hasNext() && isPlainJpeg(image)
        return image.takeIf { plain }
    }

    private fun isPlainJpeg(image: PDImageXObject): Boolean {
        val dictionary = image.cosObject
        val deviceColour = image.colorSpace == PDDeviceRGB.INSTANCE || image.colorSpace == PDDeviceGray.INSTANCE
        return image.suffix == "jpg" && image.stream.filters.size == 1 && deviceColour &&
            !dictionary.containsKey(COSName.SMASK) && !dictionary.containsKey(COSName.MASK) &&
            !dictionary.containsKey(COSName.DECODE)
    }

    /** True for a content stream that is exactly `q? cm Do Q?` with the matrix spanning the box. */
    private fun drawnOverWholePage(page: PDPage, box: PDRectangle): Boolean {
        val tokens: List<Any> = PDFStreamParser(page).parse()
        val operators = tokens.filterIsInstance<Operator>()
        val drawsOnce = operators.count { it.name == "Do" } == 1 && operators.count { it.name == "cm" } == 1
        val onlyDrawing = operators.all { it.name in DRAWING_OPERATORS }
        val matrix = tokens.filterIsInstance<COSNumber>().map { it.floatValue() }
        if (!drawsOnce || !onlyDrawing || matrix.size != MATRIX_VALUES) return false
        val width = box.width
        val height = box.height
        val a = matrix[MATRIX_A]
        val b = matrix[MATRIX_B]
        val c = matrix[MATRIX_C]
        val d = matrix[MATRIX_D]
        val e = matrix[MATRIX_E]
        val f = matrix[MATRIX_F]
        return abs(b) < EPSILON && abs(c) < EPSILON &&
            near(a, width, width) && near(d, height, height) &&
            near(e, box.lowerLeftX, width) && near(f, box.lowerLeftY, height)
    }

    private fun near(value: Float, expected: Float, extent: Float): Boolean = abs(value - expected) <= extent * POSITION_TOLERANCE

    private companion object {
        const val POINTS_PER_INCH = 72f
        const val DPI_TOLERANCE = 1.02f
        const val POSITION_TOLERANCE = 0.02f
        const val EPSILON = 1e-3f
        const val MATRIX_VALUES = 6
        const val MATRIX_A = 0
        const val MATRIX_B = 1
        const val MATRIX_C = 2
        const val MATRIX_D = 3
        const val MATRIX_E = 4
        const val MATRIX_F = 5
        const val A4_AREA = ReceiptDisplaySize.A4_SHORT_SIDE.toDouble() * ReceiptDisplaySize.A4_LONG_SIDE
        val DRAWING_OPERATORS = setOf("q", "Q", "cm", "Do")
    }
}
