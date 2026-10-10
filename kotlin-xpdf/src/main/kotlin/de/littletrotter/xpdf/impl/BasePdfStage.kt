package de.littletrotter.xpdf.impl

import de.littletrotter.shared.BasePdfRendererPort
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.ValidationStageResult
import de.littletrotter.shared.VeraPdfResult
import de.littletrotter.shared.VeraPdfValidatorPort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger

/**
 * The base-PDF stage: render the invoice layout through [BasePdfRendererPort] (Typst) and run the
 * PDF/A-3 baseline check ([PdfA3MinimalCheck]/veraPDF).
 *
 * A beleg is carried exactly once in the exported document: as Base64
 * (`AttachmentBinaryObject`, BG-24) inside the embedded ZUGFeRD/Factur-X XML — the strictest
 * transport profile (XRechnung) already requires it there, so every further copy (a rasterized
 * annex page, a second PDF/A-3 embedded file per beleg) would only inflate the container for no
 * reader that cannot already read the XML. [renderBase] therefore stays a plain template render;
 * the belege themselves never touch this class.
 *
 * [renderBase] and [checkPdfA3] were previously private, Mustang-only steps inline in
 * [PdfExportServiceImpl.performExport]. Pulling them out here means Mustang's byte-for-byte
 * behavior is unchanged (verified by the existing regression tests) while any future
 * engine-specific caller reaches the identical code, not a second implementation.
 *
 * What this class does *not* yet do: it is not wired to a second NATS subject or an actual
 * Factur-X caller. Today [PdfExportServiceImpl] is this class's only caller.
 */
@ApplicationScoped
class BasePdfStage @Inject constructor(
    private val reportRenderer: BasePdfRendererPort,
    private val pdfA3Check: PdfA3MinimalCheck,
    private val veraPdfValidator: VeraPdfValidatorPort,
) {
    /** Direct-instantiation convenience (tests, [PdfExportServiceImpl]'s default parameter) —
     *  mirrors the defaults [PdfExportServiceImpl] used to construct these collaborators
     *  with before this class existed. */
    internal constructor(reportRenderer: BasePdfRendererPort) : this(
        reportRenderer, PdfA3MinimalCheck(), VeraPdfCliValidator(),
    )

    private val log = Logger.getLogger(BasePdfStage::class.java)

    /** Renders [invoice] into the shared, engine-agnostic base document. Every engine starts
     *  from this exact byte stream, before any engine-specific XML embedding. */
    fun renderBase(invoice: InvoiceDto): ByteArray = reportRenderer.render(invoice)

    /**
     * The result of [checkPdfA3], carrying the raw [VeraPdfResult] alongside the
     * mandatory "PDFA" stage it produced. [stage]/[engineLabel] are exactly what existed before
     * the optional veraPDF layer was added (destructuring `val (stage, label) = ...` still compiles
     * unchanged — Kotlin generates `componentN()` for every data class in declaration order).
     * [veraPdfResult] is the addition: it lets [optionalVeraPdfLayer] surface veraPDF's own verdict
     * as a second, independent layer without invoking the CLI a second time.
     */
    data class PdfA3CheckOutcome(
        val stage: ValidationStageResult,
        val engineLabel: String,
        /** `null` only when `skipValidation` was true — no check of any kind ran, so there is
         *  nothing for the optional layer to reuse either (it also renders as absent then). Set
         *  in both the compliant and the engine-error branch below, even though the error branch
         *  additionally falls back to [PdfA3MinimalCheck] for [stage]'s own displayed outcome. */
        val veraPdfResult: VeraPdfResult?,
    )

    /**
     * The PDF/A-3 baseline check, identical for every engine, run on the
     * actually produced final bytes (post engine-specific XML embedding) — never a re-render.
     * veraPDF is the primary check; [PdfA3MinimalCheck] runs only as a fallback when the CLI is
     * unavailable at runtime, and that fallback is never silent (the returned engine label names
     * it). `skipValidation` mirrors every other stage in this pipeline: explicitly recorded
     * as skipped, never silently passed as if it had run.
     *
     * Returns the stage result together with the resolved engine label (for the report's version
     * block — see `pdfaEngineLabel` in [PdfExportServiceImpl.performExport]) and the raw veraPDF
     * outcome for [optionalVeraPdfLayer].
     */
    fun checkPdfA3(
        finalPdfBytes: ByteArray,
        expectedAttachmentFilename: String,
        skipValidation: Boolean,
    ): PdfA3CheckOutcome {
        if (skipValidation) {
            return PdfA3CheckOutcome(
                ValidationStageResult("PDFA", KositOutcome.VALID, skipped = true, skipReason = "skipValidation"),
                "pdfa-minimal",
                veraPdfResult = null,
            )
        }
        val veraResult = veraPdfValidator.validate(finalPdfBytes)
        return if (veraResult.outcome == KositOutcome.ERROR) {
            log.warn("veraPDF nicht verfügbar, Fallback auf PdfA3MinimalCheck: ${veraResult.engineError}")
            val pdfaResult = pdfA3Check.check(finalPdfBytes, expectedAttachmentFilename)
            val label = "pdfa-minimal (veraPDF nicht verfügbar: ${veraResult.engineError})"
            // The veraPDF failure is the VERAPDF layer's story (optionalVeraPdfLayer below), not
            // PDFA's — only attach it here when the fallback check itself also errored out.
            val pdfaEngineError = if (pdfaResult.outcome == KositOutcome.ERROR) veraResult.engineError else null
            PdfA3CheckOutcome(
                ValidationStageResult("PDFA", pdfaResult.outcome, pdfaResult.findings, engineError = pdfaEngineError),
                label,
                veraResult,
            )
        } else {
            PdfA3CheckOutcome(ValidationStageResult("PDFA", veraResult.outcome, veraResult.findings), veraResult.validator, veraResult)
        }
    }

    /**
     * The *optional* veraPDF accreditation layer, gated by
     * `ValidationCapabilityId.VERAPDF_CHECK` (`kotlin-invoice`, resolved by the caller and passed
     * in as [enabled] — this module has no DB access of its own).
     *
     * Distinct from [checkPdfA3]'s mandatory "PDFA" stage in one deliberate way: this layer never
     * falls back to [PdfA3MinimalCheck]. An unreachable CLI is surfaced here as `ERROR` — not
     * silently swapped for a weaker check — because the entire point of this layer is to prove
     * veraPDF itself ran ("technical failure ≠ document defect", `ERROR` not
     * `INVALID`). Reuses [outcome]'s already-computed [VeraPdfResult] instead of invoking the CLI
     * a second time per export.
     *
     * Returns `null` — meaning "no such layer in this run" — when [enabled] is false (the
     * switch is off) or when [outcome] carries no veraPDF result at all (`skipValidation` meant
     * nothing ran). A present result, `ERROR` included, is always turned into its own stage.
     */
    fun optionalVeraPdfLayer(outcome: PdfA3CheckOutcome, enabled: Boolean): ValidationStageResult? {
        if (!enabled) return null
        val vera = outcome.veraPdfResult ?: return null
        return ValidationStageResult("VERAPDF", vera.outcome, vera.findings, engineError = vera.engineError)
    }
}
