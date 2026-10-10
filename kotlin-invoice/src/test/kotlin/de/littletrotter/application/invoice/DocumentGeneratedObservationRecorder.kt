package de.littletrotter.application.invoice

import de.littletrotter.domain.event.DocumentGenerated
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.ObservesAsync
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * fix-einvoice-status-stale-after-completion — CDI test observer for [GenerationJobServiceIT]'s
 * own regression test for the race that fix closes. A real, `@ObservesAsync` listener (same
 * dispatch style as [DocumentGeneratedReactionHandler], the UI push handler, and
 * `MessageCenterEventHandler`) that reads the `generation_job` row's own [GenerationJobStatus] via
 * a fresh [EntityManager.find] the moment it observes [DocumentGenerated] — the exact "re-read
 * committed state" step every production observer performs. Before the fix,
 * `GenerationJobService.markCompleted` fired this event from inside its own still-open
 * transaction, so a real async observer's fresh read (a separate transaction, separate
 * persistence context) could see the pre-commit row under Postgres' READ COMMITTED isolation — an
 * observed status still DELEGATED instead of COMPLETED. `fireAsyncAfterCommit` closes that gap by
 * deferring the fire itself until the firing transaction has committed.
 *
 * Top-level class (not nested in the test class) — CDI beans need a zero-arg constructor a nested
 * class doesn't expose, same reason [de.littletrotter.domain.invoice.InvoiceOptimisticLockTestHelper]'s
 * own KDoc gives. [arm] must be called before triggering the event under test: the observer
 * dispatches on the CDI async executor and could in principle run before the calling test thread
 * reaches its own `await` call.
 */
@ApplicationScoped
class DocumentGeneratedObservationRecorder {

    @Inject
    lateinit var em: EntityManager

    private val latches = ConcurrentHashMap<Long, CountDownLatch>()
    private val observedStatuses = ConcurrentHashMap<Long, GenerationJobStatus>()
    // L24 (fix-l24-draft-job-status-never-pushed) — records the event's own `issued` field
    // alongside the re-read status, so GenerationJobServiceIT can prove against a REAL Postgres
    // transaction/CDI async dispatch (not just a mocked Event<DomainEvent>) that this observer
    // — standing in for GenerationJobUiPushHandler's real dispatch style — now runs for a DRAFT
    // invoice's job too, which the pre-L24 emission guard made structurally impossible.
    private val observedIssued = ConcurrentHashMap<Long, Boolean>()

    /** Call before the action expected to fire [DocumentGenerated] for [jobId]. */
    fun arm(jobId: Long) {
        latches[jobId] = CountDownLatch(1)
        observedStatuses.remove(jobId)
        observedIssued.remove(jobId)
    }

    /** Blocks until the observer below has run for [jobId], or [timeoutMs] elapses. */
    fun await(jobId: Long, timeoutMs: Long = 5_000): Boolean =
        (latches[jobId] ?: error("arm(jobId) was not called for $jobId")).await(timeoutMs, TimeUnit.MILLISECONDS)

    /** The [GenerationJobStatus] this observer saw for [jobId] the moment it ran. */
    fun statusObservedFor(jobId: Long): GenerationJobStatus? = observedStatuses[jobId]

    /** L24 — the [DocumentGenerated.issued] value this observer saw for [jobId]. */
    fun issuedObservedFor(jobId: Long): Boolean? = observedIssued[jobId]

    @Transactional
    fun onDocumentGenerated(@ObservesAsync event: DocumentGenerated) {
        // Null-safe: this observer also runs for every OTHER test's markCompleted in the suite,
        // where the row may already be gone again by the time this async dispatch runs.
        val job = em.find(GenerationJob::class.java, event.generationJobId) ?: return
        observedStatuses[event.generationJobId] = job.status
        observedIssued[event.generationJobId] = event.issued
        latches[event.generationJobId]?.countDown()
    }
}
