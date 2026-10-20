package de.littletrotter.infrastructure.receipt

import de.littletrotter.application.invoice.port.outbound.ProbedPage
import de.littletrotter.application.invoice.port.outbound.ReceiptPageProbe
import de.littletrotter.shared.receipt.ReceiptDisplaySize
import de.littletrotter.shared.receipt.readability.ImageMetrics
import de.littletrotter.shared.receipt.readability.PageReadability
import jakarta.enterprise.context.ApplicationScoped
import org.apache.pdfbox.Loader
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.graphics.image.PDImage
import org.jboss.logging.Logger
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt

/** PDF user-space units are points; 72 of them make an inch. */
private const val POINTS_PER_INCH = 72.0

/** An image must cover at least this share of the page to count as the scan of the receipt. */
private const val MIN_PAGE_COVERAGE = 0.5

/** Decoded images are thinned to about this many times the sharpness reference resolution, bounding memory. */
private const val DECODE_OVERSAMPLING = 2

/** Longest side of the preview picture shown in the hint dialog. */
private const val PREVIEW_MAX_SIDE = 480

/**
 * PDFBox adapter for [ReceiptPageProbe]: finds the scan on each page (the image covering most of
 * it), relates its pixel width to the size the page is shown at (never enlarged, shrunk to the A4
 * print area, see [ReceiptDisplaySize]) and measures its sharpness. Pages without such an image,
 * such as text or vector pages, are reported as not measurable.
 */
@ApplicationScoped
class PdfBoxReceiptPageProbe : ReceiptPageProbe {

    private val log = Logger.getLogger(javaClass)

    override fun probe(pdfData: ByteArray, maxPages: Int): List<ProbedPage> = try {
        Loader.loadPDF(pdfData).use { document ->
            document.pages.take(maxPages).mapIndexed { index, page -> probePage(index + 1, page) }
        }
    } catch (e: IOException) {
        log.debugf(e, "Readability check skipped: the file could not be read as PDF")
        emptyList()
    }

    private fun probePage(pageNumber: Int, page: PDPage): ProbedPage {
        val box = page.cropBox
        val scale = try {
            ReceiptDisplaySize.ofRotated(box.width, box.height, page.rotation).scale.toDouble()
        } catch (_: IllegalArgumentException) {
            return ProbedPage(PageReadability(pageNumber, null, null), null)
        }
        val scan = PaintedImages(page).also { it.processPage(page) }.largest()
        if (scan == null || scan.areaPoints < MIN_PAGE_COVERAGE * box.width * box.height) {
            return ProbedPage(PageReadability(pageNumber, null, null), null)
        }
        val displayInches = scan.widthPoints * scale / POINTS_PER_INCH
        val effectiveDpi = scan.image.width / displayInches
        val bitmap = decodeThinned(scan.image, displayInches)
        val readability = PageReadability(pageNumber, effectiveDpi, ImageMetrics.laplaceVariance(bitmap, displayInches))
        return ProbedPage(readability, if (readability.suspect) preview(bitmap) else null)
    }

    private fun decodeThinned(image: PDImage, displayInches: Double): BufferedImage {
        val wanted = displayInches * ImageMetrics.SHARPNESS_REFERENCE_DPI * DECODE_OVERSAMPLING
        val subsampling = (image.width / wanted).toInt().coerceAtLeast(1)
        return image.getImage(null, subsampling)
    }

    private fun preview(bitmap: BufferedImage): ByteArray {
        val longest = maxOf(bitmap.width, bitmap.height)
        val factor = minOf(1.0, PREVIEW_MAX_SIDE.toDouble() / longest)
        val small = ImageMetrics.resample(bitmap, (bitmap.width * factor).roundToInt().coerceAtLeast(1), (bitmap.height * factor).roundToInt().coerceAtLeast(1))
        return ByteArrayOutputStream().also { ImageIO.write(small, "png", it) }.toByteArray()
    }
}

/** An image painted on a page together with its displayed size in points. */
private class PaintedImage(val image: PDImage, val widthPoints: Double, val heightPoints: Double) {
    val areaPoints: Double get() = widthPoints * heightPoints
}

/**
 * Records every image painted on one page with the size the current transformation matrix gives
 * it. It never renders; every path callback the base class requires is a deliberate no-op.
 */
private class PaintedImages(page: PDPage) : PDFGraphicsStreamEngine(page) {

    private val images = mutableListOf<PaintedImage>()

    fun largest(): PaintedImage? = images.maxByOrNull { it.areaPoints }

    override fun drawImage(pdImage: PDImage) {
        val matrix = graphicsState.currentTransformationMatrix
        images += PaintedImage(pdImage, abs(matrix.scalingFactorX).toDouble(), abs(matrix.scalingFactorY).toDouble())
    }

    override fun appendRectangle(p0: Point2D, p1: Point2D, p2: Point2D, p3: Point2D) = Unit
    override fun clip(windingRule: Int) = Unit
    override fun moveTo(x: Float, y: Float) = Unit
    override fun lineTo(x: Float, y: Float) = Unit
    override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = Unit
    override fun getCurrentPoint(): Point2D = Point2D.Float(0f, 0f)
    override fun closePath() = Unit
    override fun endPath() = Unit
    override fun strokePath() = Unit
    override fun fillPath(windingRule: Int) = Unit
    override fun fillAndStrokePath(windingRule: Int) = Unit
    override fun shadingFill(shadingName: COSName) = Unit
}
