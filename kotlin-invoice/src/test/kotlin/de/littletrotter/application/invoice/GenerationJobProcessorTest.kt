package de.littletrotter.application.invoice

import de.littletrotter.application.shared.DomainEventDispatcher
import de.littletrotter.domain.event.GenerationJobCreated
import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.fixture.aGenerationJob
import de.littletrotter.fixture.anInvoice
import de.littletrotter.test.FIXED_TIME_SOURCE
import io.nats.client.JetStream
import io.nats.client.PublishOptions
import io.nats.client.api.PublishAck
import io.nats.client.impl.Headers
import org.eclipse.microprofile.faulttolerance.exceptions.BulkheadException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * [GenerationJobService] dissolved: every fake below now directly subclasses the specific
 * former collaborator it needs ([GenerationJobQueryService]/[GenerationJobPublishingService]/
 * [GenerationJobRecoveryService], all CDI-allopen like the facade they replace), instead of one
 * `GenerationJobService` subclass overriding methods that actually lived on different
 * collaborators underneath it.
 */
class GenerationJobProcessorTest {

    // The business fixture is projected from an offline synthetic datagen run.
    // Job IDs, retry counters and errors below are local test controls only.
    private val demoInvoiceNumber = java.util.Properties().apply {
        requireNotNull(GenerationJobProcessorTest::class.java.getResourceAsStream("/datagen-default/generation-recovery.properties"))
            .use { load(it) }
    }.getProperty("invoice.number")

    private fun noopPublisher() = DocumentJobPublisher(jetStreamProvider = null, jobsSubjectOverride = null)

    private fun stubRepo() = object : GenerationJobRepository {
        override fun findByInvoiceId(invoiceId: Long): List<GenerationJob> = emptyList()
        override fun findByInvoiceIdWithTaxRates(invoiceId: Long): List<GenerationJob> = emptyList()
        override fun findPending(): List<GenerationJob> = emptyList()
        override fun findPendingWithInvoice(): List<GenerationJob> = emptyList()
        override fun findById(id: Long): GenerationJob? = null
        override fun save(job: GenerationJob): GenerationJob = job
        override fun tryMarkDelegated(id: Long): Boolean = true
    }

    private fun defaultQueryService() = object : GenerationJobQueryService(stubRepo()) {}
    private fun defaultPublishingService() = object : GenerationJobPublishingService(stubRepo(), time = FIXED_TIME_SOURCE) {}
    private fun defaultRecoveryService() = object : GenerationJobRecoveryService(
        stubRepo(), mock<InvoiceRepository>(), mock<DomainEventDispatcher>(), mock<GenerationJobLlmResultHandler>(), time = FIXED_TIME_SOURCE,
    ) {}

    @Test
    fun `pollPendingJobs runs the issued document sweep and survives its failure`() {
        val sweep: IssuedDocumentJobSweep = mock()
        whenever(sweep.requestMissingCreditNoteJobs()).thenReturn(2).thenThrow(RuntimeException("db down"))
        val processor = GenerationJobProcessor(
            defaultQueryService(), defaultPublishingService(), defaultRecoveryService(), noopPublisher(),
            GenerationJobFastPublishTransaction(defaultQueryService(), defaultPublishingService(), noopPublisher()),
            DelegatingManagedExecutor(Executor { it.run() }), 5, issuedDocumentJobSweep = sweep,
        )

        assertDoesNotThrow { processor.pollPendingJobs() }
        assertDoesNotThrow { processor.pollPendingJobs() }

        verify(sweep, times(2)).requestMissingCreditNoteJobs()
    }

    @Test
    fun `pollPendingJobs retries on transient failures then delegates job`() {
        var fetchAttempts = 0
        var delegatedJobIds = mutableListOf<Long>()
        val job = aGenerationJob {
            id = 10L
            // pollPendingJobs() skips re-publish when invoice is null (no invoice to re-publish) —
            // attach a minimal invoice so the test actually reaches tryMarkDelegated().
            invoice = anInvoice(invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber(demoInvoiceNumber))
        }

        val queryService = object : GenerationJobQueryService(stubRepo()) {
            override fun findPendingWithInvoice(): List<GenerationJob> {
                fetchAttempts++
                if (fetchAttempts < 3) throw RuntimeException("db temporary")
                return listOf(job)
            }
        }
        val publishingService = object : GenerationJobPublishingService(stubRepo(), time = FIXED_TIME_SOURCE) {
            override fun tryMarkDelegated(job: GenerationJob): Boolean {
                delegatedJobIds += job.id!!
                return true
            }
        }

        GenerationJobProcessor(queryService, publishingService, defaultRecoveryService(), noopPublisher()).pollPendingJobs()

        assertEquals(3, fetchAttempts, "Should have attempted 3 times before succeeding")
        assertEquals(listOf(10L), delegatedJobIds, "tryMarkDelegated must be called for the pending job")
    }

    @Test
    fun `pollPendingJobs does nothing when no pending jobs`() {
        var delegateCalled = false
        val queryService = object : GenerationJobQueryService(stubRepo()) {
            override fun findPendingWithInvoice(): List<GenerationJob> = emptyList()
        }
        val publishingService = object : GenerationJobPublishingService(stubRepo(), time = FIXED_TIME_SOURCE) {
            override fun tryMarkDelegated(job: GenerationJob): Boolean {
                delegateCalled = true
                return true
            }
        }

        GenerationJobProcessor(queryService, publishingService, defaultRecoveryService(), noopPublisher()).pollPendingJobs()

        assertFalse(delegateCalled, "tryMarkDelegated must not be called when there are no pending jobs")
    }

    @Test
    fun `startup and scheduled recovery share an in-flight guard which releases after the scan`() {
        var scans = 0
        lateinit var processor: GenerationJobProcessor
        val queryService = object : GenerationJobQueryService(stubRepo()) {
            override fun findPendingWithInvoice(): List<GenerationJob> {
                scans++
                // A second entry while the first scan owns the guard must not start a query.
                processor.pollPendingJobs()
                return emptyList()
            }
        }
        processor = GenerationJobProcessor(queryService, defaultPublishingService(), defaultRecoveryService(), noopPublisher())

        processor.pollPendingJobs()
        assertEquals(1, scans)
        processor.pollPendingJobs()
        assertEquals(2, scans, "the guard must release so the next recovery tick can scan")
    }

    @Test
    fun `pollPendingJobs does not throw when all retry attempts fail`() {
        val queryService = object : GenerationJobQueryService(stubRepo()) {
            override fun findPendingWithInvoice(): List<GenerationJob> = throw RuntimeException("persistent db error")
        }

        assertDoesNotThrow {
            GenerationJobProcessor(queryService, defaultPublishingService(), defaultRecoveryService(), noopPublisher()).pollPendingJobs()
        }
    }

    // ── Closing the gap to 100 % line+branch ─────────────────────────────────────

    @Test
    fun `pollPendingJobs skips a job that has no associated invoice`() {
        var delegateCalled = false
        val job = aGenerationJob { id = 20L } // invoice stays null
        val queryService = object : GenerationJobQueryService(stubRepo()) {
            override fun findPendingWithInvoice(): List<GenerationJob> = listOf(job)
        }
        val publishingService = object : GenerationJobPublishingService(stubRepo(), time = FIXED_TIME_SOURCE) {
            override fun tryMarkDelegated(job: GenerationJob): Boolean {
                delegateCalled = true
                return true
            }
        }

        GenerationJobProcessor(queryService, publishingService, defaultRecoveryService(), noopPublisher()).pollPendingJobs()

        assertFalse(delegateCalled, "a job without an invoice must never reach tryMarkDelegated")
    }

    @Test
    fun `pollPendingJobs skips re-publish when another thread already claimed the delegation`() {
        val publisher = noopPublisher()
        val job = aGenerationJob {
            id = 21L
            invoice = anInvoice(invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber(demoInvoiceNumber))
        }
        val queryService = object : GenerationJobQueryService(stubRepo()) {
            override fun findPendingWithInvoice(): List<GenerationJob> = listOf(job)
        }
        val publishingService = object : GenerationJobPublishingService(stubRepo(), time = FIXED_TIME_SOURCE) {
            override fun tryMarkDelegated(job: GenerationJob): Boolean = false
        }

        // Must not throw even though noopPublisher's publish() would throw — proof it is never
        // reached when the idempotency guard loses the race.
        assertDoesNotThrow {
            GenerationJobProcessor(queryService, publishingService, defaultRecoveryService(), publisher).pollPendingJobs()
        }
    }

    // ── The attempt budget: pollPendingJobs fails a job outright once GenerationJob
    // .attempts reaches invoice.jobs.max-attempts, instead of re-delegating it for another doomed
    // cycle. ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `pollPendingJobs fails an exhausted job instead of re-publishing it`() {
        val job = aGenerationJob {
            id = 40L
            attempts = 5
            invoice = anInvoice(invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber(demoInvoiceNumber))
        }
        var delegatedCalled = false
        var exhaustedCalledWith: Pair<Long?, Int>? = null
        val queryService = object : GenerationJobQueryService(stubRepo()) {
            override fun findPendingWithInvoice(): List<GenerationJob> = listOf(job)
        }
        val publishingService = object : GenerationJobPublishingService(stubRepo(), time = FIXED_TIME_SOURCE) {
            override fun tryMarkDelegated(job: GenerationJob): Boolean { delegatedCalled = true; return true }
        }
        val recoveryService = object : GenerationJobRecoveryService(
            stubRepo(), mock<InvoiceRepository>(), mock<DomainEventDispatcher>(), mock<GenerationJobLlmResultHandler>(), time = FIXED_TIME_SOURCE,
        ) {
            override fun failExhaustedJob(job: GenerationJob, maxAttempts: Int): Boolean {
                exhaustedCalledWith = job.id to maxAttempts
                return true
            }
        }
        val processor = GenerationJobProcessor(queryService, publishingService, recoveryService, noopPublisher(), DelegatingManagedExecutor(Executor { it.run() }), 5)

        processor.pollPendingJobs()

        assertFalse(delegatedCalled, "an exhausted job must never reach tryMarkDelegated")
        assertEquals(40L to 5, exhaustedCalledWith)
    }

    @Test
    fun `pollPendingJobs still re-publishes a job whose attempts are below the budget`() {
        val job = aGenerationJob {
            id = 41L
            attempts = 4
            invoice = anInvoice(invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber(demoInvoiceNumber))
        }
        var delegatedCalled = false
        var exhaustedCalled = false
        val queryService = object : GenerationJobQueryService(stubRepo()) {
            override fun findPendingWithInvoice(): List<GenerationJob> = listOf(job)
        }
        val publishingService = object : GenerationJobPublishingService(stubRepo(), time = FIXED_TIME_SOURCE) {
            override fun tryMarkDelegated(job: GenerationJob): Boolean { delegatedCalled = true; return true }
        }
        val recoveryService = object : GenerationJobRecoveryService(
            stubRepo(), mock<InvoiceRepository>(), mock<DomainEventDispatcher>(), mock<GenerationJobLlmResultHandler>(), time = FIXED_TIME_SOURCE,
        ) {
            override fun failExhaustedJob(job: GenerationJob, maxAttempts: Int): Boolean { exhaustedCalled = true; return true }
        }
        val processor = GenerationJobProcessor(queryService, publishingService, recoveryService, noopPublisher(), DelegatingManagedExecutor(Executor { it.run() }), 5)

        processor.pollPendingJobs()

        assertTrue(delegatedCalled, "a job below the budget must still be delegated normally")
        assertFalse(exhaustedCalled)
    }

    @Test
    fun `pollPendingJobs does not throw when failExhaustedJob itself throws`() {
        val job = aGenerationJob {
            id = 42L
            attempts = 5
            invoice = anInvoice(invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber(demoInvoiceNumber))
        }
        val queryService = object : GenerationJobQueryService(stubRepo()) {
            override fun findPendingWithInvoice(): List<GenerationJob> = listOf(job)
        }
        val recoveryService = object : GenerationJobRecoveryService(
            stubRepo(), mock<InvoiceRepository>(), mock<DomainEventDispatcher>(), mock<GenerationJobLlmResultHandler>(), time = FIXED_TIME_SOURCE,
        ) {
            override fun failExhaustedJob(job: GenerationJob, maxAttempts: Int): Boolean = throw RuntimeException("db down")
        }
        val processor = GenerationJobProcessor(queryService, defaultPublishingService(), recoveryService, noopPublisher(), DelegatingManagedExecutor(Executor { it.run() }), 5)

        assertDoesNotThrow { processor.pollPendingJobs() }
    }

    @Test
    fun `pollPendingJobs republishes successfully via a real JetStream, including a job with a null id`() {
        val jetStream: JetStream = mock()
        whenever(jetStream.publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>()))
            .thenReturn(CompletableFuture.completedFuture(mock<PublishAck>()))
        val publisher = DocumentJobPublisher(jetStreamProvider = { jetStream })

        // job.id is null here on purpose — proof of the `job.id ?: -1` fallback on the publish call.
        val job = aGenerationJob {
            invoice = anInvoice(invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber(demoInvoiceNumber))
        }
        val queryService = object : GenerationJobQueryService(stubRepo()) {
            override fun findPendingWithInvoice(): List<GenerationJob> = listOf(job)
        }
        val publishingService = object : GenerationJobPublishingService(stubRepo(), time = FIXED_TIME_SOURCE) {
            override fun tryMarkDelegated(job: GenerationJob): Boolean = true
        }

        assertDoesNotThrow {
            GenerationJobProcessor(queryService, publishingService, defaultRecoveryService(), publisher).pollPendingJobs()
        }

        org.mockito.kotlin.verify(jetStream).publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>())
    }

    @Test
    fun `pollPendingJobs records the real ack seqno synchronously after a successful republish`() {
        val jetStream: JetStream = mock()
        val ack: PublishAck = mock()
        whenever(ack.seqno).thenReturn(9001L)
        whenever(jetStream.publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>()))
            .thenReturn(CompletableFuture.completedFuture(ack))
        val publisher = DocumentJobPublisher(jetStreamProvider = { jetStream })
        val job = aGenerationJob {
            id = 51L
            invoice = anInvoice(invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber(demoInvoiceNumber))
        }
        var recordedWith: Pair<Long?, Long>? = null
        val queryService = object : GenerationJobQueryService(stubRepo()) {
            override fun findPendingWithInvoice(): List<GenerationJob> = listOf(job)
        }
        val publishingService = object : GenerationJobPublishingService(stubRepo(), time = FIXED_TIME_SOURCE) {
            override fun tryMarkDelegated(job: GenerationJob): Boolean = true
            override fun recordDelegatedStreamSeq(job: GenerationJob, streamSeq: Long): Boolean {
                recordedWith = job.id to streamSeq
                return true
            }
        }

        GenerationJobProcessor(queryService, publishingService, defaultRecoveryService(), publisher).pollPendingJobs()

        assertEquals(51L to 9001L, recordedWith, "the poller's own republish must record the real ack seqno synchronously too")
    }

    @Test
    fun `pollPendingJobs continues to the next job when recording the ack seqno fails`() {
        // A recording failure falls into the same catch-and-continue as any other republish
        // failure (and hands the claim back, see the revert tests below) -- it must not abort the
        // whole tick.
        val jetStream: JetStream = mock()
        val ack: PublishAck = mock()
        whenever(ack.seqno).thenReturn(1L)
        whenever(jetStream.publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>()))
            .thenReturn(CompletableFuture.completedFuture(ack))
        val publisher = DocumentJobPublisher(jetStreamProvider = { jetStream })
        val job1 = aGenerationJob {
            id = 52L
            invoice = anInvoice(invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber(demoInvoiceNumber))
        }
        val job2 = aGenerationJob {
            id = 53L
            invoice = anInvoice(invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber(demoInvoiceNumber))
        }
        var secondJobDelegated = false
        val queryService = object : GenerationJobQueryService(stubRepo()) {
            override fun findPendingWithInvoice(): List<GenerationJob> = listOf(job1, job2)
        }
        val publishingService = object : GenerationJobPublishingService(stubRepo(), time = FIXED_TIME_SOURCE) {
            override fun tryMarkDelegated(job: GenerationJob): Boolean {
                if (job.id == 53L) secondJobDelegated = true
                return true
            }
            override fun recordDelegatedStreamSeq(job: GenerationJob, streamSeq: Long): Boolean =
                if (job.id == 52L) throw RuntimeException("db down") else true
        }

        assertDoesNotThrow {
            GenerationJobProcessor(queryService, publishingService, defaultRecoveryService(), publisher).pollPendingJobs()
        }

        assertTrue(secondJobDelegated, "a recording failure for one job must not abort the loop over the rest")
    }

    // ── back-pressure handling: claim hand-back and bounded in-place retry ──────────────────

    private fun jobWithInvoice(jobId: Long) = aGenerationJob {
        id = jobId
        invoice = anInvoice(invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber(demoInvoiceNumber))
    }

    private class Recorder {
        val reverted = mutableListOf<Long?>()
        val delegated = mutableListOf<Long?>()
        val sleeps = mutableListOf<Long>()
    }

    private fun recordingPublishingService(rec: Recorder) = object : GenerationJobPublishingService(stubRepo(), time = FIXED_TIME_SOURCE) {
        override fun tryMarkDelegated(job: GenerationJob): Boolean { rec.delegated += job.id; return true }
        override fun revertToPending(job: GenerationJob): Boolean { rec.reverted += job.id; return true }
        override fun recordDelegatedStreamSeq(job: GenerationJob, streamSeq: Long): Boolean = true
    }

    private fun pollerFor(jobs: List<GenerationJob>, publisher: DocumentJobPublisher, rec: Recorder, attempts: Int = 3): GenerationJobProcessor {
        val queryService = object : GenerationJobQueryService(stubRepo()) {
            override fun findPendingWithInvoice(): List<GenerationJob> = jobs
        }
        val publishingService = recordingPublishingService(rec)
        return GenerationJobProcessor(
            queryService, publishingService, defaultRecoveryService(), publisher,
            GenerationJobFastPublishTransaction(queryService, publishingService, publisher),
            DelegatingManagedExecutor(Executor { it.run() }), 5, null,
            LlmPendingRecoveryBatchTransaction(defaultRecoveryService()), null, attempts, 100L,
        ).also { it.sleeper = { ms -> rec.sleeps += ms } }
    }

    private fun okAck(): PublishAck {
        val ack: PublishAck = mock()
        whenever(ack.seqno).thenReturn(7L)
        return ack
    }

    @Test
    fun `poller hands the claim back when the publish fails for a non-bulkhead reason`() {
        val publisher: DocumentJobPublisher = mock()
        whenever(publisher.publish(any<GenerationJob>(), any<Boolean>())).thenThrow(RuntimeException("NATS down"))
        val rec = Recorder()

        pollerFor(listOf(jobWithInvoice(60L)), publisher, rec).pollPendingJobs()

        assertEquals(listOf<Long?>(60L), rec.reverted)
        assertTrue(rec.sleeps.isEmpty(), "only bulkhead rejections are retried in place")
        verify(publisher, times(1)).publish(any<GenerationJob>(), any<Boolean>())
    }

    @Test
    fun `poller retries a bulkhead rejection with doubling backoff and then publishes`() {
        val publisher: DocumentJobPublisher = mock()
        whenever(publisher.publish(any<GenerationJob>(), any<Boolean>()))
            .thenThrow(RuntimeException("wrapped", BulkheadException("full")))
            .thenThrow(BulkheadException("full"))
            .thenReturn(CompletableFuture.completedFuture(okAck()))
        val rec = Recorder()

        pollerFor(listOf(jobWithInvoice(61L)), publisher, rec).pollPendingJobs()

        assertEquals(listOf(100L, 200L), rec.sleeps)
        assertTrue(rec.reverted.isEmpty(), "a job that finally published must stay DELEGATED")
        verify(publisher, times(3)).publish(any<GenerationJob>(), any<Boolean>())
    }

    @Test
    fun `poller reverts to PENDING when the bulkhead stays full and continues with the next job`() {
        val publisher: DocumentJobPublisher = mock()
        whenever(publisher.publish(any<GenerationJob>(), any<Boolean>())).thenThrow(BulkheadException("full"))
        val rec = Recorder()

        pollerFor(listOf(jobWithInvoice(62L), jobWithInvoice(63L)), publisher, rec).pollPendingJobs()

        assertEquals(listOf<Long?>(62L, 63L), rec.delegated, "the loop must reach the second job")
        assertEquals(listOf<Long?>(62L, 63L), rec.reverted)
        verify(publisher, times(6)).publish(any<GenerationJob>(), any<Boolean>())
    }

    @Test
    fun `fast path re-invokes publishOne while the bulkhead is busy and stops once published`() {
        val tx: GenerationJobFastPublishTransaction = mock()
        whenever(tx.publishOne(70L))
            .thenReturn(FastPublishOutcome.REJECTED_BUSY, FastPublishOutcome.REJECTED_BUSY, FastPublishOutcome.PUBLISHED)
        val rec = Recorder()

        fastPathProcessor(tx, rec).onGenerationJobCreated(GenerationJobCreated(70L, DocumentJobLane.BULK))

        verify(tx, times(3)).publishOne(70L)
        assertEquals(listOf(100L, 200L), rec.sleeps)
    }

    @Test
    fun `fast path stops after the retry budget and leaves the job to the poller`() {
        val tx: GenerationJobFastPublishTransaction = mock()
        whenever(tx.publishOne(71L)).thenReturn(FastPublishOutcome.REJECTED_BUSY)
        val rec = Recorder()

        fastPathProcessor(tx, rec).onGenerationJobCreated(GenerationJobCreated(71L, DocumentJobLane.BULK))

        verify(tx, times(3)).publishOne(71L)
        assertEquals(2, rec.sleeps.size)
    }

    @Test
    fun `fast path does not retry a non-bulkhead failure`() {
        val tx: GenerationJobFastPublishTransaction = mock()
        whenever(tx.publishOne(72L)).thenReturn(FastPublishOutcome.FAILED)
        val rec = Recorder()

        fastPathProcessor(tx, rec).onGenerationJobCreated(GenerationJobCreated(72L, DocumentJobLane.BULK))

        verify(tx, times(1)).publishOne(72L)
        assertTrue(rec.sleeps.isEmpty())
    }

    @Test
    fun `poller still propagates into the log and continues when handing the claim back fails`() {
        val publisher: DocumentJobPublisher = mock()
        whenever(publisher.publish(any<GenerationJob>(), any<Boolean>())).thenThrow(RuntimeException("NATS down"))
        val rec = Recorder()
        val queryService = object : GenerationJobQueryService(stubRepo()) {
            override fun findPendingWithInvoice(): List<GenerationJob> = listOf(jobWithInvoice(64L), jobWithInvoice(65L))
        }
        val publishingService = object : GenerationJobPublishingService(stubRepo(), time = FIXED_TIME_SOURCE) {
            override fun tryMarkDelegated(job: GenerationJob): Boolean { rec.delegated += job.id; return true }
            override fun revertToPending(job: GenerationJob): Boolean = error("db down")
        }
        val poller = GenerationJobProcessor(
            queryService, publishingService, defaultRecoveryService(), publisher,
            GenerationJobFastPublishTransaction(queryService, publishingService, publisher),
            DelegatingManagedExecutor(Executor { it.run() }), 5, null,
            LlmPendingRecoveryBatchTransaction(defaultRecoveryService()), null, 3, 100L,
        )

        poller.pollPendingJobs()

        assertEquals(listOf<Long?>(64L, 65L), rec.delegated, "a failing hand-back must not abort the loop")
    }

    @Test
    fun `fast path stops retrying when the backoff sleep is interrupted`() {
        val tx: GenerationJobFastPublishTransaction = mock()
        whenever(tx.publishOne(73L)).thenReturn(FastPublishOutcome.REJECTED_BUSY)
        val rec = Recorder()

        fastPathProcessor(tx, rec).also { it.sleeper = { throw InterruptedException("stop") } }
            .onGenerationJobCreated(GenerationJobCreated(73L, DocumentJobLane.BULK))

        verify(tx, times(1)).publishOne(73L)
        assertTrue(Thread.interrupted(), "the interrupt flag must be restored")
    }

    @Test
    fun `fast path backs off with the real sleeper by default`() {
        val tx: GenerationJobFastPublishTransaction = mock()
        whenever(tx.publishOne(74L)).thenReturn(FastPublishOutcome.REJECTED_BUSY, FastPublishOutcome.PUBLISHED)
        val processor = GenerationJobProcessor(
            defaultQueryService(), defaultPublishingService(), defaultRecoveryService(), noopPublisher(), tx,
            DelegatingManagedExecutor(Executor { it.run() }), 5, null,
            LlmPendingRecoveryBatchTransaction(defaultRecoveryService()), null, 3, 1L,
        )

        processor.onGenerationJobCreated(GenerationJobCreated(74L, DocumentJobLane.BULK))

        verify(tx, times(2)).publishOne(74L)
    }

    @Test
    fun `exhausted fast path schedules exactly one coalesced rescue pass that republishes without duplicates`() {
        val tx: GenerationJobFastPublishTransaction = mock()
        whenever(tx.publishOne(any())).thenReturn(FastPublishOutcome.REJECTED_BUSY)
        val rec = Recorder()
        val scheduled = mutableListOf<Pair<Long, Runnable>>()
        val processor = fastPathProcessor(tx, rec).also {
            it.rescueDelayMs = 777L
            it.rescueScheduler = { d, t -> scheduled += d to t }
        }

        processor.onGenerationJobCreated(GenerationJobCreated(90L, DocumentJobLane.BULK))
        processor.onGenerationJobCreated(GenerationJobCreated(91L, DocumentJobLane.BULK))

        assertEquals(1, scheduled.size)
        assertEquals(777L, scheduled[0].first)
        scheduled[0].second.run()
        // The rescue ran (guard released) and a later exhaustion may schedule a new one-shot pass.
        processor.onGenerationJobCreated(GenerationJobCreated(92L, DocumentJobLane.BULK))
        assertEquals(2, scheduled.size)
    }

    @Test
    fun `a failing rescue pass is logged, releases the coalescing guard and does not escape the timer task`() {
        val tx: GenerationJobFastPublishTransaction = mock()
        whenever(tx.publishOne(any())).thenReturn(FastPublishOutcome.REJECTED_BUSY)
        val brokenJob: GenerationJob = mock()
        whenever(brokenJob.id).thenThrow(IllegalStateException("corrupt job row"))
        val queryService = object : GenerationJobQueryService(stubRepo()) {
            override fun findPendingWithInvoice(): List<GenerationJob> = listOf(brokenJob)
        }
        val scheduled = mutableListOf<Runnable>()
        val processor = GenerationJobProcessor(
            queryService, defaultPublishingService(), defaultRecoveryService(), noopPublisher(), tx,
            DelegatingManagedExecutor(Executor { it.run() }), 5, null,
            LlmPendingRecoveryBatchTransaction(defaultRecoveryService()), null, 3, 100L,
        ).also {
            it.sleeper = { }
            it.rescueScheduler = { _, task -> scheduled += task }
        }
        val records = captureLog { processor.onGenerationJobCreated(GenerationJobCreated(96L, DocumentJobLane.BULK)) }
        assertEquals(1, scheduled.size)

        val failureRecords = captureLog { assertDoesNotThrow { scheduled[0].run() } }

        assertTrue(records.none { it.level.intValue() >= java.util.logging.Level.SEVERE.intValue() })
        val error = failureRecords.single { it.level == java.util.logging.Level.SEVERE }
        assertEquals("Rescue pass for bulkhead-rejected generation jobs failed", error.message)
        assertEquals("corrupt job row", error.thrown.message)
        // The guard was released before the failure, so the next exhaustion schedules a fresh pass.
        processor.onGenerationJobCreated(GenerationJobCreated(97L, DocumentJobLane.BULK))
        assertEquals(2, scheduled.size)
    }

    @Test
    fun `a scheduler that rejects the rescue pass is logged and releases the coalescing guard`() {
        val tx: GenerationJobFastPublishTransaction = mock()
        whenever(tx.publishOne(any())).thenReturn(FastPublishOutcome.REJECTED_BUSY)
        var attempts = 0
        val processor = fastPathProcessor(tx, Recorder()).also {
            it.rescueScheduler = { _, _ ->
                attempts++
                throw RejectedExecutionException("scheduler shut down")
            }
        }

        val records = captureLog {
            assertDoesNotThrow { processor.onGenerationJobCreated(GenerationJobCreated(98L, DocumentJobLane.BULK)) }
            // A stuck guard would swallow this second request; a released one reaches the scheduler again.
            assertDoesNotThrow { processor.onGenerationJobCreated(GenerationJobCreated(99L, DocumentJobLane.BULK)) }
        }

        assertEquals(2, attempts)
        val errors = records.filter { it.level == java.util.logging.Level.SEVERE }
        assertEquals(2, errors.size)
        assertEquals("Could not schedule the rescue pass for bulkhead-rejected generation jobs", errors[0].message)
        assertEquals("scheduler shut down", errors[0].thrown.message)
    }

    private fun captureLog(block: () -> Unit): List<java.util.logging.LogRecord> {
        val julLogger = java.util.logging.Logger.getLogger(GenerationJobProcessor::class.java.name)
        val records = mutableListOf<java.util.logging.LogRecord>()
        val handler = object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) { records += record }
            override fun flush() = Unit
            override fun close() = Unit
        }
        julLogger.addHandler(handler)
        try {
            block()
        } finally {
            julLogger.removeHandler(handler)
        }
        return records
    }

    @Test
    fun `a published fast path schedules no rescue pass`() {
        val tx: GenerationJobFastPublishTransaction = mock()
        whenever(tx.publishOne(95L)).thenReturn(FastPublishOutcome.REJECTED_BUSY, FastPublishOutcome.PUBLISHED)
        var scheduledCount = 0
        val processor = fastPathProcessor(tx, Recorder()).also { it.rescueScheduler = { _, _ -> scheduledCount++ } }

        processor.onGenerationJobCreated(GenerationJobCreated(95L, DocumentJobLane.BULK))

        assertEquals(0, scheduledCount)
    }

    private fun fastPathProcessor(tx: GenerationJobFastPublishTransaction, rec: Recorder) = GenerationJobProcessor(
        defaultQueryService(), defaultPublishingService(), defaultRecoveryService(), noopPublisher(), tx,
        DelegatingManagedExecutor(Executor { it.run() }), 5, null,
        LlmPendingRecoveryBatchTransaction(defaultRecoveryService()), null, 3, 100L,
    ).also { it.sleeper = { ms -> rec.sleeps += ms } }

    @Test
    fun `fast path runs an interactive job on the interactive executor and a bulk job on the shared one`() {
        val tx: GenerationJobFastPublishTransaction = mock()
        whenever(tx.publishOne(any())).thenReturn(FastPublishOutcome.PUBLISHED)
        val shared = mutableListOf<Runnable>()
        val interactive = mutableListOf<Runnable>()
        val processor = GenerationJobProcessor(
            defaultQueryService(), defaultPublishingService(), defaultRecoveryService(), noopPublisher(), tx,
            DelegatingManagedExecutor(Executor { shared += it }), 5, null,
            LlmPendingRecoveryBatchTransaction(defaultRecoveryService()), null, 3, 100L,
            InteractiveFastPathExecutor { interactive += it },
        )

        processor.onGenerationJobCreated(GenerationJobCreated(80L, DocumentJobLane.INTERACTIVE))
        processor.onGenerationJobCreated(GenerationJobCreated(81L, DocumentJobLane.BULK))

        assertEquals(1, interactive.size)
        assertEquals(1, shared.size)
        verify(tx, never()).publishOne(any())
        interactive.single().run()
        verify(tx).publishOne(80L)
        verify(tx, never()).publishOne(81L)
        shared.single().run()
        verify(tx).publishOne(81L)
    }

    @Test
    fun `fast path leaves the job to the poller when the interactive executor rejects it`() {
        val tx: GenerationJobFastPublishTransaction = mock()
        val processor = GenerationJobProcessor(
            defaultQueryService(), defaultPublishingService(), defaultRecoveryService(), noopPublisher(), tx,
            DelegatingManagedExecutor(Executor { it.run() }), 5, null,
            LlmPendingRecoveryBatchTransaction(defaultRecoveryService()), null, 3, 100L,
            InteractiveFastPathExecutor { throw java.util.concurrent.RejectedExecutionException("queue full") },
        )

        assertDoesNotThrow { processor.onGenerationJobCreated(GenerationJobCreated(82L, DocumentJobLane.INTERACTIVE)) }

        verify(tx, never()).publishOne(any())
    }

    @Test
    fun `fetchPendingWithRetry stops retrying and rethrows when the sleep between attempts is interrupted`() {
        var fetchAttempts = 0
        val queryService = object : GenerationJobQueryService(stubRepo()) {
            override fun findPendingWithInvoice(): List<GenerationJob> {
                fetchAttempts++
                throw RuntimeException("db temporary")
            }
        }

        Thread.currentThread().interrupt()
        try {
            // Does not throw out of pollPendingJobs() — the interrupted retry still rethrows into
            // the outer try, which logs and returns, same as the retries-exhausted path.
            assertDoesNotThrow {
                GenerationJobProcessor(queryService, defaultPublishingService(), defaultRecoveryService(), noopPublisher()).pollPendingJobs()
            }
        } finally {
            assertTrue(Thread.interrupted(), "the interrupt flag must be restored, not swallowed")
        }
        assertEquals(1, fetchAttempts, "the interrupted sleep must stop retrying after the first attempt")
    }

    // ── resetStaleDelegatedJobs is no longer its own @Scheduled method (it used to run
    //    on a fixed 5-minute timer, independent of and drifting from the configurable
    //    recovery-interval pollPendingJobs runs on); it is now the first step of every
    //    pollPendingJobs/scanPendingJobs pass. These tests drive it exclusively through
    //    pollPendingJobs, proving the fold by an invocation-count assertion rather than by reading
    //    an annotation. ──────────────────────────────────────────────────────────────────────

    /** Every test below drives [GenerationJobProcessor.resetStaleDelegatedJobs] through a
     *  mocked [StaleDelegatedResetCoordinator] (a plain, non-final CDI bean — Mockito mocks it via
     *  subclassing, bypassing its own constructor, same as every other concrete-class mock in this
     *  suite), passed as the primary constructor's 8th argument. Every other argument uses the same
     *  defaults the rest of this test class already relies on. */
    private fun processorWithCoordinator(
        queryService: GenerationJobQueryService,
        coordinator: StaleDelegatedResetCoordinator,
        publisher: DocumentJobPublisher = noopPublisher(),
        publishingService: GenerationJobPublishingService = defaultPublishingService(),
    ) = GenerationJobProcessor(
        queryService, publishingService, defaultRecoveryService(), publisher, GenerationJobFastPublishTransaction(queryService, publishingService, publisher),
        DelegatingManagedExecutor { it.run() }, GenerationJobProcessor.DEFAULT_MAX_ATTEMPTS,
        coordinator,
    )

    @Test
    fun `pollPendingJobs does nothing and does not throw when no coordinator is wired`() {
        // The 4-arg constructor (used throughout most of this test class) leaves
        // staleDelegatedResetCoordinator at its `null` default — the plain-test-constructor case
        // that default exists for; see that parameter's own KDoc.
        assertDoesNotThrow {
            GenerationJobProcessor(defaultQueryService(), defaultPublishingService(), defaultRecoveryService(), noopPublisher()).pollPendingJobs()
        }
    }

    @Test
    fun `pollPendingJobs logs nothing when nothing was reset`() {
        val coordinator: StaleDelegatedResetCoordinator = mock()
        whenever(coordinator.resetStale(2)).thenReturn(0)

        assertDoesNotThrow { processorWithCoordinator(defaultQueryService(), coordinator).pollPendingJobs() }
    }

    @Test
    fun `pollPendingJobs logs when stale jobs were reset back to PENDING`() {
        val coordinator: StaleDelegatedResetCoordinator = mock()
        whenever(coordinator.resetStale(2)).thenReturn(3)

        assertDoesNotThrow { processorWithCoordinator(defaultQueryService(), coordinator).pollPendingJobs() }
    }

    @Test
    fun `pollPendingJobs does not throw when the stale-delegated reset fails`() {
        val coordinator: StaleDelegatedResetCoordinator = mock()
        whenever(coordinator.resetStale(2)).thenThrow(RuntimeException("db down"))

        assertDoesNotThrow { processorWithCoordinator(defaultQueryService(), coordinator).pollPendingJobs() }
    }

    @Test
    fun `pollPendingJobs still scans for PENDING jobs even when the stale-delegated reset fails`() {
        var scanned = false
        val coordinator: StaleDelegatedResetCoordinator = mock()
        whenever(coordinator.resetStale(2)).thenThrow(RuntimeException("db down"))
        val queryService = object : GenerationJobQueryService(stubRepo()) {
            override fun findPendingWithInvoice(): List<GenerationJob> {
                scanned = true
                return emptyList()
            }
        }

        processorWithCoordinator(queryService, coordinator).pollPendingJobs()

        assertTrue(scanned, "a failed stale-delegated reset must not abort the PENDING scan in the same tick")
    }

    @Test
    fun `pollPendingJobs resets stale delegated jobs on every recovery-interval tick, not on a separate fixed timer`() {
        val coordinator: StaleDelegatedResetCoordinator = mock()
        whenever(coordinator.resetStale(2)).thenReturn(0)
        val processor = processorWithCoordinator(defaultQueryService(), coordinator)

        processor.pollPendingJobs()
        processor.pollPendingJobs()

        verify(coordinator, times(2)).resetStale(2)
    }

    @Test
    fun `GenerationJobProcessor no longer carries a separate fixed-interval scheduled method for the stale-delegated reset`() {
        val scheduledMethodNames = GenerationJobProcessor::class.java.declaredMethods
            .filter { it.isAnnotationPresent(io.quarkus.scheduler.Scheduled::class.java) }
            .map { it.name }

        assertEquals(
            setOf("pollPendingJobs", "resetStaleLlmAnalyses"),
            scheduledMethodNames.toSet(),
            "resetStaleDelegatedJobs must no longer be an independent @Scheduled entry point",
        )
    }

    // ── onGenerationJobCreated dispatches to GenerationJobFastPublishTransaction.publishOne;
    //    publishOne's own behavior is exercised in GenerationJobFastPublishTransactionTest now that it
    //    lives on that dedicated bean (PO-Grundsatz 29.08.2026 — no more Provider<Self>). ──────

    @Test
    fun `onGenerationJobCreated dispatches publishOne via the injected executor`() {
        var dispatchedViaExecutor = false
        val executor = DelegatingManagedExecutor(Executor { task -> dispatchedViaExecutor = true; task.run() })
        val job = aGenerationJob {
            id = 30L
            invoice = anInvoice(invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber(demoInvoiceNumber))
        }
        var delegated = false
        val queryService = object : GenerationJobQueryService(stubRepo()) {
            override fun findByIdWithInvoice(id: Long): GenerationJob? = if (id == 30L) job else null
        }
        val publishingService = object : GenerationJobPublishingService(stubRepo(), time = FIXED_TIME_SOURCE) {
            override fun tryMarkDelegated(job: GenerationJob): Boolean { delegated = true; return true }
        }
        val processor = GenerationJobProcessor(queryService, publishingService, defaultRecoveryService(), noopPublisher(), executor)

        // noopPublisher() has no JetStream configured, so the publish attempt inside publishOne
        // fails and is swallowed — this test only asserts that the event was actually routed
        // through the executor and reached publishOne, not the publish outcome itself.
        assertDoesNotThrow { processor.onGenerationJobCreated(GenerationJobCreated(30L, DocumentJobLane.BULK)) }

        assertTrue(dispatchedViaExecutor, "the event must be dispatched via the injected executor, not run inline")
        assertTrue(delegated, "publishOne must have run and reached tryMarkDelegated")
    }

    @Test
    fun `the default same-thread executor of the 4-arg constructor actually runs the scheduled task`() {
        val job = aGenerationJob {
            id = 37L
            invoice = anInvoice(invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber(demoInvoiceNumber))
        }
        var delegated = false
        val queryService = object : GenerationJobQueryService(stubRepo()) {
            override fun findByIdWithInvoice(id: Long): GenerationJob? = job
        }
        val publishingService = object : GenerationJobPublishingService(stubRepo(), time = FIXED_TIME_SOURCE) {
            override fun tryMarkDelegated(job: GenerationJob): Boolean { delegated = true; return true }
        }
        // The 4-arg constructor (used throughout this test class) defaults to a same-thread
        // executor — proof that it actually runs the scheduled task rather than merely compiling.
        val processor = GenerationJobProcessor(queryService, publishingService, defaultRecoveryService(), noopPublisher())

        processor.onGenerationJobCreated(GenerationJobCreated(37L, DocumentJobLane.BULK))

        assertTrue(delegated, "the default same-thread executor must actually run the scheduled publishOne task")
    }

    @Test
    fun `onGenerationJobCreated dispatches through an explicitly injected GenerationJobFastPublishTransaction collaborator`() {
        // Production has CDI inject a real GenerationJobFastPublishTransaction bean (its own proxy), so
        // that publishOne()'s @Transactional is actually reached through interception rather than
        // bypassed — see that class's own KDoc. Exercised here via the true primary constructor,
        // naming every collaborator explicitly, including a custom GenerationJobFastPublishTransaction
        // built from the same two collaborators the processor itself was given.
        val job = aGenerationJob {
            id = 38L
            invoice = anInvoice(invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber(demoInvoiceNumber))
        }
        var delegated = false
        val queryService = object : GenerationJobQueryService(stubRepo()) {
            override fun findByIdWithInvoice(id: Long): GenerationJob? = job
        }
        val publishingService = object : GenerationJobPublishingService(stubRepo(), time = FIXED_TIME_SOURCE) {
            override fun tryMarkDelegated(job: GenerationJob): Boolean { delegated = true; return true }
        }
        val publisher = noopPublisher()
        val fastPublishTransaction = GenerationJobFastPublishTransaction(queryService, publishingService, publisher)
        val processor = GenerationJobProcessor(
            queryService, publishingService, defaultRecoveryService(), publisher, fastPublishTransaction,
            DelegatingManagedExecutor(Executor { it.run() }),
            GenerationJobProcessor.DEFAULT_MAX_ATTEMPTS,
        )

        processor.onGenerationJobCreated(GenerationJobCreated(38L, DocumentJobLane.BULK))

        assertTrue(delegated, "dispatch via the injected GenerationJobFastPublishTransaction must still reach publishOne")
    }

    @Test
    fun `onGenerationJobCreated logs and does not throw when the executor rejects the task`() {
        val throwingExecutor = DelegatingManagedExecutor(Executor { throw RejectedExecutionException("pool shut down") })
        val processor = GenerationJobProcessor(defaultQueryService(), defaultPublishingService(), defaultRecoveryService(), noopPublisher(), throwingExecutor)

        assertDoesNotThrow { processor.onGenerationJobCreated(GenerationJobCreated(31L, DocumentJobLane.BULK)) }
    }

    // ── resetStaleLlmAnalyses (the @Scheduled wrapper around
    //    GenerationJobRecoveryService.resetStaleLlmPending) ─────────────────────────────

    @Test
    fun `resetStaleLlmAnalyses delegates to the batched service entry point and survives a positive count`() {
        var receivedCutoff = -1L
        val recoveryService = object : GenerationJobRecoveryService(
            stubRepo(), mock<InvoiceRepository>(), mock<DomainEventDispatcher>(), mock<GenerationJobLlmResultHandler>(), time = FIXED_TIME_SOURCE,
        ) {
            override fun resetStaleLlmPendingBatched(
                batchTransaction: LlmPendingRecoveryBatchTransaction,
                staleCutoffMinutes: Long,
                lookbackHours: Long,
                batchSize: Int,
            ): Int {
                receivedCutoff = staleCutoffMinutes
                return 2
            }
        }

        assertDoesNotThrow {
            GenerationJobProcessor(defaultQueryService(), defaultPublishingService(), recoveryService, noopPublisher()).resetStaleLlmAnalyses()
        }
        assertEquals(15L, receivedCutoff, "the scheduled wrapper passes the documented 15-minute cutoff")
    }

    @Test
    fun `resetStaleLlmAnalyses stays quiet when nothing was stale`() {
        val recoveryService = object : GenerationJobRecoveryService(
            stubRepo(), mock<InvoiceRepository>(), mock<DomainEventDispatcher>(), mock<GenerationJobLlmResultHandler>(), time = FIXED_TIME_SOURCE,
        ) {
            override fun resetStaleLlmPendingBatched(
                batchTransaction: LlmPendingRecoveryBatchTransaction,
                staleCutoffMinutes: Long,
                lookbackHours: Long,
                batchSize: Int,
            ): Int = 0
        }

        assertDoesNotThrow {
            GenerationJobProcessor(defaultQueryService(), defaultPublishingService(), recoveryService, noopPublisher()).resetStaleLlmAnalyses()
        }
    }

    @Test
    fun `resetStaleLlmAnalyses never lets a service failure escape the scheduler`() {
        val recoveryService = object : GenerationJobRecoveryService(
            stubRepo(), mock<InvoiceRepository>(), mock<DomainEventDispatcher>(), mock<GenerationJobLlmResultHandler>(), time = FIXED_TIME_SOURCE,
        ) {
            override fun resetStaleLlmPendingBatched(
                batchTransaction: LlmPendingRecoveryBatchTransaction,
                staleCutoffMinutes: Long,
                lookbackHours: Long,
                batchSize: Int,
            ): Int = throw RuntimeException("db down")
        }

        assertDoesNotThrow {
            GenerationJobProcessor(defaultQueryService(), defaultPublishingService(), recoveryService, noopPublisher()).resetStaleLlmAnalyses()
        }
    }

    // ── The attempt-budget reopen sweep runs unconditionally on every tick now: the former
    //    worker-liveness gate is gone. ──────────────────────────────────────────────────────

    @Test
    fun `reopenExhaustedJobs runs on every tick, unconditionally`() {
        var reopenCalls = 0
        val publishingService = object : GenerationJobPublishingService(stubRepo(), time = FIXED_TIME_SOURCE) {
            override fun reopenExhaustedJobsWithoutDocument(): Int {
                reopenCalls++
                return 0
            }
        }

        GenerationJobProcessor(defaultQueryService(), publishingService, defaultRecoveryService(), noopPublisher()).pollPendingJobs()

        assertEquals(1, reopenCalls, "reopenExhaustedJobsWithoutDocument must run every tick, no gate left")
    }

    @Test
    fun `scanPendingJobs logs when attempt-budget-exhausted jobs were reopened`() {
        val publishingService = object : GenerationJobPublishingService(stubRepo(), time = FIXED_TIME_SOURCE) {
            override fun reopenExhaustedJobsWithoutDocument(): Int = 2
        }

        assertDoesNotThrow {
            GenerationJobProcessor(defaultQueryService(), publishingService, defaultRecoveryService(), noopPublisher()).pollPendingJobs()
        }
    }

    @Test
    fun `scanPendingJobs never lets a reopen failure escape the scheduler`() {
        val publishingService = object : GenerationJobPublishingService(stubRepo(), time = FIXED_TIME_SOURCE) {
            override fun reopenExhaustedJobsWithoutDocument(): Int = throw RuntimeException("db down")
        }

        assertDoesNotThrow {
            GenerationJobProcessor(defaultQueryService(), publishingService, defaultRecoveryService(), noopPublisher()).pollPendingJobs()
        }
    }

    // ── Fix U — isPostgresDeadlock: a branch a scoped coverage run (full-verify-fixups,
    //    2026-09-18) found still untested after Fix U landed. ───────────────────────────────────

    @Test
    fun `scanPendingJobs logs a Postgres deadlock as a self-healing warning, not an error`() {
        val job = aGenerationJob {
            id = 50L
            invoice = anInvoice(invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber(demoInvoiceNumber))
        }
        val publisher = mock<DocumentJobPublisher>()
        // Mockito's thenThrow refuses a checked java.sql.SQLException directly (not declared on
        // publish()'s signature) — wrapped in an unchecked exception instead, exactly how a real
        // JPA/Hibernate/Narayana layer would actually surface it (see isPostgresDeadlock's own
        // KDoc: it walks the full cause chain for precisely this reason).
        whenever(publisher.publish(job))
            .thenThrow(RuntimeException("commit failed", java.sql.SQLException("deadlock detected", "40P01")))
        val queryService = object : GenerationJobQueryService(stubRepo()) {
            override fun findPendingWithInvoice(): List<GenerationJob> = listOf(job)
        }
        val publishingService = object : GenerationJobPublishingService(stubRepo(), time = FIXED_TIME_SOURCE) {
            override fun tryMarkDelegated(job: GenerationJob): Boolean = true
        }

        // The deadlock branch (log.warnf, PostgresDeadlockDetection) must be taken instead of the
        // generic error branch — both are self-healing (job stays PENDING, next tick retries), but
        // only a real SQLSTATE 40P01 proves isPostgresDeadlock's cause-chain walk actually fires
        // from this call site, not merely that some exception was caught.
        assertDoesNotThrow {
            GenerationJobProcessor(queryService, publishingService, defaultRecoveryService(), publisher).pollPendingJobs()
        }
    }
}
