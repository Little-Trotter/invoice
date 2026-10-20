package de.littletrotter.xpdf.impl

import de.littletrotter.shared.BasePdfRendererPort
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.ValidationStageResult
import de.littletrotter.shared.receipt.orderedDistinctReceipts
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * The base-PDF stage: render the invoice layout through [BasePdfRendererPort] (Typst), append the
 * receipts assigned to the invoice as rasterised pages ([ReceiptAnnexBuilder]) and run the PDF/A-3
 * baseline check ([PdfA3MinimalCheck]).
 *
 * The watermark joins that pass instead of a pass of its own after validation.
 *
 * The receipt pages exist so a reader of the PDF alone sees what the XML attachments (BG-24)
 * carry; the receipts are still embedded exactly once, as Base64 in the XML, never as PDF
 * attachments. [renderBase] is the one place the combined document is assembled, so every
 * engine-specific caller reaches the identical code.
 */
@ApplicationScoped
class BasePdfStage @Inject constructor(
    private val reportRenderer: BasePdfRendererPort,
    private val pdfA3Check: PdfA3MinimalCheck,
    private val receiptAnnexBuilder: ReceiptAnnexBuilder,
    private val timers: ExportStageTimers,
    private val workingSet: PdfBoxWorkingSet,
) {

    /** Test seam: a stage with the default in-memory threshold. */
    constructor(
        reportRenderer: BasePdfRendererPort,
        pdfA3Check: PdfA3MinimalCheck,
        receiptAnnexBuilder: ReceiptAnnexBuilder,
        timers: ExportStageTimers,
    ) : this(reportRenderer, pdfA3Check, receiptAnnexBuilder, timers, PdfBoxWorkingSet())

    /** Renders [invoice] into the shared, engine-agnostic base document: invoice pages followed by
     *  the receipt pages and, when given, the [stamp] on every page. Annex and stamp share one
     *  PDFBox pass, so the document that is validated later is the one that is delivered. Every
     *  engine starts from this exact byte stream, before any engine-specific XML embedding. A
     *  receipt that cannot be rendered fails the call. */
    fun renderBase(invoice: InvoiceDto, stamp: PdfWatermarkStamper.Stamp? = null): ByteArray {
        val rendered = timers.record(ExportStage.RENDER) { reportRenderer.render(invoice) }
        val hasReceipts = invoice.orderedDistinctReceipts().isNotEmpty()
        // Nothing to add: no load/save pass at all. A skipped stage is not timed, it would only
        // fill the series with zeros.
        if (!hasReceipts && stamp == null) return rendered
        return workingSet.load(rendered).use { document ->
            if (hasReceipts) timers.record(ExportStage.ANNEX) { receiptAnnexBuilder.appendTo(document, invoice.orderedDistinctReceipts()) }
            if (stamp != null) timers.record(ExportStage.WATERMARK) { PdfWatermarkStamper.stamp(document, stamp) }
            workingSet.save(document)
        }
    }

    /**
     * The result of [checkPdfA3]: the mandatory "PDFA" stage and the label of the engine that
     * produced it (for the report's version block).
     */
    data class PdfA3CheckOutcome(
        val stage: ValidationStageResult,
        val engineLabel: String,
    )

    /**
     * The PDF/A-3 baseline check, identical for every engine, run on the actually produced final
     * bytes (post engine-specific XML embedding) — never a re-render. Conformance in the sense of
     * the veraPDF profile is not decided here: it runs afterwards as its own service on the stored
     * document and joins the report as the VERAPDF stage. `skipValidation` mirrors every other
     * stage in this pipeline: explicitly recorded as skipped, never silently passed as if it had
     * run.
     */
    fun checkPdfA3(
        finalPdfBytes: ByteArray,
        expectedAttachmentFilename: String,
        skipValidation: Boolean,
    ): PdfA3CheckOutcome {
        if (skipValidation) {
            return PdfA3CheckOutcome(
                ValidationStageResult("PDFA", KositOutcome.VALID, skipped = true, skipReason = "skipValidation"),
                PDFA_ENGINE_LABEL,
            )
        }
        return timers.record(ExportStage.PDFA) {
            val result = pdfA3Check.check(finalPdfBytes, expectedAttachmentFilename)
            PdfA3CheckOutcome(ValidationStageResult("PDFA", result.outcome, result.findings), PDFA_ENGINE_LABEL)
        }
    }

    private companion object {
        const val PDFA_ENGINE_LABEL = "pdfa-minimal"
    }
}
