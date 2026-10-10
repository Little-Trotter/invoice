package de.littletrotter.domain.repository

import de.littletrotter.domain.invoice.DocumentEngine
import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.GenerationJobTrigger
import de.littletrotter.shared.GermanTime
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

interface GenerationJobRepository {
    fun findByInvoiceId(invoiceId: Long): List<GenerationJob>
    fun findByInvoiceIdWithTaxRates(invoiceId: Long): List<GenerationJob>

    /**
     * Whether [invoiceId] already has at least one [GenerationJob] that reached
     * [GenerationJobStatus.COMPLETED] with [GenerationJob.issued] `true`. This is the
     * single-generation gate's own query: once true for an issued invoice (see
     * [de.littletrotter.domain.invoice.isIssued]),
     * [de.littletrotter.application.invoice.GenerationJobService.createJob] rejects any further
     * job for it. Deliberately not scoped by [GenerationJob.jobType] — every job kind's artifacts
     * land on the same invoice's deterministic export keys, so any completed *issued* job locks
     * generation regardless of which kind it was. A completed job with [GenerationJob.issued]
     * `false` (a review-time draft job, created while the invoice was still DRAFT/IN_REVIEW) does
     * NOT count — that is exactly the N13 fix: an invoice validated before being sent must not be
     * locked by the draft PDF its own review pass already produced. Default implementation (filter
     * [findByInvoiceId]) for in-memory test doubles;
     * [de.littletrotter.infrastructure.persistence.PanacheGenerationJobRepository] overrides it
     * with a real `COUNT` query.
     */
    fun hasCompletedIssuedJob(invoiceId: Long): Boolean =
        findByInvoiceId(invoiceId).any { it.status == GenerationJobStatus.COMPLETED && it.issued }

    /**
     * "Guard against concurrent/duplicate sends" (PO decision 2026-09-13) —
     * whether [invoiceId] already has a non-terminal (PENDING/DELEGATED/RUNNING) job with the
     * given [trigger]. [de.littletrotter.application.invoice.GenerationJobService.createJob]
     * calls this only for `trigger == SEND`, to reject a second concurrent "Senden" click outright
     * rather than queueing a duplicate job. Default implementation (filter [findByInvoiceId]) for
     * in-memory test doubles; [de.littletrotter.infrastructure.persistence
     * .PanacheGenerationJobRepository] overrides it with a real `COUNT` query.
     */
    fun hasNonTerminalTriggerJob(invoiceId: Long, trigger: GenerationJobTrigger): Boolean =
        findByInvoiceId(invoiceId).any {
            it.trigger == trigger &&
                it.status in setOf(GenerationJobStatus.PENDING, GenerationJobStatus.DELEGATED, GenerationJobStatus.RUNNING)
        }

    /**
     * Whether [invoiceId] already has a non-terminal (PENDING/DELEGATED/RUNNING) job of
     * [jobType] for the same variant ([engine], [llmOptimize]). Backs the idempotency guard that
     * rejects a second concurrent print job for the same document. Default implementation for
     * in-memory test doubles;
     * [de.littletrotter.infrastructure.persistence.PanacheGenerationJobRepository] overrides it
     * with a real `COUNT` query.
     */
    fun hasNonTerminalJob(invoiceId: Long, jobType: String, engine: DocumentEngine, llmOptimize: Boolean): Boolean =
        findByInvoiceId(invoiceId).any {
            it.jobType == jobType && it.engine == engine && it.llmOptimize == llmOptimize &&
                it.status in setOf(GenerationJobStatus.PENDING, GenerationJobStatus.DELEGATED, GenerationJobStatus.RUNNING)
        }

    /**
     * Ids of issued credit notes (born cancelled, never sent) that have no generation job of any
     * kind, oldest first, at most [limit]. A credit note passes no send step, so its first
     * document job is requested right after the storno commit; this is the durable fallback for
     * a crash between the commit and that request. A failed job still counts as a job: the
     * sweep must not re-enqueue on every tick. Default implementation for in-memory test doubles.
     */
    fun findCreditNoteIdsWithoutJob(limit: Int): List<Long> = emptyList()

    fun findPending(): List<GenerationJob>
    /** Like [findPending] but eagerly fetches [GenerationJob.invoice] so it can be passed to a NATS publisher. */
    fun findPendingWithInvoice(): List<GenerationJob>
    fun findById(id: Long): GenerationJob?

    /**
     * Like [findById] but eagerly fetches [GenerationJob.invoice], for the fast-path
     * publish (`GenerationJobProcessor.publishOne`) which runs outside any request-scoped
     * persistence context. Default delegates to [findById] — in-memory test doubles have no lazy
     * associations to worry about; only
     * [de.littletrotter.infrastructure.persistence.PanacheGenerationJobRepository] overrides it
     * with a real `JOIN FETCH`.
     */
    fun findByIdWithInvoice(id: Long): GenerationJob? = findById(id)

    fun save(job: GenerationJob): GenerationJob
    /**
     * Atomically sets a job's status from PENDING to DELEGATED using a WHERE-guarded UPDATE.
     * Returns true if the row was updated (job was still PENDING), false if another thread
     * already changed the status (idempotency guard against double-publish). The real
     * ([de.littletrotter.infrastructure.persistence.PanacheGenerationJobRepository]) stamp for
     * [GenerationJob.delegatedAt] comes from that implementation's own injected TimeSource — kept
     * out of this signature so the ~25 in-memory test doubles across the suite that already
     * implement this narrow interface (most not caring about the exact timestamp at all) don't
     * all need a value threaded through them too.
     */
    fun tryMarkDelegated(id: Long): Boolean

    /**
     * The reverse of [tryMarkDelegated]: atomically reverts a job from DELEGATED back to
     * PENDING via a WHERE-guarded UPDATE, used when the fast path's immediate publish attempt
     * right after its own [tryMarkDelegated] claim fails. Returns true if the row was updated.
     * Default implementation (mutate + [save]) for in-memory test doubles;
     * [de.littletrotter.infrastructure.persistence.PanacheGenerationJobRepository] overrides it
     * with a real guarded `UPDATE`.
     */
    fun revertToPending(id: Long): Boolean {
        val job = findById(id) ?: return false
        if (job.status != GenerationJobStatus.DELEGATED) return false
        job.transitionTo(GenerationJobStatus.PENDING)
        save(job)
        return true
    }

    /**
     * The read half of the stale-DELEGATED recovery: `DELEGATED` jobs with no
     * [GenerationJob.startedAt] whose [GenerationJob.delegatedAt] is older than
     * [staleCutoffMinutes] minutes, ordered by id (Fix U — the ascending-id order every single-row
     * writer already uses, so the follow-up [resetStaleDelegatedByIds] call never has to lock rows
     * out of order). A plain read, deliberately without `FOR UPDATE`: [de.littletrotter.application
     * .invoice.StaleDelegatedResetCoordinator] calls this, then decides — off-transaction, against
     * the real message broker — which of the candidates to actually reset, and only then opens the
     * short, lock-holding write transaction ([resetStaleDelegatedByIds]). Holding a Postgres row
     * lock across an arbitrary-latency NATS round trip is exactly what this two-phase split avoids.
     * Default `emptyList()` — matching [findExhaustedJobsWithoutDocument]'s own "nothing to do"
     * default — for in-memory test doubles that don't care about this scan.
     */
    fun findStaleDelegatedCandidates(staleCutoffMinutes: Long = 2): List<StaleDelegatedCandidate> = emptyList()

    /**
     * The write half: resets exactly the given [ids] back to `PENDING`, incrementing
     * [GenerationJob.attempts] and clearing [GenerationJob.delegatedAt]/
     * [GenerationJob.delegatedStreamSeq] — every actual reset now always means "a worker had this
     * message and it is gone" (see [de.littletrotter.application.invoice
     * .StaleDelegatedResetDecider]'s own KDoc), so the increment is unconditional; there is no
     * longer a worker-liveness flag to gate it. Only ids still `DELEGATED` at write time are
     * touched (the `WHERE status = DELEGATED` clause on the real per-id `UPDATE` in
     * [de.littletrotter.infrastructure.persistence.PanacheGenerationJobRepository
     * .resetStaleDelegatedByIds] is the guard for this edge — a row [findStaleDelegatedCandidates]
     * saw as a candidate but that moved on before this write runs is silently skipped, same
     * idiom as every other bulk-JPQL method here).
     *
     * Fix U (generation-job-deadlock-demo-burst) — id-ordered, `SKIP LOCKED`, one row per
     * statement: a row a concurrent publish transaction currently holds is left DELEGATED for this
     * tick rather than waited on. See the real implementation's own KDoc for the deadlock this
     * replaced. Returns the number of rows actually reset. Default implementation (mutate + [save],
     * guarded via [GenerationJob.transitionTo]) for in-memory test doubles;
     * [de.littletrotter.infrastructure.persistence.PanacheGenerationJobRepository] overrides it
     * with the real guarded, id-ordered bulk `UPDATE`.
     */
    fun resetStaleDelegatedByIds(ids: List<Long>): Int {
        var reset = 0
        ids.forEach { id ->
            val job = findById(id) ?: return@forEach
            if (job.status != GenerationJobStatus.DELEGATED) return@forEach
            job.transitionTo(GenerationJobStatus.PENDING)
            job.attempts += 1
            job.delegatedAt = null
            job.delegatedStreamSeq = null
            save(job)
            reset++
        }
        return reset
    }

    /**
     * Records the JetStream stream sequence of this job's own `document-jobs` publish
     * ([io.nats.client.api.PublishAck.seqno]), called synchronously by the same caller that just
     * claimed the `PENDING -> DELEGATED` transition and is still blocked on the publish ack
     * ([de.littletrotter.application.invoice.GenerationJobFastPublishTransaction.publishOne],
     * [de.littletrotter.application.invoice.GenerationJobProcessor.scanPendingJobs]) — same
     * thread, same transaction as that claim. `WHERE status = DELEGATED` guards against the one
     * remaining race (the job moved on again between the claim and this call); returns `false`
     * with nothing recorded in that case. Default implementation (mutate + [save], guarded via a
     * plain status check) for in-memory test doubles;
     * [de.littletrotter.infrastructure.persistence.PanacheGenerationJobRepository] overrides it
     * with a real guarded bulk `UPDATE`.
     */
    fun recordDelegatedStreamSeq(id: Long, streamSeq: Long): Boolean {
        val job = findById(id) ?: return false
        if (job.status != GenerationJobStatus.DELEGATED) return false
        job.delegatedStreamSeq = streamSeq
        save(job)
        return true
    }

    /**
     * Whether at least one [GenerationJob] with the given
     * [engine] was created at or after [since]. The idle-stop scheduler
     * ([de.littletrotter.application.invoice.FacturXEngineIdleStopScheduler]) reads this instead
     * of keeping an in-process "last used" timestamp, because [EngineActivation.ensureRunning
     * ][de.littletrotter.application.invoice.EngineActivation.ensureRunning] can fire from either
     * the main app container (switch toggle, fast-path publish) or the job-worker container
     * (poller re-publish) — two separate JVMs with no shared memory — while this table is the one
     * thing both already agree on. Default `true` (nothing to reset) for in-memory test doubles
     * that never override it, matching this interface's existing default-method convention (see
     * [revertToPending]) — a test that neither stubs nor cares about idle-stop stays unaffected.
     */
    fun existsRecentByEngine(engine: DocumentEngine, since: OffsetDateTime): Boolean = true

    /**
     * [de.littletrotter.application.invoice.GenerationJobService
     * .resetStaleLlmPending]'s own bounded read: COMPLETED jobs whose
     * [GenerationJob.finishedAt] falls in the half-open window `[from, to)`. Narrowed by this
     * timestamp window (not a direct `resultRefs->>'llmStatus' = 'PENDING'` query) because
     * `resultRefs` is a `jsonb` column with no portable JPQL operator for it — the actual
     * `llmStatus` check happens in Kotlin, after this method's own SQL-level filter has already
     * bounded the scan to a small, recent slice instead of the whole table. Default `emptyList()`
     * — matching [existsRecentByEngine]'s own "nothing to do" default — for in-memory test doubles
     * that don't care about this scan.
     */
    fun findCompletedBetween(from: OffsetDateTime, to: OffsetDateTime): List<GenerationJob> = emptyList()

    /**
     * FAILED jobs whose [GenerationJob.lastError] was stamped by
     * [failExhausted]'s own attempt-budget-exhaustion message (starts with [GenerationJob
     * .ATTEMPT_BUDGET_EXHAUSTED_REASON_PREFIX] — never any other FAILED reason) AND whose invoice
     * has no `COMPLETED` [GenerationJob] at all yet, from any job. [de.littletrotter.application
     * .invoice.GenerationJobService.reopenExhaustedJobsWithoutDocument] reads this on every
     * recovery-interval tick, unconditionally — reopens each one via [reopenExhausted] — so a job
     * that burned its whole budget purely because a worker was down, before this recovery existed, or a worker
     * outage longer than the budget allows, still ends up with a document once a worker comes
     * back, without operator action. Default `emptyList()` — matching [findCompletedBetween]'s own
     * "nothing to do" default — for in-memory test doubles that don't care about this scan.
     */
    fun findExhaustedJobsWithoutDocument(): List<GenerationJob> = emptyList()

    /**
     * The write half of [findExhaustedJobsWithoutDocument]'s recovery: atomically
     * reopens a job that is still FAILED (the `WHERE status = FAILED` clause IS the guard for this
     * one narrow FAILED→PENDING edge [de.littletrotter.domain.invoice.GenerationJobStateMachine]
     * documents — same bulk-UPDATE-with-WHERE-guard exception as [failExhausted]/[tryMarkDelegated]
     * above), resetting [GenerationJob.attempts] to `0` (a full fresh budget) and clearing
     * [de.littletrotter.domain.invoice.GenerationJob.finishedAt]. Returns `false` (no-op) if the
     * job was no longer FAILED by the time this ran (e.g. a concurrent manual retry already moved
     * it). Default implementation (mutate + [save], guarded via [GenerationJob.transitionTo]) for
     * in-memory test doubles; [de.littletrotter.infrastructure.persistence
     * .PanacheGenerationJobRepository] overrides it with a real guarded bulk `UPDATE`.
     */
    fun reopenExhausted(id: Long): Boolean {
        val job = findById(id) ?: return false
        if (job.status != GenerationJobStatus.FAILED) return false
        job.transitionTo(GenerationJobStatus.PENDING)
        job.attempts = 0
        job.finishedAt = null
        save(job)
        return true
    }

    /**
     * The attempt-budget-exhaustion transition: atomically fails a job that is still
     * PENDING (the `WHERE status = PENDING` clause IS the guard for this bulk-style write — see
     * [de.littletrotter.infrastructure.persistence.PanacheGenerationJobRepository
     * .failExhausted]'s own KDoc), stamping [lastError] and [de.littletrotter.domain.invoice
     * .GenerationJob.finishedAt]. Returns `false` (no-op) if the job was no longer PENDING by the
     * time this ran (already delegated by a racing publish, or already terminal) — mirrors
     * [tryMarkDelegated]'s own idempotency-guard shape.
     *
     * Default implementation (mutate + [save], guarded via [GenerationJob.transitionTo] — so an
     * illegal call from a non-PENDING job still throws rather than silently corrupting state) for
     * in-memory test doubles; [de.littletrotter.infrastructure.persistence
     * .PanacheGenerationJobRepository] overrides it with a real guarded bulk `UPDATE`.
     */
    fun failExhausted(id: Long, lastError: String, finishedAt: OffsetDateTime): Boolean {
        val job = findById(id) ?: return false
        if (job.status != GenerationJobStatus.PENDING) return false
        job.transitionTo(GenerationJobStatus.FAILED)
        job.lastError = lastError
        job.finishedAt = finishedAt
        save(job)
        return true
    }

    /**
     * Best-effort diagnostic write: records [message] on [de.littletrotter.domain.invoice
     * .GenerationJob.lastError] without touching [de.littletrotter.domain.invoice.GenerationJob
     * .status] at all (not a transition, no guard involved). Used by
     * [de.littletrotter.infrastructure.messaging.GenerationJobResultConsumer]'s DLQ path as the
     * fallback when [failDlq] itself was a no-op (job already terminal, or no longer resolves at
     * all) — so an operator still sees WHY a job kept failing processing even in that edge case.
     * Returns `false` (no-op) if [id] no longer resolves to a row. Default implementation (mutate +
     * [save]) for in-memory test doubles; [de.littletrotter.infrastructure.persistence
     * .PanacheGenerationJobRepository] overrides it with a plain, unconditional bulk `UPDATE`.
     */
    fun recordLastError(id: Long, message: String): Boolean {
        val job = findById(id) ?: return false
        job.lastError = message
        save(job)
        return true
    }

    /**
     * L3 (fix-generation-job-failure-visibility.md) — the DLQ "give up" transition: atomically
     * fails a job that is still DELEGATED or RUNNING (the `WHERE j.status IN (...)` clause IS the
     * guard for this write — [de.littletrotter.domain.invoice.GenerationJobStateMachine]'s existing
     * DELEGATED/RUNNING→FAILED "result" edges already cover this case, no new edge needed: a
     * result DID arrive, this application's own processing of it is what never completed),
     * stamping [message] onto [de.littletrotter.domain.invoice.GenerationJob.lastError] and
     * [de.littletrotter.domain.invoice.GenerationJob.finishedAt]. Used by
     * [de.littletrotter.application.invoice.GenerationJobService.markResultProcessingFailed] from
     * [de.littletrotter.infrastructure.messaging.GenerationJobResultConsumer]'s DLQ path, once that
     * consumer's own 3-attempt retry loop gives up on a message it could not durably process —
     * before this, the job was left DELEGATED/RUNNING forever ("Weitergeleitet"/"In Bearbeitung" in
     * the UI) with only [recordLastError]'s best-effort diagnostic note, no terminal user-visible
     * failure state. Returns `false` (no-op, caller falls back to [recordLastError]) if the job was
     * already terminal or was never delegated at all by the time this ran — mirrors
     * [failExhausted]'s own idempotency-guard shape. Default implementation (mutate + [save]) for
     * in-memory test doubles; [de.littletrotter.infrastructure.persistence
     * .PanacheGenerationJobRepository] overrides it with a real guarded bulk `UPDATE`.
     */
    fun failDlq(id: Long, message: String, finishedAt: OffsetDateTime): Boolean {
        val job = findById(id) ?: return false
        if (job.status != GenerationJobStatus.DELEGATED && job.status != GenerationJobStatus.RUNNING) return false
        job.transitionTo(GenerationJobStatus.FAILED)
        job.lastError = message
        job.finishedAt = finishedAt
        save(job)
        return true
    }

    /**
     * The global "Druckaufträge" listing: every [GenerationJob] across every invoice,
     * newest first (`createdAt DESC, id DESC`), fetch-joining [GenerationJob.invoice] and its
     * billing recipient in one query so [de.littletrotter.application.invoice
     * .PrintJobOverviewQueryServiceImpl] never triggers a lazy load per row (no N+1).
     *
     * Plain filter parameters, not the application-layer `GenerationJobFilter` DTO: a domain
     * port must not depend on `application` (`ArchitectureRulesTest`) —
     * [de.littletrotter.application.invoice.PrintJobOverviewQueryServiceImpl] unpacks its filter
     * DTO into these before calling in, the same convention [SystemMessageRepository.find]'s own
     * KDoc documents. Every parameter is optional; an unset one applies no restriction.
     *
     * Default `emptyList()` — matching [existsRecentByEngine]'s/[findCompletedBetween]'s own
     * "nothing to do" default — for in-memory test doubles that don't care about this listing;
     * [de.littletrotter.infrastructure.persistence.PanacheGenerationJobRepository] overrides it
     * with the real fetch-joined, paged query.
     */
    fun findPaged(
        statuses: Set<GenerationJobStatus> = emptySet(),
        recipientId: Long? = null,
        createdFrom: OffsetDateTime? = null,
        createdTo: OffsetDateTime? = null,
        offset: Int = 0,
        limit: Int = 60,
    ): List<GenerationJob> = emptyList()

    /** Row count matching [findPaged] — drives the callback data provider's total. Default `0L`
     *  for in-memory test doubles, same convention as [findPaged] itself. */
    fun countFiltered(
        statuses: Set<GenerationJobStatus> = emptySet(),
        recipientId: Long? = null,
        createdFrom: OffsetDateTime? = null,
        createdTo: OffsetDateTime? = null,
    ): Long = 0L

    /**
     * Per-day aggregate counts for the same filtered set [findPaged]/[countFiltered] page
     * through, newest day first. Grouped by the LOCAL calendar day in [zone] (default
     * [GermanTime.ZONE]) — the same day boundary the UI's own date-range filters already use
     * ([de.littletrotter.ui.view.invoice.PrintJobListView.currentFilter]), so a job created at
     * 23:30 and one at 00:30 the same Berlin evening land in the same day row even though their
     * `createdAt` UTC instants differ in calendar date.
     *
     * Default `emptyList()` — matching [findPaged]'s own "nothing to do" default — for in-memory
     * test doubles that don't care about the day-grouped view;
     * [de.littletrotter.infrastructure.persistence.PanacheGenerationJobRepository] overrides it
     * with the real grouped query.
     */
    fun findDaySummaries(
        statuses: Set<GenerationJobStatus> = emptySet(),
        recipientId: Long? = null,
        createdFrom: OffsetDateTime? = null,
        createdTo: OffsetDateTime? = null,
        zone: ZoneId = GermanTime.ZONE,
    ): List<GenerationJobDaySummary> = emptyList()

    /**
     * The one grouped, single-round-trip aggregate the NATS panel's pipeline row reads:
     * how many jobs are open (not yet finished), finished (in the last 24h / 5min) or failed, plus
     * the oldest still-open job's [GenerationJob.createdAt]. [completedSince24h]/
     * [completedSince5m] are computed by the caller from an injectable clock, not `now()` here, so
     * the throughput figure stays unit-testable.
     *
     * Default [GenerationJobPipelineCounts] (all zero) — matching [findPaged]'s own "nothing to
     * do" default — for in-memory test doubles that don't care about this aggregate;
     * [de.littletrotter.infrastructure.persistence.PanacheGenerationJobRepository] overrides it
     * with the real grouped query.
     */
    fun pipelineCounts(completedSince24h: OffsetDateTime, completedSince5m: OffsetDateTime): GenerationJobPipelineCounts =
        GenerationJobPipelineCounts()
}

/**
 * One calendar day's aggregate for the day-grouped "Druckaufträge" TreeGrid: how many jobs
 * were created that day (matching whatever filter [GenerationJobRepository.findDaySummaries] was
 * called with) and how many of them ended FAILED. [day] is the LOCAL calendar date in the zone
 * that grouping used (default [GermanTime.ZONE]) — not a UTC date, since two jobs created a few
 * hours apart around midnight UTC must still land in the same row a German user expects to see as
 * "today".
 */
data class GenerationJobDaySummary(
    val day: LocalDate,
    val jobCount: Long,
    val failedCount: Long,
)

/**
 * [GenerationJobRepository.pipelineCounts]'s own read model: DB-authoritative counts for
 * the NATS panel's pipeline row. [openCount]/[oldestOpenCreatedAt] cover every non-terminal
 * [GenerationJobStatus] (`PENDING`, `DELEGATED`, `RUNNING`) — the database has no signal for
 * whether a worker has actually claimed an open job, only JetStream's own consumer state does.
 */
data class GenerationJobPipelineCounts(
    val openCount: Long = 0,
    val oldestOpenCreatedAt: OffsetDateTime? = null,
    val completedLast24h: Long = 0,
    val completedLast5m: Long = 0,
    val failedCount: Long = 0,
)

/**
 * One stale-DELEGATED row [GenerationJobRepository.findStaleDelegatedCandidates] found:
 * just enough to decide whether to reset it
 * ([de.littletrotter.application.invoice.StaleDelegatedResetDecider]), never the whole entity —
 * the decision never needs anything else, and the candidate list can cross an off-transaction NATS
 * round trip in [de.littletrotter.application.invoice.StaleDelegatedResetCoordinator] without
 * holding any loaded entity/session open for it.
 */
data class StaleDelegatedCandidate(
    val id: Long,
    val delegatedStreamSeq: Long?,
    val lane: DocumentJobLane,
)
