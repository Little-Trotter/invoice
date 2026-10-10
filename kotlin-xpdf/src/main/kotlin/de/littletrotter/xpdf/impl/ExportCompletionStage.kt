package de.littletrotter.xpdf.impl

import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.ExportResult
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.archive.ArchivePackageBuilder
import de.littletrotter.shared.archive.ArchivePayload
import de.littletrotter.shared.archive.ArchiveSigner
import de.littletrotter.shared.extractGuidelineUrn
import de.littletrotter.shared.resolveOutputIntentSubject
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.xpdf.PdfLayoutInspector
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger

/**
 * The shared post-export machinery both [PdfExportServiceImpl.performExport] and
 * [PdfExportServiceImpl.performFacturXExport] reach at the end of their route: watermark stamp,
 * store + checksum the final PDF/XML, sign+store the archive package, schedule the deferred LLM
 * analysis, and assemble [ExportResult]. Split out of [PdfExportServiceImpl] so both engines keep
 * producing byte-for-byte identical report shape without a reader needing to know which engine
 * produced a given report.
 */
@ApplicationScoped
class ExportCompletionStage @Inject constructor(
    private val documentStore: DocumentStore,
    private val archiveSigner: ArchiveSigner?,
    private val llmAnalysisStage: LlmAnalysisStage?,
    private val outputIntentVerifier: OutputIntentVerifier,
    private val validationStages: ExportValidationStages,
    // The one injected source of "now" — see de.littletrotter.xpdf.TimeSourceProducer.
    private val time: TimeSource,
) {
    private val log = Logger.getLogger(ExportCompletionStage::class.java)

    /**
     * [ExportStages.engine] is `null` for Mustang (no engine-of-its-own validation exists there)
     * and the Factur-X route's own `FACTURX` stage otherwise; [ExportStages.brFrCtc] is `null`
     * for Mustang and for every Factur-X job whose recipient isn't French — both are left
     * out of the report by [ExportStages.chain], never present-and-empty.
     */
    internal fun completeExport(
        request: ExportRequest,
        artifacts: ExportArtifacts,
        stages: ExportStages,
        // PO decision (2026-08-25, extension #5) — true only for the no-XML degraded Factur-X
        // completion (the engine rejected `generateXml` itself, see
        // [FacturXExportPipeline.completeRejectedFacturXExport]): there is no XML at all for the
        // deferred LLM stage to analyze, so a would-be `PENDING` must not silently promise work
        // that can never run — the same reasoning as `JobConsumer.llmStatusForFailedJob`'s KDoc:
        // an honest `FAILED` beats a `PENDING` nothing will ever resolve. `false`
        // (the default) leaves every existing caller's behavior byte-for-byte unchanged.
        suppressLlmScheduling: Boolean = false,
    ): ExportResult {
        val objectKey = request.keys.pdfObjectKey
        // The archive package's own storage key (`DocumentKeyRules.deriveJobExportKeys`, per attempt);
        // building/storing the package itself is entirely this method's own concern (see the
        // archive-package block near the end of this method).
        val archiveObjectKey = request.keys.archiveObjectKey
        val zugferdXmlObjectKey = artifacts.zugferdXmlObjectKey
        val xmlBytes = artifacts.xmlBytes
        // The watermark, stamped last (after Mustang's ZUGFeRD embed / the Factur-X engine's
        // convert, and after the receipt annex — both already baked into the final PDF by the
        // time either caller reaches this shared method), so every stage below (storage,
        // checksum, the mandatory PDF/A-3 check, the optional veraPDF layer) sees exactly the
        // bytes a customer would receive. See [applyWatermark]'s own KDoc for the exact three-row
        // routing.
        val stampedPdfBytes = applyWatermark(request, artifacts.finalPdfBytes)
        documentStore.put(objectKey, stampedPdfBytes, "application/pdf")
        val checksum = sha256Hex(stampedPdfBytes)
        // The XML's own checksum, same "hash right after storage" point as the PDF's above.
        // `zugferdXmlObjectKey` is blank only for the one Factur-X outcome with no XML at
        // all (completeRejectedFacturXExport's generateXml-rejected branch) — that is the sole
        // case this reports `null` rather than a real digest.
        val xmlSha256 = if (artifacts.hasXml) sha256Hex(xmlBytes) else null

        // Signed archive package (tar.gz: original/ + signed/ + manifest.json), built and
        // stored right after the PDF/XML's own checksums above so this "hash right after storage"
        // point covers all three artifacts consistently. `null` when there is nothing to build —
        // see [buildAndStoreArchivePackage]'s own KDoc for the exact two guard conditions.
        // Entry names inside the package come from the invoice number (`<Nr>.pdf`/`<Nr>.xml`), not
        // from the per-attempt storage keys: the package content stays readable for its recipient.
        val entryNames = de.littletrotter.shared.DocumentKeyRules.deriveExportKeys(
            request.invoice.invoiceNumber.value, request.invoice.id,
        )
        val archiveSha256 = buildAndStoreArchivePackage(
            request = request,
            pdfPayload = ArchivePayload(entryNames.pdfObjectKey.substringAfterLast('/'), stampedPdfBytes),
            // `null` exactly when there is no XML at all to pair with the PDF — see
            // buildAndStoreArchivePackage's own KDoc, second guard condition.
            xmlPayload = zugferdXmlObjectKey.takeIf { it.isNotBlank() }
                ?.let { ArchivePayload(entryNames.xmlObjectKey.substringAfterLast('/'), xmlBytes) },
            archiveObjectKey = archiveObjectKey,
        )

        val kositResult = validationStages.buildValidationReport(request, artifacts, stages, stampedPdfBytes)
        val kositReportJson = KositReportPayload.encode(kositResult)

        // The guideline URN, extracted once here so both export routes
        // (Mustang and Factur-X both pass their own generated XML into this shared method) carry
        // it in the result the same way; JobConsumer.publishCompletedResult puts it into
        // resultRefs["formatGuideline"] unchanged.
        val formatGuideline = extractGuidelineUrn(xmlBytes)

        // 4. LLM improvement specification via oMLX — see [scheduleLlmAnalysis].
        val llmStatus = scheduleLlmAnalysis(
            request, artifacts, PostExportOutputs(stampedPdfBytes, kositReportJson), suppressLlmScheduling,
        )

        return ExportResult(
            objectKey       = objectKey,
            zugferdXmlKey   = zugferdXmlObjectKey,
            kositSuccess    = kositResult.status == KositOutcome.VALID,
            kositReport     = kositReportJson,
            sha256          = checksum,
            zugferdXmlSha256 = xmlSha256,
            // Never populated here anymore: the report, model, error and duration are only known
            // once the deferred stage's SECOND callback resolves them.
            llmReportKey    = "",
            llmStatus       = llmStatus,
            llmModel        = "",
            llmError        = "",
            llmDurationMs   = 0,
            formatGuideline = formatGuideline,
            // The object key is only meaningful once a package was actually built/stored
            // for it (archiveSha256 non-null); otherwise this stays null too, never a key pointing
            // at nothing in the store.
            archiveObjectKey = archiveSha256?.let { archiveObjectKey },
            archiveSha256   = archiveSha256,
        )
    }

    /**
     * 4. LLM improvement specification via oMLX: the print job itself
     * ends HERE, right after the deterministic stages above; the oMLX call no longer runs
     * inline on this thread at all. When an analysis was requested and oMLX is configured,
     * this only reports llmStatus "PENDING" and hands [llmAnalysisStage] everything it needs
     * to run the actual model call afterwards, off its own single-thread executor — the
     * outcome (COMPLETED/FAILED, stored report, model/duration/error) arrives later as a
     * SECOND result message on the same NATS result path (see [LlmAnalysisStage]'s own KDoc).
     * A model failure was never allowed to fail the export job itself even before this
     * decoupling — it still isn't; it just can no longer happen on this call stack anymore.
     * Returns the job's `llmStatus`. Split out of [completeExport].
     */
    private fun scheduleLlmAnalysis(
        request: ExportRequest,
        artifacts: ExportArtifacts,
        outputs: PostExportOutputs,
        suppressLlmScheduling: Boolean,
    ): String {
        val stampedPdfBytes = outputs.stampedPdfBytes
        val kositReportJson = outputs.kositReportJson
        val stage = llmAnalysisStage
        val llmConfigured = stage?.isAvailable() == true
        val llmOptimize = request.llmOptimize
        val llmScheduled = llmOptimize && llmConfigured && !suppressLlmScheduling
        // Derive the not-scheduled states explicitly so the reader never has to guess: DISABLED
        // (not configured) takes priority over NOT_REQUESTED so a non-configured install reads
        // the same regardless of whether the checkbox was ticked. FAILED (extension #5) only
        // applies when scheduling was actively suppressed despite being otherwise eligible — a
        // job that was never going to schedule anyway (not requested, or oMLX not configured)
        // keeps its usual NOT_REQUESTED/DISABLED reading, unaffected by [suppressLlmScheduling].
        val llmStatus = when {
            llmScheduled -> "PENDING"
            suppressLlmScheduling && llmOptimize && llmConfigured -> "FAILED"
            // llmConfigured is always false here: llmOptimize && llmConfigured is fully handled
            // by the two branches above (PENDING or FAILED), whichever way suppressLlmScheduling
            // falls.
            llmOptimize -> "DISABLED"
            else -> "NOT_REQUESTED"
        }
        if (llmScheduled) {
            // Deterministic PDF layout measurement (Approach C) — best-effort, since it's
            // purely an enrichment of the LLM prompt, not something the export result depends on.
            // Sole catch-all of this best-effort enrichment flow: any failure here must degrade
            // to an empty layout report, never fail the already-completed
            // export job. Logged, never silently swallowed.
            val layoutReport = try {
                PdfLayoutInspector().inspect(stampedPdfBytes).toLlmText()
            } catch (ex: Exception) {
                log.warn("PDF layout inspection failed for job ${request.jobId}: ${ex.message}")
                ""
            }
            stage.schedule(
                DeferredLlmAnalysisRequest(
                    jobId = request.jobId,
                    invoice = request.invoice,
                    xmlBytes = artifacts.xmlBytes,
                    kositReportJson = kositReportJson,
                    layoutReport = layoutReport,
                    llmReportKey = request.keys.llmReportKey,
                ),
            )
        }
        return llmStatus
    }

    /**
     * Builds and stores the signed archive package for one completed export, returning its
     * own SHA-256 (or `null` when no package was built at all). Two independent guard conditions,
     * both required:
     *  - [ArchiveSigningKeyProvider][de.littletrotter.xpdf.impl.ArchiveSigningKeyProvider] (via
     *    [archiveSigner]) must be configured — always true in production (CDI), `null` only for
     *    tests that construct this class directly without one (see
     *    [archiveSigner]'s own KDoc);
     *  - the invoice must be issued, never a draft ([InvoiceDto.draft]: a package exists
     *    only for `SENT`/a type-384 storno document, since a cancelled original is locked to a
     *    single generation job), AND a real XML must actually have been produced ([xmlPayload] `null`
     *    means the one degraded Factur-X outcome with no XML at all,
     *    [FacturXExportPipeline.completeRejectedFacturXExport]'s `generateXml`-rejected branch —
     *    there is no complete PDF+XML pair to archive in that case).
     *
     * [pdfPayload]/[xmlPayload] carry the exact same filenames (their own object keys' last path
     * segment, derived once at the call site — not re-derived independently here) the invoice's
     * PDF/XML downloads already use, so the archive's `original/` directory never drifts from them.
     */
    private fun buildAndStoreArchivePackage(
        request: ExportRequest,
        pdfPayload: ArchivePayload,
        xmlPayload: ArchivePayload?,
        archiveObjectKey: String,
    ): String? {
        val invoice = request.invoice
        val jobId = request.jobId
        val signer = archiveSigner ?: return null
        if (invoice.draft || xmlPayload == null) return null
        val payloads = listOf(pdfPayload, xmlPayload)
        // Manifest fields: invoice number, type code, timestamp, job id, actor. "actor" is fixed
        // to "system": the NATS job envelope this worker receives (DocumentJobRequest) carries no
        // per-request user identity to attribute the export to — the same "no live request
        // context here" reasoning `AuditActorProvider`'s own background-work fallback already
        // documents elsewhere in this codebase (kotlin-invoice). Threading the real actor through
        // the publish pipeline is a follow-up.
        val manifestFields = mapOf(
            "invoiceNumber" to invoice.invoiceNumber.value,
            "typeCode" to (invoice.typeCode ?: "380"),
            "timestamp" to time.nowUtc().toString(),
            "jobId" to jobId,
            "actor" to "system",
        )
        val archivePackage = ArchivePackageBuilder.build(payloads, manifestFields, signer)
        documentStore.put(archiveObjectKey, archivePackage.bytes, "application/gzip")
        return archivePackage.sha256
    }

    /**
     * Every PDF carries at most one of three fixed marks, first match wins: the
     * storno document itself ([InvoiceDto.storno]), a non-finalized invoice ([InvoiceDto.draft]),
     * or the brand mark otherwise. A cancelled original is never regenerated (the single-
     * generation gate), so its archived PDF simply keeps whichever mark it already carried
     * when it was issued — there is no separate row for it here. Each mark's text follows
     * [de.littletrotter.shared.InvoiceMetaDto.documentLanguage] (the frozen document language),
     * never the UI locale.
     *
     * Rows 1-2 are checked in that order and are both equally unconditional: a storno document
     * and a non-finalized invoice must never leave this method unstamped, and neither is ever
     * suppressed. Do not reorder these branches and do not make either of them conditional on
     * anything.
     */
    private fun applyWatermark(request: ExportRequest, finalPdfBytes: ByteArray): ByteArray {
        val invoice = request.invoice
        val language = invoice.meta.documentLanguage
        return when {
            invoice.storno -> PdfWatermarkStamper.stampMark(finalPdfBytes, PdfWatermarkStamper.STORNO_MARK, language)
            invoice.draft -> PdfWatermarkStamper.stampMark(finalPdfBytes, PdfWatermarkStamper.DRAFT_MARK, language)
            outputIntentVerifier.covers(request.outputIntent, resolveOutputIntentSubject(invoice), time.today()) ->
                finalPdfBytes
            else -> PdfWatermarkStamper.stampMark(finalPdfBytes, PdfWatermarkStamper.BRAND_MARK, language)
        }
    }
}

/** kotlin:LongParameterList — bundles [scheduleLlmAnalysis]'s two output-side inputs (the
 *  stamped PDF a customer would receive, and the already-encoded validation report) so that
 *  private method stays at 4 parameters instead of 5. Not a `data class`: it carries a
 *  [ByteArray] and is never compared/hashed, so a generated `equals`/`hashCode` over that array's
 *  identity would only invite the ArrayInDataClass finding for no benefit. */
private class PostExportOutputs(val stampedPdfBytes: ByteArray, val kositReportJson: String)
