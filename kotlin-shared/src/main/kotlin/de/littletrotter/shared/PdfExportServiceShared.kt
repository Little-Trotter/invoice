package de.littletrotter.shared

/**
 * Port interface for document generation. Implementations must live in the document worker (kotlin-xpdf).
 * This keeps kotlin-shared tiny (DTOs + ports) and avoids pulling heavy PDF/ZUGFeRD/S3 dependencies
 * into kotlin-invoice at compile/runtime.
 */
interface PdfExportPort {
    /**
     * @param llmOptimize When true and the oMLX client is configured, the generated ZUGFeRD XML is
     *   sent to the LLM together with the KoSIT validation result. The Markdown improvement
     *   specification is stored in the S3-compatible object store and referenced via resultRefs["llmReportKey"].
     * @param skipValidation When true, the KoSIT validation step is skipped entirely.
     * @param veraPdfCheck When true, an *additional*, optional veraPDF
     *   accreditation layer runs on the exported PDF and appears in the report as its own stage
     *   (`"VERAPDF"`), distinct from the mandatory `"PDFA"` baseline stage that already runs
     *   unconditionally. Default `false` so a message from a producer that predates this
     *   option (queued before a rolling deploy) never gains a check it never asked for. The
     *   caller (`kotlin-invoice`'s `DocumentJobPublisher`) resolves this purely from
     *   `ValidationCapabilityId.VERAPDF_CHECK` — there is no separate user request for it, unlike
     *   [llmOptimize].
     * @param outputIntent opaque value read by kotlin-invoice from its system settings; absent = none.
     */
    fun performExport(
        invoice: InvoiceDto,
        jobId: String,
        llmOptimize: Boolean = false,
        skipValidation: Boolean = false,
        veraPdfCheck: Boolean = false,
        outputIntent: String? = null,
    ): ExportResult

    /**
     * The Factur-X counterpart to [performExport].
     * Separate method, not an `engine` parameter on [performExport], because the two routes fail
     * differently: a Mustang failure is always a thrown exception (the existing retry/nak
     * contract), while a Factur-X engine rejection ([FacturXExportOutcome.Failed]) is a definitive
     * answer that must never be retried — see the port doc on
     * `de.littletrotter.facturx.FacturXEngine` for why that boundary returns outcomes,
     * not exceptions. An unrelated infrastructure failure (e.g. a receipt that fails to load) still
     * throws here exactly as it does from [performExport] — only the engine's own
     * accept/reject verdict is modeled as [FacturXExportOutcome].
     *
     * Same parameter meanings as [performExport]; see there for [llmOptimize]/[skipValidation]/
     * [veraPdfCheck], and [outputIntent].
     */
    fun performFacturXExport(
        invoice: InvoiceDto,
        jobId: String,
        llmOptimize: Boolean = false,
        skipValidation: Boolean = false,
        veraPdfCheck: Boolean = false,
        outputIntent: String? = null,
    ): FacturXExportOutcome
}

/**
 * The result of a `FACTURX`-engine job: either a normal [ExportResult] (identical shape
 * to Mustang's, see [PdfExportPort.performFacturXExport]'s KDoc) or the engine's own rejection,
 * carried as [ValidationMessage]s rather than an exception (the vocabulary
 * `de.littletrotter.facturx.FacturXEngine` established).
 */
sealed interface FacturXExportOutcome {
    data class Success(val result: ExportResult) : FacturXExportOutcome

    /**
     * [findings] are exactly what the engine call (`generateXml`/`convert`) returned.
     * [reportJson] is the fully encoded report — same [KositReportPayload] shape every other job
     * failure in this system carries, with a single `FACTURX`/`ERROR` stage holding [findings] —
     * so the caller (`JobConsumer`) can publish it unchanged, the same way it already does for
     * every other `FAILED` result, without knowing the report's internal structure.
     */
    data class Failed(val findings: List<ValidationMessage>, val reportJson: String) : FacturXExportOutcome
}

/**
 * Minimal result returned by the document worker after successful export.
 */
data class ExportResult(
    /** Object key of the generated PDF. */
    val objectKey: String,
    /** Object key of the original standalone XML — always set. Mustang: the ZUGFeRD XML from
     *  [PdfExportPort.performExport]. Factur-X: the CII/D22B XML from the engine's own
     *  `generateXml` call, stored before `convert` embeds it into the final PDF — the field name
     *  stays engine-agnostic on purpose, see [PdfExportPort.performFacturXExport]'s KDoc on why
     *  both routes share one report shape. */
    val zugferdXmlKey: String,
    val kositSuccess: Boolean,
    /** Raw KoSIT validation report, or empty string when KoSIT is not configured / did not respond. */
    val kositReport: String,
    val sha256: String,
    /**
     * SHA-256 of [zugferdXmlKey]'s stored bytes, computed at the same point the PDF's own
     * [sha256] is (`PdfExportServiceImpl.completeExport`, right after storage). `null` only for
     * the one Factur-X outcome that has no XML at all (the engine rejected `generateXml` itself —
     * see `PdfExportServiceImpl.completeRejectedFacturXExport`'s own KDoc), never for a genuine
     * empty-but-present XML.
     */
    val zugferdXmlSha256: String? = null,
    /**
     * Object key of the LLM improvement specification (Markdown).
     * Empty string when the LLM was not run, was not configured, or produced no report.
     */
    val llmReportKey: String = "",
    /** LLM run outcome conveyed to the reader so it can close the InvoiceLlmAnalysis
     *  status chain. One of: "PENDING" (the deterministic stages succeeded and the
     *  analysis was accepted, but the oMLX call itself now runs afterwards, off the print job,
     *  on its own single-thread executor; the actual outcome arrives later as a SECOND result
     *  message, see `LlmAnalysisStage`), "COMPLETED"/"FAILED" (that
     *  second message's own outcome), "DISABLED" (not configured), "NOT_REQUESTED" (llmOptimize
     *  was off). Empty string on a legacy producer predating this field — the reader treats that as
     *  unknown / no LLM in that job. */
    val llmStatus: String = "",
    /** Name of the model that produced the report, when the LLM actually ran. */
    val llmModel: String = "",
    /** Error message when [llmStatus] == "FAILED". */
    val llmError: String = "",
    /** Wall-clock milliseconds spent waiting for the model, when it ran. */
    val llmDurationMs: Long = 0,
    /**
     * The produced XML's own
     * `rsm:ExchangedDocumentContext/ram:GuidelineSpecifiedDocumentContextParameter/ram:ID`,
     * verbatim, extracted by the worker so the UI (which must never parse XML itself, layer rule
     * Z3) can turn it into an exact "standard + version" label. `null` when the element is
     * absent/unreadable — never thrown.
     */
    val formatGuideline: String? = null,
    /**
     * Object key of the signed archive package (tar.gz: `original/` + `signed/` +
     * `manifest.json`, see `de.littletrotter.shared.archive.ArchivePackageBuilder`), built and
     * stored right after the PDF/XML in `PdfExportServiceImpl.completeExport`. `null` for a draft
     * export — a package is only built for an issued document (`SENT`, or a type-384 storno
     * document), never for `DRAFT`/`IN_REVIEW`.
     */
    val archiveObjectKey: String? = null,
    /** SHA-256 of the stored [archiveObjectKey] bytes, computed at the same "hash right
     *  after storage" point as [sha256]/[zugferdXmlSha256]. `null` exactly when [archiveObjectKey]
     *  is (a draft export). */
    val archiveSha256: String? = null,
)
