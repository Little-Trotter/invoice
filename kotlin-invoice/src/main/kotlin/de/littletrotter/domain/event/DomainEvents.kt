/*
 * Project: Little Trotter X-Invoice
 * Copyright (c) 2026 Eifel42 Stefan Zils
 * See LICENSE and README.md for license terms and documentation.
 *
 * Disclaimer: This software is provided as-is, without warranty of any kind. Use at your own risk.
 */

package de.littletrotter.domain.event

import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.LlmAnalysisStatus

/**
 * Base type for all domain events.
 * Events are created inside the domain aggregate and dispatched by the application layer.
 */
sealed class DomainEvent

data class InvoiceCreated(
    val invoiceId: Long,
    val invoiceNumber: String,
) : DomainEvent()

/**
 * An accepted offer became an invoice draft. [skippedAttachments] names the customer files that
 * stayed at the offer because the receipt pool only takes PDF documents; the clerk has to
 * attach them by hand.
 */
data class InvoiceCreatedFromQuote(
    val invoiceId: Long,
    val quoteNumber: String,
    val invoiceNumber: String,
    val skippedAttachments: List<String>,
) : DomainEvent()

/** An accepted offer could not be turned into an invoice draft; nothing was created. */
data class InvoiceCreationFromQuoteRejected(
    val quoteNumber: String,
    val reason: String,
) : DomainEvent()

data class InvoiceStatusChanged(
    val invoiceId: Long,
    val invoiceNumber: String,
    val from: InvoiceStatus,
    val to: InvoiceStatus,
) : DomainEvent()

data class InvoiceFinalized(
    val invoiceId: Long,
    val invoiceNumber: String,
) : DomainEvent()

data class InvoiceItemAdded(
    val invoiceId: Long,
    val invoiceNumber: String,
    val positionId: String,
) : DomainEvent()

data class InvoiceItemRemoved(
    val invoiceId: Long,
    val invoiceNumber: String,
    val positionId: String,
) : DomainEvent()

/**
 * Credit note created as draft; explicitly NOT a status change on original (GoBD).
 * No separate "finalized" event — [InvoiceFinalized] handles both.
 */
/** ST-3 — a sent invoice was cancelled: it moved to CANCELLED and the storno document named here
 *  came into being, already numbered and already terminal. Replaces the former
 *  `InvoiceCreditNoteDrafted`, whose name described a draft that no longer exists. */
data class InvoiceCancelled(
    val creditNoteId: Long,
    val creditNoteNumber: String,
    val originalInvoiceId: Long,
    val originalInvoiceNumber: String,
) : DomainEvent()

/**
 * L24 (fix-l24-draft-job-status-never-pushed) — fired for EVERY completed job with a
 * resolvable invoice, draft or issued alike: a job finishing is a real event the UI has to hear
 * about regardless of the invoice's status (see [de.littletrotter.application.invoice
 * .GenerationJobUiPushHandler], the one consumer that reads none of the fields below and pushes
 * unconditionally). [issued] carries what used to be [de.littletrotter.application.invoice
 * .GenerationJobService.markCompleted]'s own emission guard
 * (`invoice.status.isIssued || trigger == SEND`) — every OTHER consumer
 * ([de.littletrotter.application.invoice.DocumentGeneratedReactionHandler]'s four reactions,
 * [de.littletrotter.application.message.MessageCenterEventHandler]) still only acts when it is
 * `true`, preserving their exact pre-L24 behavior for a draft's job. Defaults to `true` so every
 * pre-L24 test/call site that never considered drafts keeps compiling and behaving unchanged.
 */
data class DocumentGenerated(
    val invoiceId: Long,
    val invoiceNumber: String,
    val generationJobId: Long,
    val resultRefs: String?,
    val issued: Boolean = true,
) : DomainEvent()

/**
 * Fired synchronously (`Event.fire`, never
 * `fireAsync`) right after
 * [de.littletrotter.application.invoice.GenerationJobService.createJob] persists a new job
 * PENDING, from inside that `@Transactional` method. Observed with
 * `@Observes(during = TransactionPhase.AFTER_SUCCESS)` in
 * [de.littletrotter.application.invoice.GenerationJobProcessor] — CDI defers a transactional
 * observer until the transaction actually commits, and only a *synchronous* `fire()` supports a
 * `TransactionPhase` at all (`@ObservesAsync` cannot declare one). The observer itself then hops
 * onto a worker thread before touching the job — never runs inline on whatever thread committed
 * the transaction (may be the Vaadin/UI request thread) — see
 * [de.littletrotter.application.invoice.GenerationJobProcessor.onGenerationJobCreated]'s own
 * KDoc for why.
 *
 * Purpose: eliminate the up-to-10s latency of waiting for the next
 * [de.littletrotter.application.invoice.GenerationJobProcessor.pollPendingJobs] tick — the
 * poller stays exactly as it is, as the recovery path for crashes/failures. The atomic
 * PENDING→DELEGATED claim
 * ([de.littletrotter.application.invoice.GenerationJobService.tryMarkDelegated]) makes this fast
 * path and the poller race-safe against each other; see
 * [de.littletrotter.application.invoice.GenerationJobFastPublishTransaction.publishOne]'s own KDoc for what
 * happens when the immediate publish attempt itself fails.
 */
data class GenerationJobCreated(val jobId: Long, val lane: DocumentJobLane) : DomainEvent()

/**
 * Fired when a [de.littletrotter.application.invoice.GenerationJobService.markFailed]
 * transition happens, i.e. the document-generation pipeline reported a failure for this job
 * (distinct from [DocumentGenerated], which only ever fires on a *completed* job — a completed
 * job can still carry a failed KoSIT outcome, that is not this event). No such signal existed
 * before the message center needed one; mirrors [DocumentGenerated]'s shape/firing site
 * (`fireAsync`, same invoice/job identity fields) so the message center's curated observer can
 * surface a generation failure even when nobody has an open view.
 *
 * L24 — same treatment as [DocumentGenerated]'s own [DocumentGenerated.issued]: fired for every
 * failed job with a resolvable invoice, draft or issued alike, so
 * [de.littletrotter.application.invoice.GenerationJobUiPushHandler] can push a failed draft job
 * too. [issued] carries [de.littletrotter.application.invoice.GenerationJobService.markFailed]'s
 * own former emission guard (`invoice.status.isIssued`) for
 * [de.littletrotter.application.message.MessageCenterEventHandler], the only other consumer.
 */
data class GenerationJobFailed(
    val invoiceId: Long,
    val invoiceNumber: String,
    val generationJobId: Long,
    val reason: String,
    val issued: Boolean = true,
) : DomainEvent()

/**
 * Fired when [de.littletrotter.application.invoice.GenerationJobService.failExhaustedJob]
 * transitions a job PENDING→FAILED because its stale-reset attempt budget
 * (`invoice.jobs.max-attempts`) is exhausted, instead of re-delegating it for yet another doomed
 * cycle. Distinct from [GenerationJobFailed] (a worker-reported failure, "Ergebnis" edge in
 * [de.littletrotter.domain.invoice.GenerationJobStateMachine]) — this one never reached a worker
 * at all in its final attempt; the message-center text this drives must say so, not read like a
 * generic generation failure. Mirrors [GenerationJobFailed]'s shape/firing style (`fireAsync`,
 * same invoice/job identity fields), plus [attempts] so the curated message can name the count.
 */
data class GenerationJobExhausted(
    val invoiceId: Long,
    val invoiceNumber: String,
    val generationJobId: Long,
    val attempts: Int,
    val reason: String,
) : DomainEvent()

/**
 * L3 (fix-generation-job-failure-visibility.md) — fired when [de.littletrotter.application.invoice
 * .GenerationJobService.markResultProcessingFailed] transitions a job DELEGATED/RUNNING→FAILED
 * because [de.littletrotter.infrastructure.messaging.GenerationJobResultConsumer] received a
 * worker result but this app's own processing of it kept failing until the consumer gave up
 * (simulated DLQ) after 3 attempts. Distinct from both [GenerationJobFailed] (the WORKER itself
 * reported the failure — the result that arrived here was, in fact, a genuine success or failure
 * outcome, never even durably recorded) and [GenerationJobExhausted] (the job never reached a
 * worker at all on its final attempt) — a result DID arrive this time, only this application's own
 * persistence of it never completed; the message-center text says so instead of reading like a
 * generic or budget-exhaustion failure. Mirrors [GenerationJobFailed]'s shape/firing style
 * (`fireAsync`, same invoice/job identity fields).
 */
data class GenerationJobResultProcessingFailed(
    val invoiceId: Long,
    val invoiceNumber: String,
    val generationJobId: Long,
    val reason: String,
) : DomainEvent()

/**
 * Fired when
 * [de.littletrotter.application.invoice.GenerationJobService.updateLlmAnalysisFromResult]
 * resolves an [de.littletrotter.domain.invoice.InvoiceLlmAnalysis] to a terminal, model-reported
 * outcome ([LlmAnalysisStatus.COMPLETED]/[LlmAnalysisStatus.FAILED]). Deliberately not fired for
 * [LlmAnalysisStatus.DISABLED]/still-[LlmAnalysisStatus.PENDING] resolutions (not run, or not yet
 * run) — those are not what the "LLM analysis result" curated list means. Also not fired from
 * [de.littletrotter.application.invoice.GenerationJobService.failPendingAnalysesForInvoice] — that
 * bulk-fails every still-PENDING analysis as a side effect of the *job* failing, which
 * [GenerationJobFailed] already reports; firing this too would just duplicate that message.
 */
data class InvoiceLlmAnalysisResolved(
    val invoiceId: Long,
    val invoiceNumber: String,
    val modelName: String,
    val status: LlmAnalysisStatus,
) : DomainEvent()

/** Audit trail for explicit party data refresh from master data. */
data class PartySnapshotsRefreshed(
    val invoiceId: Long,
    val invoiceNumber: String,
    val changedFields: List<String>,
) : DomainEvent()
