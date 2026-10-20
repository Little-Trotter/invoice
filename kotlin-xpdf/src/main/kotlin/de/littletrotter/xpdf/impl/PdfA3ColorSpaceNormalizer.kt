package de.littletrotter.xpdf.impl

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent
import java.awt.color.ColorSpace
import java.awt.color.ICC_Profile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Found via a PO-reported live job — the Factur-X engine's `/v1/convert` round-trip loses
 * the PDF/A output intent [BasePdfStage.renderBase]'s document carries (Mustang keeps its own,
 * added internally by `ZUGFeRDExporterFromA3`). Without it, veraPDF flags ISO 19005-3:2012
 * clause 6.2.4.3 tests 2/4: DeviceRGB/DeviceGray used without either a device-independent
 * DefaultRGB/DefaultGray colour space having been set, or a PDF/A OutputIntent being present.
 *
 * Rather than hunting down every DeviceRGB/DeviceGray operator the engine's own renderer (or
 * [PdfWatermarkStamper]) emits, this restores the simpler, document-wide fix Mustang already
 * benefits from: one sRGB output intent at the catalog level satisfies clause 6.2.4.3 for every
 * unmanaged-colour-space operator in the document — including the watermark stamp's own
 * `Color(128, 128, 128)` fill, which PDFBox's `setNonStrokingColor(java.awt.Color)` always draws
 * as DeviceRGB (the `rg` operator), never DeviceGray, regardless of the colour being neutral.
 *
 * The ICC profile is the JVM's own built-in sRGB profile ([ColorSpace.CS_sRGB] via
 * [ICC_Profile.getInstance]) — no new dependency and no shipped `.icc` file: this project has
 * none today (Mustang builds its own internally), and the JRE's standard profile is exactly what
 * every other "add a PDF/A OutputIntent" recipe (including PDFBox's own reference example) uses.
 */
object PdfA3ColorSpaceNormalizer {

    private const val SRGB_DESCRIPTION = "sRGB IEC61966-2.1"

    /**
     * Returns [pdfBytes] unchanged when the document already carries an output intent (Mustang's
     * own bytes, or a future engine that already gets this right) — no re-save for nothing.
     * Otherwise adds one sRGB output intent at the catalog level and returns the re-saved bytes.
     */
    fun ensureSrgbOutputIntent(pdfBytes: ByteArray): ByteArray =
        Loader.loadPDF(pdfBytes).use { document ->
            if (document.documentCatalog.outputIntents.isNotEmpty()) {
                pdfBytes
            } else {
                val profile = ICC_Profile.getInstance(ColorSpace.CS_sRGB)
                val outputIntent = PDOutputIntent(document, ByteArrayInputStream(profile.data))
                outputIntent.info = SRGB_DESCRIPTION
                outputIntent.outputCondition = SRGB_DESCRIPTION
                outputIntent.outputConditionIdentifier = SRGB_DESCRIPTION
                outputIntent.registryName = "http://www.color.org"
                document.documentCatalog.addOutputIntent(outputIntent)

                val out = ByteArrayOutputStream()
                document.save(out)
                out.toByteArray()
            }
        }
}
