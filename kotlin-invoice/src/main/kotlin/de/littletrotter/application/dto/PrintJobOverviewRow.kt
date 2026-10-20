package de.littletrotter.application.dto

import de.littletrotter.domain.invoice.GenerationJobStatus
import java.time.OffsetDateTime

/**
 * One flat row of the global "Druckaufträge" listing. The UI must never hold managed
 * [de.littletrotter.domain.invoice.GenerationJob]/[de.littletrotter.domain.invoice.Invoice]
 * entities — [de.littletrotter.application.invoice.PrintJobOverviewQueryServiceImpl] maps the
 * fetch-joined entities [de.littletrotter.domain.repository.GenerationJobRepository.findPaged]
 * returns into this before returning to a caller.
 */
data class PrintJobOverviewRow(
    val jobId: Long,
    val invoiceId: Long,
    val invoiceNumber: String,
    /** The invoice's billing recipient display name, or `null` when the invoice has none. */
    val recipientName: String?,
    val status: GenerationJobStatus,
    val createdAt: OffsetDateTime,
    val finishedAt: OffsetDateTime?,
    /** V913 — how many times the stale-DELEGATED sweep has reset this job back to
     *  PENDING; see [de.littletrotter.domain.invoice.GenerationJob.attempts]'s own KDoc. */
    val attempts: Int,
    /** V913 — plain-text reason for the job's last recorded failure, diagnostic only;
     *  see [de.littletrotter.domain.invoice.GenerationJob.lastError]'s own KDoc. */
    val lastError: String?,
    /** V913 — the worker-reported LLM sub-status, read from its own column rather than
     *  parsed out of `resultRefs`; see [de.littletrotter.domain.invoice.GenerationJob.llmStatus]'s
     *  own KDoc. */
    val llmStatus: String?,
    /** The job's stored PDF object key (`resultRefs["objectKey"]`), `null` when the job
     *  never produced one (not yet COMPLETED, or a legacy/failed job). Lightweight by design: only
     *  the key, never the PDF bytes — [de.littletrotter.shared.DocumentStore.get] is called only
     *  once an operator actually clicks the download action. */
    val pdfObjectKey: String? = null,
    /** The job's e-invoice XML object key, `null` when none exists. See
     *  [de.littletrotter.application.invoice.deriveXmlObjectKey] for how this is derived. */
    val xmlObjectKey: String? = null,
)
