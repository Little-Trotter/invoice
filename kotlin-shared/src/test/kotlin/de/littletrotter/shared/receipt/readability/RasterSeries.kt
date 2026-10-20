package de.littletrotter.shared.receipt.readability

import java.awt.Image
import java.awt.image.BufferedImage
import java.awt.image.ConvolveOp
import java.awt.image.Kernel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.roundToInt

/** One raster configuration of the series: resolution relative to the display size, JPEG quality, colour or grey. */
data class RasterConfig(val dpi: Int, val quality: Float, val grey: Boolean)

/** Result of encoding and reading back one receipt in one [RasterConfig]. */
data class RasterResult(
    val kind: SyntheticReceiptKind,
    val config: RasterConfig,
    val bytes: Int,
    val ocrAccuracy: Double,
    val ssim: Double,
)

/** Encodes, decodes and scores receipts; shared by the measurement series and the readability gate. */
object RasterTrial {

    private const val OCR_DPI = 300
    private const val SSIM_REFERENCE_DPI = 200

    fun downscale(master: BufferedImage, dpi: Int): BufferedImage {
        val width = (master.width.toDouble() * dpi / SyntheticReceipts.MASTER_DPI).roundToInt()
        val height = (master.height.toDouble() * dpi / SyntheticReceipts.MASTER_DPI).roundToInt()
        val scaled = master.getScaledInstance(width, height, Image.SCALE_AREA_AVERAGING)
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.drawImage(scaled, 0, 0, null)
        g.dispose()
        return out
    }

    fun toGrey(image: BufferedImage): BufferedImage {
        val grey = BufferedImage(image.width, image.height, BufferedImage.TYPE_BYTE_GRAY)
        val g = grey.createGraphics()
        g.drawImage(image, 0, 0, null)
        g.dispose()
        return grey
    }

    fun encodeJpeg(image: BufferedImage, quality: Float): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality
        }
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            writer.write(null, IIOImage(image, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }

    /** OCR accuracy of [image] shown [widthInches] wide; the bitmap is upscaled to 300 dpi first, as OCR engines expect. */
    fun ocrAccuracy(image: BufferedImage, truth: String, widthInches: Double): Double {
        val width = (widthInches * OCR_DPI).roundToInt()
        val height = (image.height.toDouble() * width / image.width).roundToInt()
        val file = File.createTempFile("ocr", ".png")
        try {
            ImageIO.write(ImageMetrics.resample(image, width, height), "png", file)
            return TesseractOcr.accuracy(truth, TesseractOcr.recognise(file))
        } finally {
            file.delete()
        }
    }

    fun run(receipt: SyntheticReceipt, config: RasterConfig): RasterResult {
        val base = downscale(receipt.image, config.dpi)
        val jpeg = encodeJpeg(if (config.grey) toGrey(base) else base, config.quality)
        val decoded = ImageIO.read(ByteArrayInputStream(jpeg))
        val reference = downscale(receipt.image, SSIM_REFERENCE_DPI)
        return RasterResult(
            receipt.kind,
            config,
            jpeg.size,
            ocrAccuracy(decoded, receipt.text, receipt.widthInches),
            ImageMetrics.ssim(reference, decoded),
        )
    }

    fun blur(image: BufferedImage, sigma: Double): BufferedImage {
        if (sigma <= 0.0) return image
        val radius = ceil(sigma * 3).toInt()
        val size = 2 * radius + 1
        val weights = FloatArray(size * size)
        var sum = 0f
        for (y in -radius..radius) {
            for (x in -radius..radius) {
                val w = exp(-(x * x + y * y) / (2 * sigma * sigma)).toFloat()
                weights[(y + radius) * size + x + radius] = w
                sum += w
            }
        }
        for (i in weights.indices) weights[i] /= sum
        return ConvolveOp(Kernel(size, size, weights), ConvolveOp.EDGE_NO_OP, null).filter(image, null)
    }
}

/**
 * Measurement series behind the raster defaults: resolution x JPEG quality x colour per synthetic
 * receipt (size, OCR hit rate, SSIM), plus a sharpness calibration over source resolution and blur.
 * Needs Docker (tesseract runs in a container). Run with `java -cp <classes> de.littletrotter.shared.receipt.readability.RasterSeriesKt`; prints Markdown tables.
 */
fun main() {
    val pool = Executors.newFixedThreadPool(4)
    val receipts = SyntheticReceiptKind.entries.map { SyntheticReceipts.create(it) }
    val configs = listOf(100, 120, 150, 200).flatMap { dpi ->
        listOf(0.6f, 0.65f, 0.7f, 0.75f, 0.8f, 0.85f).flatMap { q -> listOf(false, true).map { RasterConfig(dpi, q, it) } }
    }
    val tasks = receipts.flatMap { r -> configs.map { c -> Callable { RasterTrial.run(r, c) } } }
    val results = pool.invokeAll(tasks).map { it.get() }
    println("## Series dpi x quality x colour")
    println("| kind | dpi | q | grey | bytes | OCR | SSIM |")
    println("|---|---|---|---|---|---|---|")
    for (r in results) {
        println("| ${r.kind} | ${r.config.dpi} | ${r.config.quality} | ${r.config.grey} | ${r.bytes} | ${"%.3f".format(r.ocrAccuracy)} | ${"%.3f".format(r.ssim)} |")
    }
    println()
    println("## Sharpness calibration (lossless)")
    println("| kind | variant | laplace variance | OCR |")
    println("|---|---|---|---|")
    val calibration = receipts.flatMap { r ->
        val sources = listOf(50, 75, 100, 125, 150, 200, 300).map { dpi -> "source $dpi dpi" to RasterTrial.downscale(r.image, dpi) }
        val blurred = listOf(0.7, 1.0, 1.5, 2.0, 3.0).map { s -> "blur sigma $s at 200 dpi" to RasterTrial.blur(RasterTrial.downscale(r.image, 200), s) }
        (sources + blurred).map { (label, image) ->
            Callable { listOf(r.kind, label, ImageMetrics.laplaceVariance(image, r.widthInches), RasterTrial.ocrAccuracy(image, r.text, r.widthInches)) }
        }
    }
    for (row in pool.invokeAll(calibration).map { it.get() }) {
        println("| ${row[0]} | ${row[1]} | ${"%.1f".format(row[2] as Double)} | ${"%.3f".format(row[3] as Double)} |")
    }
    pool.shutdown()
}
