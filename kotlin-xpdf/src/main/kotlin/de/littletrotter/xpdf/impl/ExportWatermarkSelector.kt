package de.littletrotter.xpdf.impl

import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.resolveOutputIntentSubject
import de.littletrotter.shared.time.TimeSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.Optional

/**
 * Chooses the mark a customer-facing invoice PDF carries. Every PDF carries at most one of three
 * fixed marks, first match wins: the cancellation document itself ([InvoiceDto.cancellation]), a non-finalized
 * invoice ([InvoiceDto.draft]), or the brand mark otherwise. A cancelled original is never
 * regenerated (the single-generation gate), so its archived PDF simply keeps whichever mark it
 * already carried when it was issued. Each mark's text follows
 * [de.littletrotter.shared.InvoiceMetaDto.documentLanguage] (the frozen document language), never
 * the UI locale.
 *
 * Rows 1-2 are checked in that order and are both equally unconditional: a cancellation document and a
 * non-finalized invoice must never leave unstamped, and neither is ever suppressed. Do not reorder
 * these branches and do not make either of them conditional on anything.
 *
 * A configured demo text ([PdfWatermarkStamper.DEMO_TEXT_PROPERTY]) joins whichever mark applies
 * as one more line, and is stamped alone where no mark applies (a covered output intent), so every
 * PDF of a demo deployment carries it.
 *
 * Only the choice lives here; the stamp itself is drawn in the same PDFBox pass that appends the
 * receipt pages, before the ZUGFeRD embedding, so the validated PDF is the delivered PDF.
 */
@ApplicationScoped
class ExportWatermarkSelector @Inject constructor(
    private val outputIntentVerifier: OutputIntentVerifier,
    private val time: TimeSource,
    // Optional deployment-wide demo text (env LITTLETROTTER_PDF_WATERMARK_TEXT); unset means no
    // demo line. `@param:` pins the annotation to the constructor parameter only; the default is
    // a test seam for direct construction, never used by CDI.
    @param:ConfigProperty(name = PdfWatermarkStamper.DEMO_TEXT_PROPERTY)
    private val demoWatermarkText: Optional<String> = Optional.empty(),
) {

    /** The stamp for [invoice], or `null` when nothing is to be drawn (covered output intent, no demo text). */
    fun select(invoice: InvoiceDto, outputIntent: String?): PdfWatermarkStamper.Stamp? {
        val language = invoice.meta.documentLanguage
        val demo = demoWatermarkText.orElse(null)
        return when {
            invoice.cancellation -> PdfWatermarkStamper.markStamp(PdfWatermarkStamper.CANCELLATION_MARK, language, demo)
            invoice.draft -> PdfWatermarkStamper.markStamp(PdfWatermarkStamper.DRAFT_MARK, language, demo)
            outputIntentVerifier.covers(outputIntent, resolveOutputIntentSubject(invoice), time.today()) ->
                PdfWatermarkStamper.demoStamp(demo)
            else -> PdfWatermarkStamper.markStamp(PdfWatermarkStamper.BRAND_MARK, language, demo)
        }
    }
}
