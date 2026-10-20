package de.littletrotter.shared.receipt.reduce

import de.littletrotter.shared.receipt.DocumentSize
import de.littletrotter.shared.receipt.ReceiptRaster
import de.littletrotter.shared.receipt.atLeastReadable
import kotlin.math.roundToInt

/** What the reducer did to a PDF; each stage keeps less of the original than the one before. */
enum class PdfReductionStage { NONE, IMAGES_DOWNSAMPLED, RASTERISED }

/**
 * Outcome of [PdfReceiptReducer.reduce]. [bytes] is the file to store: the input itself when it
 * already fit, otherwise the smallest version the reducer could produce. [meetsTarget] is false when
 * even that exceeds the target, so the caller can warn instead of refusing. [dpi] is the resolution
 * relative to the display size the result was produced at, 0 when nothing ran. [pageCount] is known
 * only when the pages were rendered (stage [PdfReductionStage.RASTERISED] was tried), otherwise 0.
 */
class PdfReduction(
    val bytes: ByteArray,
    val stage: PdfReductionStage,
    val dpi: Int,
    val meetsTarget: Boolean,
    val pageCount: Int = 0,
) {
    val size: DocumentSize get() = DocumentSize.ofBytes(bytes.size.toLong())
}

/**
 * Brings a PDF towards a target size in two stages, stopping as soon as the target is met: oversized
 * embedded images are re-encoded (text and vectors stay), then every page is rendered to one image
 * (the text layer is lost). Resolution is always judged against the size the page is shown at
 * ([de.littletrotter.shared.receipt.ReceiptDisplaySize]: original size, shrunk to the A4 print area,
 * never enlarged), not against the page size stored in the file — a phone photo exported with a
 * huge page size is therefore reduced like any other scan. A [raster] below the readability floor
 * is lifted to it.
 *
 * The result replaces the upload; no second copy is kept. A target that cannot be reached yields the
 * smaller of the upload and the rasterised version.
 */
class PdfReceiptReducer(raster: ReceiptRaster = ReceiptRaster.DEFAULT) {

    private val raster = raster.atLeastReadable()

    fun reduce(pdfData: ByteArray, target: DocumentSize): PdfReduction {
        if (DocumentSize.ofBytes(pdfData.size.toLong()) <= target) {
            return PdfReduction(pdfData, PdfReductionStage.NONE, dpi = 0, meetsTarget = true)
        }
        val dpi = raster.dpi.roundToInt()
        val downsampled = ImageDownsampler.downsample(pdfData, raster)
        if (DocumentSize.ofBytes(downsampled.size.toLong()) <= target) {
            return PdfReduction(downsampled, PdfReductionStage.IMAGES_DOWNSAMPLED, dpi, meetsTarget = true)
        }
        val rasterised = PageRasteriser.rasterise(pdfData, raster)
        if (DocumentSize.ofBytes(rasterised.bytes.size.toLong()) <= target) {
            return PdfReduction(rasterised.bytes, PdfReductionStage.RASTERISED, dpi, meetsTarget = true, pageCount = rasterised.pageCount)
        }
        // Out of reach: never store more than was uploaded.
        return if (rasterised.bytes.size < pdfData.size) {
            PdfReduction(rasterised.bytes, PdfReductionStage.RASTERISED, dpi, meetsTarget = false, pageCount = rasterised.pageCount)
        } else {
            PdfReduction(pdfData, PdfReductionStage.NONE, dpi = 0, meetsTarget = false, pageCount = rasterised.pageCount)
        }
    }
}
