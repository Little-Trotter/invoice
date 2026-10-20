package de.littletrotter.shared.receipt

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.image.BufferedImage
import kotlin.math.sqrt

/**
 * Turns one receipt page into the JPEG the annex shows. The print and the upload measurement of
 * the annex size both go through this class, so the bytes measured at upload are the bytes the
 * print produces: same resolution relative to the display size, same pixel bound, same quality.
 */
class ReceiptPageRasterizer(private val raster: ReceiptRaster) {

    /**
     * Renders page [pageIndex] of [renderer] for [sourcePage], shown at [display].
     *
     * @throws java.io.IOException when the page cannot be rendered
     */
    fun render(renderer: PDFRenderer, pageIndex: Int, display: ReceiptDisplaySize, sourcePage: PDPage): BufferedImage =
        renderer.renderImageWithDPI(pageIndex, boundedDpi(display, sourcePage))

    /** The rendered [image] as a JPEG image object of [output] at the raster's quality. */
    fun encode(output: PDDocument, image: BufferedImage): PDImageXObject =
        JPEGFactory.createFromImage(output, image, raster.jpegQuality)

    /** Bytes of the JPEG stream of [image]; what the page weighs in the PDF. */
    fun jpegBytes(image: PDImageXObject): Long =
        image.cosObject.createRawInputStream().use { it.readAllBytes().size.toLong() }

    /** The raster resolution for a page: the configured dpi relative to its display size, lowered if the bitmap would exceed the pixel bound. */
    fun boundedDpi(display: ReceiptDisplaySize, sourcePage: PDPage): Float {
        val renderDpi = display.renderDpi(raster.dpi)
        val box = sourcePage.cropBox
        val pixels = box.width.toDouble() * box.height * renderDpi * renderDpi / (POINTS_PER_INCH * POINTS_PER_INCH)
        if (pixels <= raster.maxPixels) return renderDpi
        return (renderDpi * sqrt(raster.maxPixels / pixels)).toFloat()
    }

    private companion object {
        const val POINTS_PER_INCH = 72f
    }
}
