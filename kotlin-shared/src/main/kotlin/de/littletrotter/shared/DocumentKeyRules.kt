package de.littletrotter.shared

import java.time.LocalDate

/**
 * The storage keys of one invoice export — same base name, three extensions.
 */
data class ExportKeys(
    val pdfObjectKey: String,
    val xmlObjectKey: String,
    val llmReportKey: String,
    /** The signed archive package (tar.gz), same base name/derivation as the other three
     *  keys. Only ever stored for an issued document (see
     *  `de.littletrotter.xpdf.impl.PdfExportServiceImpl.completeExport`'s own archive-building
     *  step) — a draft's key is derived here like any other, but nothing is ever written to it. */
    val archiveObjectKey: String,
)

/**
 * Common key rules for document storage in RustFS/S3.
 *
 * New documents are stored under `documents/<year>/...` where the year comes from
 * the invoice date, not the creation time. This ensures that a back-dated invoice
 * from 2025 stored in January 2026 still ends up in the 2025 folder.
 *
 * Existing keys without a year prefix remain readable via the dual-read path in
 * DocumentStore extensions.
 */
object DocumentKeyRules {

    /**
     * Build the storage key for a document given its invoice date.
     *
     * Example: 2026-08-15 + "invoice_12345.pdf" → "documents/2026/invoice_12345.pdf"
     */
    fun buildDocumentKey(invoiceDate: LocalDate, filename: String): String {
        val year = invoiceDate.year
        return "documents/$year/$filename"
    }

    /**
     * Prefix covering every document key for [year] (`documents/<year>/`).
     *
     * The single place that knows both how a key is built ([buildDocumentKey]) and how a
     * whole year's worth of them is matched, so a year-scoped listing (e.g. a yearly RustFS
     * backup) can never drift out of sync with the key layout above.
     */
    fun yearPrefix(year: Int): String = "documents/$year/"

    /**
     * Derive the export-related storage keys for an invoice from its invoice number.
     *
     * All three keys share one base name (the invoice number with `/` replaced by `-`);
     * only the extensions differ. Single derivation point for both export engines.
     *
     * A DRAFT invoice's number is unassigned (blank; `InvoiceNumber.UNASSIGNED` in kotlin-invoice,
     * not importable here — see [de.littletrotter.shared.InvoiceDto]'s own KDoc on why this
     * module has no dependency on kotlin-invoice's domain) — normal, long-lived state, not an
     * edge case, since nothing blocks PDF/XML generation before finalization. Deriving the base
     * name from the blank number would collide every draft onto the same literal
     * `invoices/.pdf`/`.xml` keys, each generation silently overwriting the last. [invoiceId]
     * disambiguates that case: blank number → `invoices/draft-$invoiceId`. A persisted invoice
     * always has an id by the time it can be exported, so [invoiceId] being null there too means
     * no stable key can be built at all — a genuine caller error.
     *
     * This is the invoice's key stem only (and the entry names inside its archive package); no
     * export ever writes an object under it — writers use [deriveJobExportKeys].
     */
    fun deriveExportKeys(invoiceNumber: String, invoiceId: Long? = null): ExportKeys =
        keysFor(invoiceKeyStem(invoiceNumber, invoiceId))

    private fun invoiceKeyStem(invoiceNumber: String, invoiceId: Long?): String =
        if (invoiceNumber.isBlank()) {
            checkNotNull(invoiceId) {
                "Cannot derive export keys: invoice number is blank and invoiceId is null"
            }
            "invoices/draft-$invoiceId"
        } else {
            "invoices/${invoiceNumber.replace('/', '-')}"
        }

    private fun keysFor(base: String) = ExportKeys(
        pdfObjectKey = "$base.pdf",
        xmlObjectKey = "$base.xml",
        llmReportKey = "$base-llm-report.md",
        archiveObjectKey = "$base.tar.gz",
    )

    /**
     * Export keys for one concrete export attempt: no two attempts ever share an object key.
     *
     * Document-checksum-mismatch spec (PO 2026-10-01): two writers of the same S3 key raced the
     * persisted SHA-256 against the stored bytes. Every key is therefore unique per attempt —
     * `<base>-<jobId>-<attempt>` — unconditionally, without an existence probe (a probe is a
     * check-then-act window by itself). [attempt] separates a JetStream redelivery of the same
     * [jobId] (heartbeat lapse re-runs the whole export) from the first run, so a late rerun can
     * never rewrite the bytes the first run's checksum was recorded for; the invoice side only
     * ever reads the keys the completed attempt reported in its result.
     *
     * A draft never uses the issued base name `invoices/<Nr>`: the number is assigned at create,
     * so a draft preview would otherwise land beside (and look like) the issued document. Drafts
     * live under `invoices/draft/<invoiceId>-<jobId>-<attempt>`.
     */
    fun deriveJobExportKeys(
        invoiceNumber: String,
        invoiceId: Long?,
        jobId: String,
        draft: Boolean,
        attempt: String = newAttemptId(),
    ): ExportKeys {
        require(jobId.isNotBlank()) { "Cannot derive export keys: jobId is blank" }
        val suffix = "-" + sanitize(jobId) + "-" + sanitize(attempt)
        val baseName = if (draft) {
            // A persisted draft always has an id; the number is only the fallback for an id-less
            // (never persisted) DTO and still lives in the draft namespace.
            val draftId = invoiceId?.toString() ?: invoiceNumber.replace('/', '-')
            check(draftId.isNotBlank()) { "Cannot derive draft export keys: invoiceId is null and number is blank" }
            "invoices/draft/$draftId$suffix"
        } else {
            invoiceKeyStem(invoiceNumber, invoiceId) + suffix
        }
        return keysFor(baseName)
    }

    /** A fresh, practically collision-free attempt id (12 hex chars of a random UUID). */
    fun newAttemptId(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(ATTEMPT_ID_LENGTH)

    /**
     * Listing prefix covering every per-attempt export of the issued invoice [invoiceNumber]
     * (`invoices/<Nr>-`). It also covers other invoices whose number merely starts with the same
     * text; filter a listing with [isIssuedJobPdfKey].
     */
    fun issuedJobExportPrefix(invoiceNumber: String, invoiceId: Long?): String = invoiceKeyStem(invoiceNumber, invoiceId) + "-"

    /** Whether [key] is a per-attempt PDF of exactly this issued invoice (see [deriveJobExportKeys]). */
    fun isIssuedJobPdfKey(key: String, invoiceNumber: String, invoiceId: Long?): Boolean =
        Regex(Regex.escape(invoiceKeyStem(invoiceNumber, invoiceId)) + "-[A-Za-z0-9_]+-[0-9a-f]{$ATTEMPT_ID_LENGTH}\\.pdf")
            .matches(key)

    // No '-' inside a part: it separates base, job id and attempt, so [isIssuedJobPdfKey] can tell
    // `RE-1`'s keys from `RE-1-5`'s.
    private fun sanitize(part: String): String = part.replace(Regex("[^A-Za-z0-9_]"), "_")

    private const val ATTEMPT_ID_LENGTH = 12
}
