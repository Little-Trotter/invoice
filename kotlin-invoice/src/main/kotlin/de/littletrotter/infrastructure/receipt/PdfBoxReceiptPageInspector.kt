package de.littletrotter.infrastructure.receipt

import de.littletrotter.application.invoice.port.outbound.ReceiptPageInspector
import de.littletrotter.application.invoice.port.outbound.ReceiptPagePreview
import de.littletrotter.shared.receipt.ReceiptDisplaySize
import de.littletrotter.shared.receipt.ReceiptPageProfile
import de.littletrotter.shared.receipt.ReceiptPdfInspector
import de.littletrotter.shared.receipt.ReceiptRaster
import de.littletrotter.shared.receipt.atLeastReadable
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.ImageIO

/**
 * Adapter for [ReceiptPageInspector] and [ReceiptPagePreview]. The inspector uses the same raster
 * values as the reducer, so a receipt reduced here is recognised as an image receipt.
 */
@ApplicationScoped
class PdfBoxReceiptPageInspector @Inject constructor(
    // `@param:` keeps the config annotation on the constructor parameter only.
    @param:ConfigProperty(name = "receipt.raster.dpi", defaultValue = "150") dpi: Float,
    @param:ConfigProperty(name = "receipt.raster.jpeg-quality", defaultValue = "0.75") jpegQuality: Float,
) : ReceiptPageInspector, ReceiptPagePreview {

    constructor(raster: ReceiptRaster) : this(raster.dpi, raster.jpegQuality)

    constructor() : this(ReceiptRaster.DEFAULT)

    private val inspector = ReceiptPdfInspector(ReceiptRaster(dpi, jpegQuality).atLeastReadable())

    override fun inspect(pdfData: ByteArray): ReceiptPageProfile =
        try {
            inspector.inspect(pdfData)
        } catch (_: IOException) {
            ReceiptPageProfile.unmeasured(pdfData.size.toLong())
        } catch (_: IllegalArgumentException) {
            ReceiptPageProfile.unmeasured(pdfData.size.toLong())
        }

    override fun firstPagePng(pdfData: ByteArray): ByteArray? =
        try {
            Loader.loadPDF(pdfData).use { document ->
                val page = document.getPage(0)
                val box = page.cropBox
                val scale = ReceiptDisplaySize.ofRotated(box.width, box.height, page.rotation).scale
                val image = PDFRenderer(document).renderImageWithDPI(0, PREVIEW_DPI * scale, ImageType.RGB)
                ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private companion object {
        const val PREVIEW_DPI = 60f
    }
}
