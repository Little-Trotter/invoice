package de.littletrotter.infrastructure.receipt

import de.littletrotter.application.invoice.port.outbound.ReceiptPdfReducer
import de.littletrotter.application.invoice.port.outbound.ReceiptReduction
import de.littletrotter.domain.masterdata.ReceiptNormalisationStage
import de.littletrotter.shared.receipt.DocumentSize
import de.littletrotter.shared.receipt.ReceiptRaster
import de.littletrotter.shared.receipt.reduce.PdfReceiptReducer
import de.littletrotter.shared.receipt.reduce.PdfReductionStage
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Adapter for [ReceiptPdfReducer] over the reducer shared with the quote side: it only supplies the
 * configured raster and maps the shared outcome to the port's. A file already at or below the target
 * is returned unchanged ([ReceiptNormalisationStage.NONE]).
 */
@ApplicationScoped
class PdfBoxReceiptPdfReducer @Inject constructor(
    // `@param:` keeps the config annotation on the constructor parameter only.
    @param:ConfigProperty(name = "receipt.raster.dpi", defaultValue = "150") dpi: Float,
    @param:ConfigProperty(name = "receipt.raster.jpeg-quality", defaultValue = "0.75") jpegQuality: Float,
) : ReceiptPdfReducer {

    constructor(raster: ReceiptRaster) : this(raster.dpi, raster.jpegQuality)

    constructor() : this(ReceiptRaster.DEFAULT)

    private val reducer = PdfReceiptReducer(ReceiptRaster(dpi, jpegQuality))

    override fun reduce(pdfData: ByteArray, target: DocumentSize): ReceiptReduction {
        val result = reducer.reduce(pdfData, target)
        val stage = stageOf(result.stage)
        return if (result.meetsTarget) {
            ReceiptReduction.Reduced(result.bytes, stage, result.dpi)
        } else {
            ReceiptReduction.StillTooLarge(result.pageCount, result.size, result.bytes, stage)
        }
    }

    private fun stageOf(stage: PdfReductionStage): ReceiptNormalisationStage =
        when (stage) {
            PdfReductionStage.NONE -> ReceiptNormalisationStage.NONE
            PdfReductionStage.IMAGES_DOWNSAMPLED -> ReceiptNormalisationStage.IMAGES_DOWNSAMPLED
            PdfReductionStage.RASTERISED -> ReceiptNormalisationStage.RASTERISED
        }
}
