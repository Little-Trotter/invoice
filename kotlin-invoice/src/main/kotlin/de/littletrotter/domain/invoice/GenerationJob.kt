package de.littletrotter.domain.invoice

import de.littletrotter.domain.invoice.converters.DocumentJobLaneConverter
import de.littletrotter.domain.invoice.converters.GenerationJobStatusConverter
import de.littletrotter.domain.invoice.converters.GenerationJobTriggerConverter
import de.littletrotter.domain.shared.CreationStampListener
import de.littletrotter.domain.shared.StampedOnCreation
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.validation.constraints.Size
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.OffsetDateTime

@Entity
@Table(name = "generation_job")
@EntityListeners(CreationStampListener::class)
class GenerationJob : StampedOnCreation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
    protected set

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id")
    var invoice: Invoice? = null
    protected set

    @field:Size(max = 50)
    @Column(name = "job_type", nullable = false, length = 50)
    var jobType: String = ""
    protected set

    @Column(nullable = false)
    @Convert(converter = GenerationJobStatusConverter::class)
    var status: GenerationJobStatus = GenerationJobStatus.PENDING
    protected set

    @Column(name = "result_refs", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    var resultRefs: String? = null
    protected set

    @Column(name = "kosit_report", columnDefinition = "TEXT")
    var kositReport: String? = null
    protected set

    @field:Size(max = 100)
    @Column(name = "correlation_id", length = 100)
    var correlationId: String? = null
    protected set

    // Set by the creator from its TimeSource; if it forgets, [CreationStampListener] stamps the
    // insert moment. [StampedOnCreation.UNSET] only exists because JPA needs a no-arg constructor.
    @Column(name = "created_at")
    var createdAt: OffsetDateTime = StampedOnCreation.UNSET
    protected set

    @Column(name = "started_at")
    var startedAt: OffsetDateTime? = null
    protected set

    @Column(name = "finished_at")
    var finishedAt: OffsetDateTime? = null
    protected set

    /** When this job last transitioned to [GenerationJobStatus.DELEGATED], set in the same
     *  write as that transition ([de.littletrotter.infrastructure.persistence
     *  .PanacheGenerationJobRepository.tryMarkDelegated]/[de.littletrotter.application.invoice
     *  .GenerationJobService.markDelegated]). Replaces [createdAt] as the stale-DELEGATED candidate
     *  clock (see [de.littletrotter.application.invoice.StaleDelegatedResetCoordinator]'s own KDoc
     *  for why `createdAt` re-selected an already-reset, merely-requeued job as stale again on the
     *  very next tick). Nulled again on every transition back to `PENDING`. */
    @Column(name = "delegated_at")
    var delegatedAt: OffsetDateTime? = null
    protected set

    /** The JetStream stream sequence of this job's own `document-jobs` publish
     *  ([io.nats.client.api.PublishAck.seqno]), recorded once the publish ack arrives
     *  ([de.littletrotter.application.invoice.DocumentJobPublishTransaction.publishTransactional]).
     *  `null` while the ack has not (yet, or ever) arrived — a stale-DELEGATED candidate with no
     *  recorded sequence is treated as evidence-free and reset outright (legacy row / lost ack), see
     *  [de.littletrotter.application.invoice.StaleDelegatedResetDecider]'s own KDoc. Nulled again on
     *  every transition back to `PENDING`, same as [delegatedAt]. */
    @Column(name = "delegated_stream_seq")
    var delegatedStreamSeq: Long? = null
    protected set

    /** V913 — how many times [de.littletrotter.application.invoice
     *  .StaleDelegatedResetCoordinator]'s stale-DELEGATED sweep has reset this job back to
     *  PENDING. Bumped in the same bulk `UPDATE` as the DELEGATED→PENDING reset itself
     *  (`PanacheGenerationJobRepository.resetStaleDelegatedByIds`) — always: every actual
     *  reset now means "a worker had this message and it is gone" (see
     *  [de.littletrotter.application.invoice.StaleDelegatedResetDecider]'s own KDoc), a job merely
     *  queued behind a backlog is never even a reset candidate, so there is no longer a case where
     *  a stale reset fires without a real attempt having been spent. Once it reaches
     *  `invoice.jobs.max-attempts`, the poller fails the job instead of re-delegating it
     *  (`GenerationJobService.failExhaustedJob`) — the attempt budget that replaces the former
     *  infinite reset/republish/fail loop. The exhaustion-reopen path also resets this back to `0` when
     *  `GenerationJobService.reopenExhaustedJobsWithoutDocument` reopens a budget-exhausted
     *  `FAILED` job — a reopened job gets a full fresh budget, not one it would immediately
     *  re-exhaust on the very next stale check. */
    @Column(nullable = false)
    var attempts: Int = 0
    protected set

    /** V913 — plain-text reason for the job's last recorded failure, for an operator reading
     *  the Druckaufträge grid: either the attempt-budget-exhaustion message
     *  ([de.littletrotter.application.invoice.GenerationJobService.failExhaustedJob]), the DLQ
     *  give-up reason ([de.littletrotter.application.invoice.GenerationJobService
     *  .markResultProcessingFailed], L3 fix-generation-job-failure-visibility.md — the terminal
     *  reason, stamped in the SAME write as the DELEGATED/RUNNING→FAILED transition, not merely a
     *  diagnostic note left behind on an otherwise-unchanged status), or that same DLQ path's
     *  best-effort [de.littletrotter.application.invoice.GenerationJobService.recordLastError]
     *  fallback for the rarer case where the job was already terminal or unresolvable by the time
     *  the give-up ran. Never read by any transition guard; purely diagnostic. */
    @Column(name = "last_error", columnDefinition = "TEXT")
    var lastError: String? = null
    protected set

    /** V913 — the worker-reported LLM sub-status (`PENDING`/`COMPLETED`/`FAILED`/`DISABLED`/
     *  `NOT_REQUESTED`, see `JobConsumer`/`LlmAnalysisStage` in kotlin-xpdf), persisted as a first-
     *  class column instead of only living inside the [resultRefs] JSON blob it always also still
     *  carries — the wire format (xpdf → app) is unchanged, this column is filled from the same
     *  `llmStatus` key on write ([de.littletrotter.application.invoice.GenerationJobService
     *  .markCompleted]/`markFailed`/`mergeLlmResult`). UI/queries should read this column, not
     *  parse the JSON. Deliberately NOT part of [GenerationJobStateMachine] — the deferred LLM
     *  callback never changes [status], only this field and [resultRefs]. */
    @field:Size(max = 20)
    @Column(name = "llm_status", length = 20)
    var llmStatus: String? = null
    protected set

    /** The AI-check choice made at [de.littletrotter.application.invoice
     *  .GenerationJobService.createJob] time, persisted so it survives unchanged into whichever
     *  publish path reaches this job first (see [de.littletrotter.application.invoice
     *  .DocumentJobPublisher.publish], the single publish path every caller — the UI's print
     *  button, [de.littletrotter.application.invoice.GenerationJobFastPublishTransaction.publishOne]'s fast
     *  path, and its `pollPendingJobs` republish — funnels through). Never overridden by a
     *  caller-supplied argument after creation; a race between publish paths can therefore no
     *  longer produce two different payloads for the same job. */
    @Column(name = "llm_optimize", nullable = false)
    var llmOptimize: Boolean = false
    protected set

    /** Whether this job was created for an already-issued invoice
     *  ([InvoiceStatus.ISSUED]/[InvoiceStatus.CANCELLED], including the cancellation document 384), set
     *  exactly once by [de.littletrotter.application.invoice.GenerationJobService.createJob] from
     *  the invoice's status at that moment and never changed afterward. The single-generation
     *  gate ([de.littletrotter.domain.repository.GenerationJobRepository.hasCompletedIssuedJob])
     *  locks on a COMPLETED job with this flag `true`, not on any completed job: a review-time
     *  draft job (created while the invoice was still DRAFT/IN_REVIEW) stays `false` forever, so it
     *  can never lock generation once the invoice is later sent — the defect this column fixes. */
    @Column(nullable = false)
    var issued: Boolean = false
    protected set

    /** Why this job exists: see [GenerationJobTrigger]'s own KDoc.
     *  Set exactly once, at creation, from the caller's own intent — never changed afterward,
     *  same "write once" discipline as [issued]. A `SEND`-triggered job is the one case [issued]
     *  is forced `true` regardless of the invoice's status at that moment (see [issued]'s own
     *  KDoc and [de.littletrotter.application.invoice.GenerationJobService.createJob]). */
    @Column(name = "trigger_type", nullable = false)
    @Convert(converter = GenerationJobTriggerConverter::class)
    var trigger: GenerationJobTrigger = GenerationJobTrigger.MANUAL
    protected set

    /** Queue lane this job is published on, fixed at creation and used by every republish
     *  (poller, fast path, recovery). Separate from [trigger] because MANUAL covers both the UI
     *  print button and the scheduled and admin sweeps. */
    @Column(name = "job_lane", nullable = false)
    @Convert(converter = DocumentJobLaneConverter::class)
    var lane: DocumentJobLane = DocumentJobLane.BULK
    protected set

    /** Records that the job was handed to the queue: moves it to DELEGATED and starts the
     *  stale-DELEGATED clock. */
    fun markDelegated(at: OffsetDateTime) {
        transitionTo(GenerationJobStatus.DELEGATED)
        delegatedAt = at
    }

    /** Remembers the stream sequence of the job's own publish; only meaningful while DELEGATED. */
    fun recordDelegatedStreamSeq(streamSeq: Long) {
        delegatedStreamSeq = streamSeq
    }

    /** A worker picked the job up. */
    fun markStarted(at: OffsetDateTime) {
        startedAt = at
        transitionTo(GenerationJobStatus.RUNNING)
    }

    /** Stale-DELEGATED reset: back to PENDING, one attempt spent, queue evidence cleared. */
    fun resetStaleDelegated() {
        transitionTo(GenerationJobStatus.PENDING)
        attempts += 1
        delegatedAt = null
        delegatedStreamSeq = null
    }

    /** Reopens a budget-exhausted FAILED job with a full fresh attempt budget. */
    fun reopenExhausted() {
        transitionTo(GenerationJobStatus.PENDING)
        attempts = 0
        finishedAt = null
    }

    /** Terminal failure with the operator-visible reason, stamped together with the transition. */
    fun fail(lastError: String, finishedAt: OffsetDateTime) {
        transitionTo(GenerationJobStatus.FAILED)
        this.lastError = lastError
        this.finishedAt = finishedAt
    }

    /** Diagnostic note that leaves [status] untouched. */
    fun recordLastError(message: String) {
        lastError = message
    }

    /** Stores the worker outcome. A null [llmStatus] keeps the stored value. */
    fun recordResult(resultRefs: String?, kositReport: String?, llmStatus: String?, finishedAt: OffsetDateTime) {
        this.finishedAt = finishedAt
        this.resultRefs = resultRefs
        this.kositReport = kositReport
        if (llmStatus != null) this.llmStatus = llmStatus
    }

    /** Replaces the stored validation report when a check that ran after storage appends its stage. */
    fun replaceKositReport(report: String) {
        kositReport = report
    }

    /** Replaces the result references after the deferred LLM callback; never touches [status]. */
    fun updateResultRefs(resultRefs: String, llmStatus: String? = null) {
        this.resultRefs = resultRefs
        if (llmStatus != null) this.llmStatus = llmStatus
    }

    /**
     * The single place that changes [status]. Asserts the edge is legal via
     * [GenerationJobStateMachine] (throws [IllegalStateException] on an illegal one, see that
     * object's own KDoc for the full edge table and for which call sites this does NOT cover —
     * the bulk-JPQL repository updates, which encode the same edges directly in their `WHERE`
     * clause instead). Every status write in [de.littletrotter.application.invoice
     * .GenerationJobService] that mutates an already-loaded, in-memory [GenerationJob] goes
     * through this rather than assigning [status] directly.
     */
    fun transitionTo(newStatus: GenerationJobStatus) {
        GenerationJobStateMachine.assertLegal(status, newStatus)
        status = newStatus
    }

    /**
     * The safety net for the one race this task fixes: a genuine worker result
     * (`COMPLETED`/`FAILED`, real `resultRefs`) arriving for a job that
     * [de.littletrotter.application.invoice.GenerationJobProcessor.resetStaleDelegatedJobs]
     * already reset back to `PENDING` out from under a still-in-flight worker attempt (see that
     * method's own KDoc, and `JobConsumer.receive`'s KDoc in kotlin-xpdf for the heartbeat-gap fix
     * that closes the root cause). Before this, markCompleted called [transitionTo] directly,
     * which threw on `PENDING -> COMPLETED` (not a legal edge — see [GenerationJobStateMachine]'s
     * table) — a real, already-generated document was then discarded to the DLQ purely because
     * local bookkeeping had diverged from what the worker had actually, successfully done.
     * (`PENDING -> FAILED` was already a legal edge before this task, for the unrelated
     * attempt-budget-exhaustion case — this method covers it too, uniformly, for the same
     * late-result reasoning, not because it was ever illegal.)
     *
     * This does NOT add a new edge to [GenerationJobStateMachine]: reaching this consumer's
     * `COMPLETED`/`FAILED` branch for job id N at all is only possible because some worker
     * actually processed job N at some point (the result subject is never written to by anything
     * else), so a currently-`PENDING` job N was, by construction, `DELEGATED` at least once before
     * — this bridges through that already-legal `PENDING -> DELEGATED` edge before the equally
     * already-legal `DELEGATED -> COMPLETED`/`DELEGATED -> FAILED` edge, modeling the race as
     * "reclaiming a dispatch that turned out to still be alive", not as a new terminal transition
     * from `PENDING`. A job already in `DELEGATED`/`RUNNING` (the non-racy, common case) skips the
     * bridge and this is exactly [transitionTo] alone.
     */
    fun reclaimAndTransitionTo(newStatus: GenerationJobStatus) {
        if (status == GenerationJobStatus.PENDING) {
            transitionTo(GenerationJobStatus.DELEGATED)
        }
        transitionTo(newStatus)
    }

    override fun stampCreationIfUnset(now: OffsetDateTime) {
        if (createdAt == StampedOnCreation.UNSET) createdAt = now
    }

    private fun populate(invoice: Invoice?, details: GenerationJobDetails, createdAt: OffsetDateTime) {
        this.invoice = invoice
        jobType = details.jobType
        status = GenerationJobStatus.PENDING
        trigger = details.trigger
        lane = details.lane
        llmOptimize = details.llmOptimize
        issued = details.issued
        correlationId = details.correlationId
        this.createdAt = createdAt
    }

    /** Carrier of a message copy: only the fields the queue payload is built from. */
    private fun populateMessageCopy(id: Long, invoice: Invoice, jobType: String) {
        this.id = id
        this.invoice = invoice
        this.jobType = jobType
    }

    companion object {
        /** A new PENDING job. [GenerationJobDetails.issued] is fixed here and never changes. */
        fun create(invoice: Invoice, details: GenerationJobDetails, createdAt: OffsetDateTime): GenerationJob =
            GenerationJob().also { it.populate(invoice, details, createdAt) }

        /** A transient, never persisted copy that only carries what the publish payload needs. */
        fun messageCopy(id: Long, invoice: Invoice, jobType: String): GenerationJob =
            GenerationJob().also { it.populateMessageCopy(id, invoice, jobType) }

        /** The fixed prefix [de.littletrotter.application.invoice.GenerationJobService
         *  .failExhaustedJob] stamps onto [lastError] for the attempt-budget-exhaustion transition.
         *  Shared with [de.littletrotter.infrastructure.persistence.PanacheGenerationJobRepository
         *  .findExhaustedJobsWithoutDocument]'s own `LIKE` query so the two can never drift apart —
         *  one is forbidden from re-deriving the other's exact string independently. */
        const val ATTEMPT_BUDGET_EXHAUSTED_REASON_PREFIX = "Attempt budget exhausted"
    }
}
