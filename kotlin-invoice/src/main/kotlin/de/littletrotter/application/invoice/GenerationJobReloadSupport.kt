package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.repository.GenerationJobRepository

/**
 * L25/L2 — the single re-read [GenerationJobResultHandler.markCompleted] and
 * [GenerationJobRecoveryService.markFailed] both use before writing through [job]: the caller's own
 * reference may have crossed a transaction boundary detached (see `markCompleted`'s own KDoc for
 * the full mechanism). Loading fresh by id, inside the CALLING method's already-open transaction,
 * gives a managed instance whose lazy associations (notably [GenerationJob.invoice]) bind to THAT
 * transaction's session and whose `@Version` reflects the current row, not whatever [job]
 * happened to carry when it was originally read.
 *
 * A [job] with no id (never persisted) has nothing to re-read: falls back to [job] itself.
 * Likewise falls back to [job] if the row no longer resolves at all (deleted concurrently).
 */
internal fun GenerationJobRepository.reloadManaged(job: GenerationJob): GenerationJob =
    job.id?.let { findById(it) } ?: job

/**
 * finding-fixes (2026-08-31) — the two terminal `resultRefs.llmStatus`/[GenerationJob.llmStatus]
 * values a deferred oMLX callback ever reports; shared by [GenerationJobResultHandler.markCompleted]'s
 * ordering guard and [GenerationJobLlmResultHandler.mergeLlmResult] so both read the exact same
 * definition of "already resolved, never downgrade".
 */
internal fun isTerminalLlmStatus(status: String?): Boolean = status == "COMPLETED" || status == "FAILED"
