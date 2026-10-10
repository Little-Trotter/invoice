package de.littletrotter.domain.invoice

/**
 * The single place in the domain that knows the legal [GenerationJob] lifecycle edges.
 * Before this the status was a plain field, written directly from four different call sites
 * ([de.littletrotter.application.invoice.GenerationJobService], [de.littletrotter.application
 * .invoice.GenerationJobProcessor], [de.littletrotter.infrastructure.persistence
 * .PanacheGenerationJobRepository]'s bulk JPQL updates) with no shared notion of which transitions
 * were even meant to be possible — the incident this task doc responds to (two jobs cycling
 * DELEGATED→PENDING→DELEGATED forever) was a symptom of that gap, not a one-off bug.
 *
 * Legal edges:
 * ```
 * PENDING   → DELEGATED             (published)
 * DELEGATED → PENDING               (stale-reset, counts attempt while a worker is subscribed)
 * DELEGATED → RUNNING               (worker starts)
 * DELEGATED → COMPLETED | FAILED    (result)
 * RUNNING   → COMPLETED | FAILED    (result)
 * PENDING   → FAILED                (attempt budget exhausted)
 * FAILED    → PENDING               (attempt-budget-exhaustion reopened once a worker is
 *                                     confirmed active again, invoice still has no document)
 * ```
 * L3 (fix-generation-job-failure-visibility.md) — the DELEGATED/RUNNING→FAILED "(result)" edges
 * above also cover [de.littletrotter.application.invoice.GenerationJobService
 * .markResultProcessingFailed]'s DLQ give-up transition: a worker result DID arrive for that job
 * (that is exactly why [de.littletrotter.infrastructure.messaging.GenerationJobResultConsumer] was
 * processing it in the first place), only this application's own persistence of it never
 * completed after 3 attempts. No new edge was needed for that fix.
 * [GenerationJobStatus.COMPLETED] is terminal — no outgoing edge at all. [GenerationJobStatus
 * .FAILED] is terminal for every ordinary outcome (the worker's own verdict, retry exhaustion, a
 * DLQ give-up) EXCEPT the one edge above: a job that only ever failed because its
 * attempt-budget was burned by stale resets while no worker was subscribed (never because of a
 * real, reported failure) can be reopened once a worker is confirmed active again, so no invoice is
 * left permanently without a document purely because of a worker outage. That write is, like the
 * bulk-JPQL methods described below, a guarded bulk `UPDATE`
 * ([de.littletrotter.infrastructure.persistence.PanacheGenerationJobRepository.reopenExhausted])
 * gated on its own narrow condition (the exact `lastError` marker
 * [GenerationJob.ATTEMPT_BUDGET_EXHAUSTED_REASON_PREFIX] plus "no other job for this invoice has
 * completed"), never a general-purpose "retry any FAILED job" capability. The deferred LLM callback
 * (`GenerationJobService.mergeLlmResult`)
 * deliberately never calls [assertLegal]/[GenerationJob.transitionTo] — it only fills
 * [GenerationJob.llmStatus] and merges result data, the job's own `status` is untouched by design
 * (that behavior predates and is out of scope for this change).
 *
 * Call sites that write status via a bulk JPQL `UPDATE ... WHERE status = :old` (`tryMarkDelegated`,
 * `revertToPending`, the stale-reset/exhaustion updates, and `failDlq` (L3) in
 * [de.littletrotter.infrastructure.persistence.PanacheGenerationJobRepository]) do not — and
 * structurally cannot — call through this object: there is no loaded [GenerationJob] instance at
 * that point, only an id. Those methods instead encode the exact same edge directly in their
 * `WHERE` clause; that clause **is** the guard for that one call site (documented on each method
 * there), verified against this same table.
 */
object GenerationJobStateMachine {

    private val legalEdges: Map<GenerationJobStatus, Set<GenerationJobStatus>> = mapOf(
        GenerationJobStatus.PENDING to setOf(GenerationJobStatus.DELEGATED, GenerationJobStatus.FAILED),
        GenerationJobStatus.DELEGATED to setOf(
            GenerationJobStatus.PENDING,
            GenerationJobStatus.RUNNING,
            GenerationJobStatus.COMPLETED,
            GenerationJobStatus.FAILED,
        ),
        GenerationJobStatus.RUNNING to setOf(GenerationJobStatus.COMPLETED, GenerationJobStatus.FAILED),
        GenerationJobStatus.COMPLETED to emptySet(),
        // The one narrow exception to "FAILED is terminal", see this object's own KDoc.
        GenerationJobStatus.FAILED to setOf(GenerationJobStatus.PENDING),
    )

    /** `true` when [from] → [to] is a legal edge in the table above (`from == to` is never legal —
     *  there is no self-edge; a caller that means "already there, nothing to do" checks that
     *  itself, as e.g. the idempotent result-consumer path does). */
    fun isLegal(from: GenerationJobStatus, to: GenerationJobStatus): Boolean =
        to in legalEdges.getValue(from)

    /** Throws [IllegalStateException] with a precise message when [from] → [to] is not a legal
     *  edge; a no-op otherwise. An illegal transition is a programming error, not a recoverable
     *  runtime condition — callers are expected to let this propagate, not catch it. */
    fun assertLegal(from: GenerationJobStatus, to: GenerationJobStatus) {
        check(isLegal(from, to)) { "Illegal GenerationJob status transition: $from -> $to" }
    }
}
