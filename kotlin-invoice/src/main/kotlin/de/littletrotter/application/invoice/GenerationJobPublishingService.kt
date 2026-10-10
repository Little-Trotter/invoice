package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.shared.time.TimeSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional

/**
 * GenerationJobService's publish/delegate state-transition responsibility, extracted
 * verbatim.
 */
@ApplicationScoped
class GenerationJobPublishingService @Inject constructor(
    private val generationJobRepository: GenerationJobRepository,
    private val time: TimeSource,
) {
    @Transactional
    fun markDelegated(job: GenerationJob): GenerationJob {
        job.transitionTo(GenerationJobStatus.DELEGATED)
        // The candidate clock for the stale-DELEGATED reset; see
        // GenerationJob.delegatedAt's own KDoc. Mirrors PanacheGenerationJobRepository
        // .tryMarkDelegated's own bulk UPDATE, which stamps it in the same statement for the real
        // (fast-path/poller) publish path — this entity-level transition is the test-fixture path.
        job.delegatedAt = time.nowUtc()
        return generationJobRepository.save(job)
    }

    /**
     * Atomically transitions the job from PENDING to DELEGATED.
     * Returns false (and does nothing) if another thread already changed the status,
     * preventing double-publication to NATS.
     */
    @Transactional
    fun tryMarkDelegated(job: GenerationJob): Boolean {
        val id = job.id ?: return false
        return generationJobRepository.tryMarkDelegated(id)
    }

    /**
     * Reverts a job the fast path just claimed ([tryMarkDelegated]) back to PENDING after
     * its immediate publish attempt (or recording its ack, see [recordDelegatedStreamSeq]) failed
     * (e.g. NATS unreachable), so it becomes visible to `findPendingWithInvoice` again on the very
     * next [GenerationJobProcessor.pollPendingJobs] tick instead of waiting for the stale-DELEGATED
     * reset's own cutoff.
     */
    @Transactional
    fun revertToPending(job: GenerationJob): Boolean {
        val id = job.id ?: return false
        return generationJobRepository.revertToPending(id)
    }

    /**
     * Records the JetStream stream sequence of this job's own `document-jobs` publish
     * ([io.nats.client.api.PublishAck.seqno]), called synchronously by the same caller that just
     * claimed the `PENDING -> DELEGATED` transition ([tryMarkDelegated]) and is still blocked on
     * the publish ack. See [GenerationJobRepository.recordDelegatedStreamSeq]'s own KDoc for the
     * guard.
     */
    @Transactional
    fun recordDelegatedStreamSeq(job: GenerationJob, streamSeq: Long): Boolean {
        val id = job.id ?: return false
        return generationJobRepository.recordDelegatedStreamSeq(id, streamSeq)
    }

    /**
     * Reopens every attempt-budget-exhausted `FAILED` job whose invoice still has no
     * document ([GenerationJobRepository.findExhaustedJobsWithoutDocument]'s own narrow condition).
     * Called from [GenerationJobProcessor.scanPendingJobs] on every tick, unconditionally. Each
     * reopened job gets a full fresh attempt budget (`attempts` reset to `0` by
     * [GenerationJobRepository.reopenExhausted]).
     */
    @Transactional
    fun reopenExhaustedJobsWithoutDocument(): Int {
        var reopened = 0
        generationJobRepository.findExhaustedJobsWithoutDocument().forEach { job ->
            val id = job.id ?: return@forEach
            if (generationJobRepository.reopenExhausted(id)) reopened++
        }
        return reopened
    }

    @Transactional
    fun markStarted(job: GenerationJob): GenerationJob {
        job.startedAt = time.nowUtc()
        job.transitionTo(GenerationJobStatus.RUNNING)
        return generationJobRepository.save(job)
    }
}
