package de.littletrotter.xpdf.impl

import de.littletrotter.shared.InvoiceDto
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.mustangproject.ZUGFeRD.ZUGFeRDExporterFromA3
import java.io.ByteArrayOutputStream

/**
 * The Mustang-route-only PDF finishing step: embed the ZUGFeRD XML (via
 * `ZUGFeRDExporterFromA3`, BYOPDF) into the shared base document. The XML alone carries every
 * beleg linked to the invoice (Base64, BG-24 `AdditionalReferencedDocument`) — this step never
 * attaches a beleg to the PDF itself a second time, see [BasePdfStage]'s own class doc for why.
 * Split out of [PdfExportServiceImpl] (one reason to change: the Mustang/ZUGFeRD exporter
 * library's own API) — the Factur-X route ([FacturXExportPipeline]) never calls this class at
 * all, exactly as before this split.
 */
@ApplicationScoped
class MustangPdfRenderer @Inject constructor(
    private val basePdfStage: BasePdfStage,
    private val xmlExportService: XmlExportServiceImpl,
) {
    // The shared base-PDF stage (template render). Mustang's own step, ZUGFeRD
    // embedding, starts from its result unchanged.
    //
    // Any failure here (an unrelated ZUGFeRD/XML embedding failure) must fail the job outright,
    // never fall back silently to the base PDF (which would violate the hybrid invoice format
    // promise: EN 16931 hybrid requires embedded XML) — so this deliberately has no catch block
    // of its own and lets every exception propagate unchanged; the real cause flows into
    // last_error via the same path as every other stage failure (JobConsumer catches and
    // publishes FAILED with error text).
    internal fun renderPdfWithZugferd(invoice: InvoiceDto, xmlBytes: ByteArray): ByteArray {
        val basePdfBytes = basePdfStage.renderBase(invoice)
        val pdfOut = ByteArrayOutputStream()
        ZUGFeRDExporterFromA3().use { exporter ->
            exporter.load(basePdfBytes)
            exporter.setProfile(xmlExportService.resolveProfile(invoice))
            exporter.setXML(xmlBytes)
            exporter.export(pdfOut)
        }
        return pdfOut.toByteArray()
    }

    /** The embedded XML's filename, per Mustang's own convention. Hardcoded
     *  rather than calling `ZUGFeRDExporterFromA3().getFilenameForVersion(...)` (verified
     *  empirically via that exact call: `"factur-x.xml"` for EN16931, `"xrechnung.xml"` for
     *  XRechnung) — constructing a second exporter instance here has no purpose beyond reading
     *  this constant, and would be a second, unnecessary `ZUGFeRDExporterFromA3()` construction
     *  in every export (and, in tests using `Mockito.mockConstruction` on that class, an
     *  unexpected extra mocked instance). */
    internal fun expectedZugferdAttachmentFilename(profile: String): String =
        if (profile == "XRechnung") "xrechnung.xml" else "factur-x.xml"
}
