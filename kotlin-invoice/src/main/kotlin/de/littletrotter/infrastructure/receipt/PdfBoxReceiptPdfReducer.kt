package de.littletrotter.infrastructure.receipt

import de.littletrotter.application.invoice.port.outbound.ReceiptPdfReducer
import de.littletrotter.application.invoice.port.outbound.ReceiptReduction
import de.littletrotter.domain.masterdata.ReceiptNormalisationStage
import de.littletrotter.shared.receipt.DocumentSize
import jakarta.enterprise.context.ApplicationScoped
import org.apache.pdfbox.Loader
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine
import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.contentstream.operator.OperatorName
import org.apache.pdfbox.cos.COSBase
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.apache.pdfbox.pdmodel.graphics.image.PDImage
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.RenderingHints
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/** Max DPI an embedded image (stage 1) or a rasterised page (stage 2) is reduced to. */
private const val TARGET_DPI = 150

/** JPEG re-encode quality for both stages — 0 (best compression) .. 1 (best quality). */
private const val JPEG_QUALITY = 0.75f

/** PDF user-space units are points; 72 of them make an inch. */
private const val POINTS_PER_INCH = 72f

/**
 * PDFBox 3.0.8 adapter for [ReceiptPdfReducer]: tries [ReceiptNormalisationStage.IMAGES_DOWNSAMPLED]
 * (re-encode oversized embedded images, keep text/vectors) then [ReceiptNormalisationStage.RASTERISED]
 * (render every page to a single image, losing the text layer), stopping as soon as the result meets
 * [target]. A file already at or below [target] is returned unchanged
 * ([ReceiptNormalisationStage.NONE]) — the pool never re-serializes a file that didn't need it.
 */
@ApplicationScoped
class PdfBoxReceiptPdfReducer : ReceiptPdfReducer {

    override fun reduce(pdfData: ByteArray, target: DocumentSize): ReceiptReduction {
        if (DocumentSize.ofBytes(pdfData.size.toLong()) <= target) {
            return ReceiptReduction.Reduced(pdfData, ReceiptNormalisationStage.NONE, dpi = 0)
        }

        val downsampled = ImageDownsampler.downsample(pdfData)
        if (DocumentSize.ofBytes(downsampled.size.toLong()) <= target) {
            return ReceiptReduction.Reduced(downsampled, ReceiptNormalisationStage.IMAGES_DOWNSAMPLED, TARGET_DPI)
        }

        val rasterised = PageRasteriser.rasterise(pdfData)
        val rasterisedSize = DocumentSize.ofBytes(rasterised.bytes.size.toLong())
        if (rasterisedSize <= target) {
            return ReceiptReduction.Reduced(rasterised.bytes, ReceiptNormalisationStage.RASTERISED, TARGET_DPI)
        }
        return ReceiptReduction.StillTooLarge(rasterised.pageCount, rasterisedSize)
    }
}

/**
 * Stage 1: walks each page's content stream to find every embedded image XObject's *displayed*
 * size (from the CTM at its `Do` invocation, [ImageSizeCollector]), and re-encodes any image whose
 * effective DPI (pixel size relative to that displayed size) exceeds [TARGET_DPI]. Text, vectors
 * and metadata are untouched. Returns [pdfData] unchanged (same bytes) when no image needed it —
 * the caller decides from the resulting size whether stage 2 is still needed.
 */
internal object ImageDownsampler {

    fun downsample(pdfData: ByteArray): ByteArray {
        Loader.loadPDF(pdfData).use { document ->
            var modified = false
            for (page in document.pages) {
                if (downsamplePage(document, page)) modified = true
            }
            if (!modified) return pdfData
            val out = ByteArrayOutputStream()
            document.save(out)
            return out.toByteArray()
        }
    }

    private fun downsamplePage(document: PDDocument, page: PDPage): Boolean {
        val resources = page.resources ?: return false
        val collector = ImageSizeCollector(page)
        collector.processPage(page)

        var modified = false
        for (name in resources.xObjectNames.toList()) {
            val xobject = resources.getXObject(name)
            val displayed = collector.displayedSizes[name]
            if (xobject is PDImageXObject && displayed != null) {
                val candidate = ImageCandidate(name, xobject, displayed)
                if (downsampleImage(document, resources, candidate)) modified = true
            }
        }
        return modified
    }

    private fun downsampleImage(document: PDDocument, resources: PDResources, candidate: ImageCandidate): Boolean {
        val (name, xobject, displayed) = candidate
        if (displayed.widthPoints <= 0f || displayed.heightPoints <= 0f) return false
        val effectiveDpi = xobject.width / (displayed.widthPoints / POINTS_PER_INCH)
        if (effectiveDpi <= TARGET_DPI) return false

        val targetWidthPx = pixelsAt(displayed.widthPoints)
        val targetHeightPx = pixelsAt(displayed.heightPoints)
        val grey = xobject.colorSpace.numberOfComponents == 1
        val scaled = scaledImage(xobject.image, targetWidthPx, targetHeightPx, grey)
        resources.put(name, JPEGFactory.createFromImage(document, scaled, JPEG_QUALITY))
        return true
    }

    private fun pixelsAt(displayedPoints: Float): Int =
        (displayedPoints / POINTS_PER_INCH * TARGET_DPI).roundToInt().coerceAtLeast(1)

    private fun scaledImage(source: BufferedImage, width: Int, height: Int, grey: Boolean): BufferedImage {
        val type = if (grey) BufferedImage.TYPE_BYTE_GRAY else BufferedImage.TYPE_INT_RGB
        val target = BufferedImage(width, height, type)
        val graphics = target.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        graphics.drawImage(source, 0, 0, width, height, null)
        graphics.dispose()
        return target
    }
}

/** An image XObject's displayed size on the page, in points (72 per inch) — read off the CTM in
 *  effect at its `Do` invocation, not off its own pixel dimensions. */
internal data class DisplayedSize(val widthPoints: Float, val heightPoints: Float)

/** One page resource entry [ImageDownsampler] found actually painted — bundled so
 *  `downsampleImage` stays under detekt's `LongParameterList` threshold. */
private data class ImageCandidate(val name: COSName, val xobject: PDImageXObject, val displayed: DisplayedSize)

/**
 * [PDFGraphicsStreamEngine] subclass that records, for every image XObject actually painted on the
 * page, the largest size it was displayed at (an image drawn more than once at different sizes is
 * sized for its most demanding use). Only [drawImage] and the `Do` bookkeeping in [processOperator]
 * matter here — every other callback the base class requires is a deliberate no-op, this engine
 * never renders anything, it only measures.
 */
private class ImageSizeCollector(page: PDPage) : PDFGraphicsStreamEngine(page) {

    val displayedSizes: MutableMap<COSName, DisplayedSize> = mutableMapOf()
    private var pendingImageName: COSName? = null

    override fun processOperator(operator: Operator, operands: List<COSBase>) {
        if (operator.name == OperatorName.DRAW_OBJECT) {
            pendingImageName = operands.firstOrNull() as? COSName
        }
        super.processOperator(operator, operands)
    }

    override fun drawImage(pdImage: PDImage) {
        val name = pendingImageName ?: return
        pendingImageName = null
        val ctm = graphicsState.currentTransformationMatrix
        val width = abs(ctm.scalingFactorX)
        val height = abs(ctm.scalingFactorY)
        val existing = displayedSizes[name]
        displayedSizes[name] = DisplayedSize(
            widthPoints = maxOf(existing?.widthPoints ?: 0f, width),
            heightPoints = maxOf(existing?.heightPoints ?: 0f, height),
        )
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

/**
 * Stage 2: renders every page to a [TARGET_DPI] JPEG and rebuilds a new PDF of the same page sizes
 * from those images — the text layer is lost, which is why this only runs once stage 1 alone isn't
 * enough. A page whose rendered pixels are all grey (r==g==b, sampled on a grid) is re-encoded as a
 * 1-component grey image rather than RGB.
 */
internal object PageRasteriser {

    /** Sampling grid for the grey check — coarse on purpose, this only decides an encoding, it
     *  never needs to inspect every pixel. */
    private const val GREY_SAMPLE_GRID = 48

    class Result(val bytes: ByteArray, val pageCount: Int)

    fun rasterise(pdfData: ByteArray): Result {
        Loader.loadPDF(pdfData).use { source ->
            val renderer = PDFRenderer(source)
            val pageCount = source.numberOfPages
            PDDocument().use { target ->
                for (pageIndex in 0 until pageCount) {
                    rasterisePage(source, renderer, target, pageIndex)
                }
                val out = ByteArrayOutputStream()
                target.save(out)
                return Result(out.toByteArray(), pageCount)
            }
        }
    }

    private fun rasterisePage(source: PDDocument, renderer: PDFRenderer, target: PDDocument, pageIndex: Int) {
        val mediaBox = source.getPage(pageIndex).mediaBox
        val rendered = renderer.renderImageWithDPI(pageIndex, TARGET_DPI.toFloat(), ImageType.RGB)
        val image = if (isGrey(rendered)) toGrey(rendered) else rendered

        val newPage = PDPage(mediaBox)
        target.addPage(newPage)
        val imageXObject = JPEGFactory.createFromImage(target, image, JPEG_QUALITY)
        PDPageContentStream(target, newPage).use { contentStream ->
            contentStream.drawImage(imageXObject, 0f, 0f, mediaBox.width, mediaBox.height)
        }
    }

    private fun isGrey(image: BufferedImage): Boolean {
        val strideX = (image.width / GREY_SAMPLE_GRID).coerceAtLeast(1)
        val strideY = (image.height / GREY_SAMPLE_GRID).coerceAtLeast(1)
        var y = 0
        while (y < image.height) {
            var x = 0
            while (x < image.width) {
                if (!isGreyPixel(image.getRGB(x, y))) return false
                x += strideX
            }
            y += strideY
        }
        return true
    }

    private fun isGreyPixel(rgb: Int): Boolean {
        val red = (rgb shr RED_SHIFT) and COLOR_MASK
        val green = (rgb shr GREEN_SHIFT) and COLOR_MASK
        val blue = rgb and COLOR_MASK
        return red == green && green == blue
    }

    private fun toGrey(image: BufferedImage): BufferedImage {
        val grey = BufferedImage(image.width, image.height, BufferedImage.TYPE_BYTE_GRAY)
        val graphics = grey.createGraphics()
        graphics.drawImage(image, 0, 0, null)
        graphics.dispose()
        return grey
    }

    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val COLOR_MASK = 0xFF
}
