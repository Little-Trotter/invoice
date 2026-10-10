package de.littletrotter.xpdf.impl

import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.ValidationRun
import de.littletrotter.shared.ValidationStageResult
import de.littletrotter.shared.VeraPdfValidatorPort
import de.littletrotter.shared.documentjob.QuoteDocumentAttachmentRef
import de.littletrotter.shared.documentjob.QuoteDocumentMark
import de.littletrotter.shared.documentjob.QuoteDocumentPayload
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.xpdf.QuotePdfExport
import de.littletrotter.xpdf.QuotePdfResult
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger

/**
 * The stage chain of one offer PDF: Typst render, optional attachment (loaded from the object
 * store by reference and checked against its checksum), sRGB output intent, watermark by copy
 * ([QuoteDocumentMark]; the dispatch copy carries none), PDF/A check, store under `quotes/` and
 * checksum. The check runs on the bytes a customer would receive, after the watermark. It is
 * veraPDF when available and the XMP-only minimal check otherwise; neither rejects the job — the
 * report states the verdict. The attachment is opaque bytes plus a name and a media type; what it
 * means is the offer service's business, so no Factur-X or Order-X claim is ever made here.
 */
@ApplicationScoped
class QuotePdfPipeline @Inject constructor(
    private val renderer: QuoteTypstRenderer,
    private val documentStore: DocumentStore,
    private val veraPdfValidator: VeraPdfValidatorPort,
    private val pdfA3Check: PdfA3MinimalCheck,
    private val embedStage: EmbedAttachmentStage,
    private val time: TimeSource,
) : QuotePdfExport {
    private val log = Logger.getLogger(QuotePdfPipeline::class.java)

    override fun export(jobId: String, payload: QuoteDocumentPayload): QuotePdfResult {
        val startedAt = time.nowUtc().toString()
        val rendered = renderer.render(payload)
        val carrying = payload.attachment?.let { embedStage.embed(rendered, loadAttachment(it)) } ?: rendered
        val base = PdfA3ColorSpaceNormalizer.ensureSrgbOutputIntent(carrying)
        val pdf = applyWatermark(base, payload)
        val stage = checkPdfA3(pdf)
        val objectKey = objectKey(payload, jobId)
        documentStore.put(objectKey, pdf, "application/pdf")
        return QuotePdfResult(objectKey, sha256Hex(pdf), reportJson(stage, startedAt))
    }

    private fun loadAttachment(ref: QuoteDocumentAttachmentRef): EmbeddedAttachment {
        val content = documentStore.get(ref.objectKey)
        val actual = sha256Hex(content)
        check(actual.equals(ref.sha256, ignoreCase = true)) {
            "attachment ${ref.objectKey} does not match its checksum (expected ${ref.sha256}, found $actual)"
        }
        return EmbeddedAttachment(content, ref.fileName, ref.mimeType, ref.description)
    }

    private fun applyWatermark(pdf: ByteArray, payload: QuoteDocumentPayload): ByteArray {
        val mark = when (payload.mark) {
            QuoteDocumentMark.DRAFT, QuoteDocumentMark.REVIEW -> PdfWatermarkStamper.DRAFT_MARK
            QuoteDocumentMark.DISPATCH -> return pdf
        }
        return PdfWatermarkStamper.stampMark(pdf, mark, payload.language)
    }

    private fun checkPdfA3(pdf: ByteArray): PdfA3Verdict {
        val vera = veraPdfValidator.validate(pdf)
        if (vera.outcome != KositOutcome.ERROR) {
            return PdfA3Verdict(ValidationStageResult("PDFA", vera.outcome, vera.findings), vera.validator)
        }
        log.warn("veraPDF unavailable, falling back to the minimal PDF/A check: ${vera.engineError}")
        val minimal = pdfA3Check.check(pdf, expectedAttachmentFilename = null)
        val engineError = if (minimal.outcome == KositOutcome.ERROR) vera.engineError else null
        return PdfA3Verdict(
            ValidationStageResult("PDFA", minimal.outcome, minimal.findings, engineError = engineError),
            "pdfa-minimal (veraPDF unavailable: ${vera.engineError})",
        )
    }

    private fun reportJson(verdict: PdfA3Verdict, startedAt: String): String {
        val run = ValidationRun(
            stages = listOf(verdict.stage),
            overall = ValidationRun.overallOutcomeOf(listOf(verdict.stage)),
            validatorVersions = mapOf("pdfa" to verdict.engineLabel),
            startedAt = startedAt,
        )
        return KositReportPayload.encode(
            KositValidationResult(status = run.overall, validationRun = run, profile = "QUOTE_PDF", checkedAt = startedAt),
        )
    }

    private fun objectKey(payload: QuoteDocumentPayload, jobId: String): String =
        "$KEY_PREFIX${payload.quoteId}/${payload.mark.name.lowercase()}-$jobId.pdf"

    private class PdfA3Verdict(val stage: ValidationStageResult, val engineLabel: String)

    companion object {
        /** Object-store prefix of every offer document. */
        const val KEY_PREFIX = "quotes/"
    }
}
