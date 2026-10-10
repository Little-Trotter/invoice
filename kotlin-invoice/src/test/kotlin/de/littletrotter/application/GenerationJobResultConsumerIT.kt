package de.littletrotter.application

import de.littletrotter.application.invoice.GenerationJobEventDispatcher
import de.littletrotter.application.invoice.GenerationJobLlmResultHandler
import de.littletrotter.application.invoice.GenerationJobQueryService
import de.littletrotter.application.invoice.GenerationJobRecoveryService
import de.littletrotter.application.invoice.GenerationJobResultHandler
import de.littletrotter.application.invoice.NoOpInvoiceRepository
import de.littletrotter.application.invoice.NoOpLlmAnalysisRepository
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.infrastructure.messaging.GenerationJobResultConsumer
import de.littletrotter.test.NatsTestContainersExtension
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.mock
import jakarta.enterprise.event.Event
import de.littletrotter.domain.event.DomainEvent
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * Integration test (Testcontainers) verifying that a NATS-produced job-result payload
 * can be consumed and processed by GenerationJobResultConsumer via a simple adapter loop.
 *
 * MVP-focused: test uses an in-test core-NATS subscription (dispatch+delegate) instead of
 * wiring the full JetStream durable-consumer adapter.
 */
@ExtendWith(NatsTestContainersExtension::class)
class GenerationJobResultConsumerIT {

    /** `GenerationJobService` no longer exists: wires the real collaborators it used to build
     *  internally, all sharing the same [repository]/[domainEventBus]. */
    private fun consumerFor(
        repository: GenerationJobRepository,
        domainEventBus: Event<DomainEvent> = mock(),
    ): GenerationJobResultConsumer {
        val queryService = GenerationJobQueryService(repository)
        val eventDispatcher = GenerationJobEventDispatcher(domainEventBus, null)
        val resultHandler = GenerationJobResultHandler(repository, eventDispatcher, time = FIXED_TIME_SOURCE)
        val llmResultHandler = GenerationJobLlmResultHandler(repository, NoOpLlmAnalysisRepository, NoOpInvoiceRepository, eventDispatcher, time = FIXED_TIME_SOURCE)
        val recoveryService = GenerationJobRecoveryService(repository, NoOpInvoiceRepository, eventDispatcher, llmResultHandler, time = FIXED_TIME_SOURCE)
        return GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService)
    }

    @Test
    fun `consume completed job result from nats`() {
        val subject = "document-job-results-${UUID.randomUUID()}"
        val connection = NatsTestContainersExtension.connection

        // Prepare a fake repository/service that records saved job status
        // A result can only legally complete a DELEGATED/RUNNING job.
        val job = de.littletrotter.domain.invoice.GenerationJob().apply {
            id = 42L
            status = de.littletrotter.domain.invoice.GenerationJobStatus.DELEGATED
        }
        class FakeRepo : de.littletrotter.domain.repository.GenerationJobRepository {
            var lastSaved: de.littletrotter.domain.invoice.GenerationJob? = null
            override fun findByInvoiceId(invoiceId: Long) = emptyList<de.littletrotter.domain.invoice.GenerationJob>()
            override fun findByInvoiceIdWithTaxRates(invoiceId: Long) = emptyList<de.littletrotter.domain.invoice.GenerationJob>()
            override fun findPending() = emptyList<de.littletrotter.domain.invoice.GenerationJob>()
            override fun findPendingWithInvoice() = emptyList<de.littletrotter.domain.invoice.GenerationJob>()
            override fun findById(id: Long) = if (id == 42L) job else null
            override fun tryMarkDelegated(id: Long): Boolean = true
            override fun save(job: de.littletrotter.domain.invoice.GenerationJob): de.littletrotter.domain.invoice.GenerationJob {
                lastSaved = job
                return job
            }
        }
        val repo = FakeRepo()
        val consumerUnderTest = consumerFor(repo)

        // Latch to wait for processing
        val latch = CountDownLatch(1)

        connection.createDispatcher { msg ->
            consumerUnderTest.consume(String(msg.data, Charsets.UTF_8))
            latch.countDown()
        }.subscribe(subject)
        connection.flush(Duration.ofSeconds(2))

        // Produce a sample completed payload
        val payload = "{\"id\":\"42\",\"status\":\"COMPLETED\",\"resultRefs\":{\"pdf\":\"s3://bucket/42.pdf\"}}"
        connection.publish(subject, payload.toByteArray(Charsets.UTF_8))

        // wait for processing
        val processed = latch.await(10, TimeUnit.SECONDS)
        assertTrue(processed, "Message was not processed in time")

        // Verify repository saved job as COMPLETED
        val saved = repo.lastSaved
        assertTrue(saved != null, "No save occurred on repository")
        assertEquals(de.littletrotter.domain.invoice.GenerationJobStatus.COMPLETED, saved!!.status)
    }

    @Test
    fun `idempotency - processing same result twice is safe`() {
        val subject = "document-job-idempotency-${UUID.randomUUID()}"
        val connection = NatsTestContainersExtension.connection

        // DELEGATED, not PENDING: a result can only legally complete a DELEGATED/RUNNING
        // job (see the previous test's own comment).
        val existingJob = de.littletrotter.domain.invoice.GenerationJob().apply { id = 100L; status = de.littletrotter.domain.invoice.GenerationJobStatus.DELEGATED }
        var saveCount = 0
        class FakeRepo : de.littletrotter.domain.repository.GenerationJobRepository {
            override fun findByInvoiceId(invoiceId: Long) = emptyList<de.littletrotter.domain.invoice.GenerationJob>()
            override fun findByInvoiceIdWithTaxRates(invoiceId: Long) = emptyList<de.littletrotter.domain.invoice.GenerationJob>()
            override fun findPending() = emptyList<de.littletrotter.domain.invoice.GenerationJob>()
            override fun findPendingWithInvoice() = emptyList<de.littletrotter.domain.invoice.GenerationJob>()
            override fun findById(id: Long) = if (id == 100L) existingJob else null
            override fun tryMarkDelegated(id: Long): Boolean = true
            override fun save(job: de.littletrotter.domain.invoice.GenerationJob): de.littletrotter.domain.invoice.GenerationJob {
                saveCount++
                existingJob.status = job.status
                return job
            }
        }
        val repo = FakeRepo()
        val consumerUnderTest = consumerFor(repo)

        val latch = CountDownLatch(2) // We expect 2 messages
        connection.createDispatcher { msg ->
            consumerUnderTest.consume(String(msg.data, Charsets.UTF_8))
            latch.countDown()
        }.subscribe(subject)
        connection.flush(Duration.ofSeconds(2))

        val payload = "{\"id\":\"100\",\"status\":\"COMPLETED\",\"resultRefs\":{\"pdf\":\"s3://bucket/100.pdf\"}}"
        // Send twice
        connection.publish(subject, payload.toByteArray(Charsets.UTF_8))
        connection.publish(subject, payload.toByteArray(Charsets.UTF_8))

        val processed = latch.await(15, TimeUnit.SECONDS)
        assertTrue(processed, "Both messages should have been processed")
        assertEquals(de.littletrotter.domain.invoice.GenerationJobStatus.COMPLETED, existingJob.status)
        // Genuinely idempotent now, not just "doesn't crash": the first
        // COMPLETED delivery transitions the job DELEGATED->COMPLETED and saves once; the second
        // delivery finds the job already terminal (job.status == COMPLETED) and is a no-op —
        // acked, logged, never a second write. saveCount therefore stays at exactly 1.
        assertEquals(1, saveCount, "a re-delivered result for an already-terminal job must not write again")
    }

    // ── L3 (fix-generation-job-failure-visibility.md) — the DLQ path persists a terminal,
    // user-visible FAILED state instead of leaving the job DELEGATED forever. ────────────────────

    @Test
    fun `DLQ give-up after 3 failed processing attempts persists a terminal FAILED status`() {
        val subject = "document-job-dlq-${UUID.randomUUID()}"
        val connection = NatsTestContainersExtension.connection

        // Simulates the observed symptom: a worker result arrives for a DELEGATED job, but this
        // app's own processing of it keeps failing (e.g. the L2 stale-version write-back bug)
        // until the consumer gives up and simulate-DLQs the message. Before this fix the job
        // stayed DELEGATED ("Weitergeleitet" in the UI) forever; it must now end up FAILED with
        // the give-up reason recorded on last_error.
        val job = de.littletrotter.domain.invoice.GenerationJob().apply {
            id = 950L
            status = de.littletrotter.domain.invoice.GenerationJobStatus.DELEGATED
        }
        class FakeRepo : de.littletrotter.domain.repository.GenerationJobRepository {
            var saveCount = 0
            override fun findByInvoiceId(invoiceId: Long) = emptyList<de.littletrotter.domain.invoice.GenerationJob>()
            override fun findByInvoiceIdWithTaxRates(invoiceId: Long) = emptyList<de.littletrotter.domain.invoice.GenerationJob>()
            override fun findPending() = emptyList<de.littletrotter.domain.invoice.GenerationJob>()
            override fun findPendingWithInvoice() = emptyList<de.littletrotter.domain.invoice.GenerationJob>()
            override fun findById(id: Long) = if (id == 950L) job else null
            override fun tryMarkDelegated(id: Long): Boolean = true
            override fun save(job: de.littletrotter.domain.invoice.GenerationJob): de.littletrotter.domain.invoice.GenerationJob {
                saveCount++
                return job
            }
        }
        val repo = FakeRepo()
        var processingAttempts = 0
        val queryService = GenerationJobQueryService(repo)
        val resultHandler = object : GenerationJobResultHandler(repo, GenerationJobEventDispatcher(mock(), null), time = FIXED_TIME_SOURCE) {
            override fun markCompleted(
                job: de.littletrotter.domain.invoice.GenerationJob,
                resultRefs: String?,
                kositReport: String?,
                llmStatus: String?,
            ): de.littletrotter.domain.invoice.GenerationJob {
                processingAttempts++
                throw RuntimeException("simulated repeated processing failure (e.g. L2 stale-version write-back)")
            }
        }
        val eventDispatcher = GenerationJobEventDispatcher(mock(), null)
        val llmResultHandler = GenerationJobLlmResultHandler(repo, NoOpLlmAnalysisRepository, NoOpInvoiceRepository, eventDispatcher, time = FIXED_TIME_SOURCE)
        val recoveryService = GenerationJobRecoveryService(repo, NoOpInvoiceRepository, eventDispatcher, llmResultHandler, time = FIXED_TIME_SOURCE)
        val consumerUnderTest = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService)

        val latch = CountDownLatch(1)
        connection.createDispatcher { msg ->
            consumerUnderTest.consume(String(msg.data, Charsets.UTF_8))
            latch.countDown()
        }.subscribe(subject)
        connection.flush(Duration.ofSeconds(2))

        val payload = "{\"id\":\"950\",\"status\":\"COMPLETED\",\"resultRefs\":{\"pdf\":\"s3://bucket/950.pdf\"}}"
        connection.publish(subject, payload.toByteArray(Charsets.UTF_8))

        val processed = latch.await(15, TimeUnit.SECONDS)
        assertTrue(processed, "Message was not processed in time")

        assertEquals(3, processingAttempts, "must retry exactly 3 times before giving up (DLQ, simulated)")
        assertEquals(
            de.littletrotter.domain.invoice.GenerationJobStatus.FAILED, job.status,
            "the job must end up terminally FAILED, not stuck DELEGATED forever",
        )
        assertTrue(job.lastError?.contains("3 attempts") == true, "lastError should record why: ${job.lastError}")
        assertTrue(repo.saveCount > 0, "the terminal FAILED transition must have been persisted")
    }
}
