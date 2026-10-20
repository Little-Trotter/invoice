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
import de.littletrotter.shared.documentjob.QuoteDocumentReceiptRef
import de.littletrotter.shared.receipt.DocumentSize
import de.littletrotter.shared.receipt.OrderedReceipt
import de.littletrotter.shared.receipt.ReceiptSizePolicy
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.xpdf.NonRetryableExportException
import de.littletrotter.xpdf.QuotePdfExport
import de.littletrotter.xpdf.QuotePdfResult
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.util.Optional

/**
 * The stage chain of one offer PDF: Typst render, receipt pages (the customer's files
 * rasterised behind the offer, each checked against its checksum), optional attachment (loaded
 * from the object store by reference and checked against its checksum), sRGB output intent,
 * watermark by copy ([QuoteDocumentMark]; the dispatch copy carries none), PDF/A check, store
 * under `quotes/` and checksum. The final bytes must stay within the invoice document size limit;
 * a larger offer fails the job with a readable message instead of shipping an oversized file. The check runs on the bytes a customer would receive, after the watermark. It is
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
    private val receiptAnnexBuilder: ReceiptAnnexBuilder,
    private val time: TimeSource,
    // Optional deployment-wide demo text (env LITTLETROTTER_PDF_WATERMARK_TEXT); unset means no
    // demo line. `@param:` pins the annotation to the constructor parameter only (see
    // RenderWarmup); the default is a test seam for direct construction, never used by CDI.
    @param:ConfigProperty(name = PdfWatermarkStamper.DEMO_TEXT_PROPERTY)
    private val demoWatermarkText: Optional<String> = Optional.empty(),
) : QuotePdfExport {
    private val log = Logger.getLogger(QuotePdfPipeline::class.java)

    override fun export(jobId: String, payload: QuoteDocumentPayload): QuotePdfResult {
        val startedAt = time.nowUtc().toString()
        val rendered = renderer.render(payload)
        val withReceipts = appendReceipts(rendered, payload)
        val carrying = payload.attachment?.let { embedStage.embed(withReceipts, loadAttachment(it)) } ?: withReceipts
        val base = PdfA3ColorSpaceNormalizer.ensureSrgbOutputIntent(carrying)
        val pdf = enforceSizeBackstop(applyWatermark(base, payload))
        val stage = checkPdfA3(pdf)
        val objectKey = objectKey(payload, jobId)
        documentStore.put(objectKey, pdf, "application/pdf")
        return QuotePdfResult(objectKey, sha256Hex(pdf), reportJson(stage, startedAt))
    }

    private fun appendReceipts(rendered: ByteArray, payload: QuoteDocumentPayload): ByteArray {
        if (payload.receipts.isEmpty()) return rendered
        return receiptAnnexBuilder.append(rendered, payload.receipts.map { it.toOrderedReceipt() }, payload.language)
    }

    private fun QuoteDocumentReceiptRef.toOrderedReceipt() = OrderedReceipt(
        fileName = fileName,
        objectKey = objectKey,
        description = description,
        positions = emptyList(),
        invoiceLevel = true,
        rasterKind = null,
        sha256 = sha256,
    )

    private fun enforceSizeBackstop(pdf: ByteArray): ByteArray {
        val actual = DocumentSize.ofBytes(pdf.size.toLong())
        if (actual > ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE) {
            throw QuoteDocumentTooLargeException(actual, ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE)
        }
        return pdf
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
        val demo = demoWatermarkText.orElse(null)
        val mark = when (payload.mark) {
            QuoteDocumentMark.DRAFT, QuoteDocumentMark.REVIEW -> PdfWatermarkStamper.DRAFT_MARK
            QuoteDocumentMark.DISPATCH -> return PdfWatermarkStamper.stampDemoText(pdf, demo)
        }
        return PdfWatermarkStamper.stampMark(pdf, mark, payload.language, demo)
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

/**
 * The final offer document, receipt pages and watermark included, exceeds the size limit shared
 * with invoices. Not retryable: the same files would produce the same size again; the sender has
 * to attach fewer or smaller files.
 */
class QuoteDocumentTooLargeException(val actual: DocumentSize, val limit: DocumentSize) : NonRetryableExportException(
    "Offer document is ${mib(actual)} MiB, exceeding the maximum of ${mib(limit)} MiB",
) {
    private companion object {
        fun mib(size: DocumentSize) = "%.1f".format(java.util.Locale.ROOT, size.mebiBytes)
    }
}
