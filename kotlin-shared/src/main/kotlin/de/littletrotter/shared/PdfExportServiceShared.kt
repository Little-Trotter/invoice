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
     * @param veraPdfCheck When true, the report carries the veraPDF conformance stage
     *   (`"VERAPDF"`) as pending: the check runs afterwards as its own service on the stored
     *   PDF and its result replaces the stage. It is distinct from the mandatory `"PDFA"`
     *   baseline stage that runs unconditionally. Default `false` so a message from a producer that predates this
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
}

/**
 * Minimal result returned by the document worker after successful export.
 */
data class ExportResult(
    /** Object key of the generated PDF. */
    val objectKey: String,
    /** Object key of the original standalone XML — always set. The ZUGFeRD XML from
     *  [PdfExportPort.performExport]. */
    val zugferdXmlKey: String,
    val kositSuccess: Boolean,
    /** Raw KoSIT validation report, or empty string when KoSIT is not configured / did not respond. */
    val kositReport: String,
    val sha256: String,
    /**
     * SHA-256 of [zugferdXmlKey]'s stored bytes, computed at the same point the PDF's own
     * [sha256] is (`PdfExportServiceImpl.completeExport`, right after storage). `null` only when
     * there is no XML at all, never for a genuine empty-but-present XML.
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
     * export — a package is only built for an issued document (`ISSUED`, or a type-384 cancellation
     * document), never for `DRAFT`/`IN_REVIEW`.
     */
    val archiveObjectKey: String? = null,
    /** SHA-256 of the stored [archiveObjectKey] bytes, computed at the same "hash right
     *  after storage" point as [sha256]/[zugferdXmlSha256]. `null` exactly when [archiveObjectKey]
     *  is (a draft export). */
    val archiveSha256: String? = null,
)
