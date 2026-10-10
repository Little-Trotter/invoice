package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.repository.GenerationJobRepository
import io.nats.client.JetStream
import io.nats.client.PublishOptions
import io.nats.client.api.PublishAck
import io.nats.client.impl.Headers
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.eclipse.microprofile.faulttolerance.exceptions.BulkheadException
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.CompletableFuture
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * PO-Grundsatz 29.08.2026 (CDI-Injektionen nie ausnehmen) — [GenerationJobFastPublishTransaction.publishOne]
 * moved here, off [GenerationJobProcessor], when the former `Provider<GenerationJobProcessor>`
 * self-reference was replaced by this dedicated collaborator bean (see that class's own KDoc).
 * These tests are the direct move of [GenerationJobProcessor]'s former `publishOne`-suite —
 * [GenerationJobProcessorTest] now only exercises that [onGenerationJobCreated
 * ][GenerationJobProcessor.onGenerationJobCreated] dispatches to this bean's [publishOne
 * ][GenerationJobFastPublishTransaction.publishOne], not what that method itself does.
 *
 * [GenerationJobService] dissolved: the fakes below now directly subclass its two former
 * collaborators, [GenerationJobQueryService] (`findByIdWithInvoice`) and [GenerationJobPublishingService]
 * (`tryMarkDelegated`/`revertToPending`/`recordDelegatedStreamSeq`), both CDI-allopen like the
 * facade they replace.
 */
class GenerationJobFastPublishTransactionTest {

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

    private fun defaultPublishingService() = object : GenerationJobPublishingService(stubRepo(), time = FIXED_TIME_SOURCE) {}

    @Test
    fun `publishOne does nothing when the job no longer exists`() {
        val queryService = object : GenerationJobQueryService(stubRepo()) {
            override fun findByIdWithInvoice(id: Long): GenerationJob? = null
        }

        assertDoesNotThrow { GenerationJobFastPublishTransaction(queryService, defaultPublishingService(), noopPublisher()).publishOne(999L) }
    }

    @Test
    fun `publishOne skips a job that has no associated invoice`() {
        val job = GenerationJob().apply { id = 32L } // invoice stays null
        var delegateCalled = false
        val queryService = object : GenerationJobQueryService(stubRepo()) {
            override fun findByIdWithInvoice(id: Long): GenerationJob? = job
        }
        val publishingService = object : GenerationJobPublishingService(stubRepo(), time = FIXED_TIME_SOURCE) {
            override fun tryMarkDelegated(job: GenerationJob): Boolean { delegateCalled = true; return true }
        }

        GenerationJobFastPublishTransaction(queryService, publishingService, noopPublisher()).publishOne(32L)

        assertFalse(delegateCalled, "a job without an invoice must never reach tryMarkDelegated")
    }

    @Test
    fun `publishOne skips when tryMarkDelegated loses the race to the poller`() {
        val publisher = noopPublisher()
        val job = GenerationJob().apply {
            id = 33L
            invoice = de.littletrotter.domain.invoice.Invoice().apply {
                invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber("RE-2026-01-00033")
            }
        }
        val queryService = object : GenerationJobQueryService(stubRepo()) {
            override fun findByIdWithInvoice(id: Long): GenerationJob? = job
        }
        val publishingService = object : GenerationJobPublishingService(stubRepo(), time = FIXED_TIME_SOURCE) {
            override fun tryMarkDelegated(job: GenerationJob): Boolean = false
        }

        // Must not throw even though noopPublisher's publish() would throw — proof it is never
        // reached when the idempotency guard loses the race.
        assertDoesNotThrow { GenerationJobFastPublishTransaction(queryService, publishingService, publisher).publishOne(33L) }
    }

    @Test
    fun `publishOne publishes successfully, records the real ack seqno synchronously, and does not revert`() {
        val jetStream: JetStream = mock()
        val ack: PublishAck = mock()
        whenever(ack.seqno).thenReturn(4242L)
        whenever(jetStream.publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>()))
            .thenReturn(CompletableFuture.completedFuture(ack))
        val publisher = DocumentJobPublisher(jetStreamProvider = { jetStream })
        val job = GenerationJob().apply {
            id = 34L
            invoice = de.littletrotter.domain.invoice.Invoice().apply {
                invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber("RE-2026-01-00034")
            }
        }
        var reverted = false
        var recordedWith: Pair<Long?, Long>? = null
        val queryService = object : GenerationJobQueryService(stubRepo()) {
            override fun findByIdWithInvoice(id: Long): GenerationJob? = job
        }
        val publishingService = object : GenerationJobPublishingService(stubRepo(), time = FIXED_TIME_SOURCE) {
            override fun tryMarkDelegated(job: GenerationJob): Boolean = true
            override fun revertToPending(job: GenerationJob): Boolean { reverted = true; return true }
            override fun recordDelegatedStreamSeq(job: GenerationJob, streamSeq: Long): Boolean {
                recordedWith = job.id to streamSeq
                return true
            }
        }

        GenerationJobFastPublishTransaction(queryService, publishingService, publisher).publishOne(34L)

        org.mockito.kotlin.verify(jetStream).publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>())
        assertEquals(34L to 4242L, recordedWith, "the real ack's own seqno must be recorded, synchronously, on the success path")
        assertFalse(reverted, "a successful publish must not trigger a revert back to PENDING")
    }

    @Test
    fun `publishOne records the seqno of a duplicate ack the same way as any other successful ack`() {
        // The stable Nats-Msg-Id dedup: a republish of the same job within the broker's
        // duplicate window is accepted by JetStream as a duplicate, but the ack it returns still
        // carries the ORIGINAL message's own real seqno -- exactly the sequence this job's message
        // still lives at, so it is recorded the same way as any other successful ack.
        val jetStream: JetStream = mock()
        val ack: PublishAck = mock()
        whenever(ack.seqno).thenReturn(777L)
        whenever(ack.isDuplicate).thenReturn(true)
        whenever(jetStream.publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>()))
            .thenReturn(CompletableFuture.completedFuture(ack))
        val publisher = DocumentJobPublisher(jetStreamProvider = { jetStream })
        val job = GenerationJob().apply {
            id = 40L
            invoice = de.littletrotter.domain.invoice.Invoice().apply {
                invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber("RE-2026-01-00040")
            }
        }
        var recordedWith: Pair<Long?, Long>? = null
        val queryService = object : GenerationJobQueryService(stubRepo()) {
            override fun findByIdWithInvoice(id: Long): GenerationJob? = job
        }
        val publishingService = object : GenerationJobPublishingService(stubRepo(), time = FIXED_TIME_SOURCE) {
            override fun tryMarkDelegated(job: GenerationJob): Boolean = true
            override fun recordDelegatedStreamSeq(job: GenerationJob, streamSeq: Long): Boolean {
                recordedWith = job.id to streamSeq
                return true
            }
        }

        GenerationJobFastPublishTransaction(queryService, publishingService, publisher).publishOne(40L)

        assertEquals(40L to 777L, recordedWith, "a duplicate ack's own seqno points at the same, still-real message")
    }

    @Test
    fun `publishOne reports REJECTED_BUSY for a bulkhead rejection and FAILED otherwise, reverting the claim both times`() {
        val job = GenerationJob().apply {
            id = 80L
            invoice = de.littletrotter.domain.invoice.Invoice().apply {
                invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber("RE-2026-01-00080")
            }
        }
        val queryService = object : GenerationJobQueryService(stubRepo()) {
            override fun findByIdWithInvoice(id: Long): GenerationJob? = job
        }
        var reverts = 0
        val publishingService = object : GenerationJobPublishingService(stubRepo(), time = FIXED_TIME_SOURCE) {
            override fun tryMarkDelegated(job: GenerationJob): Boolean = true
            override fun revertToPending(job: GenerationJob): Boolean { reverts++; return true }
        }
        val publisher: DocumentJobPublisher = org.mockito.kotlin.mock()
        whenever(publisher.publish(any<GenerationJob>(), any<Boolean>()))
            .thenThrow(RuntimeException("wrapped", BulkheadException("full")))
            .thenThrow(RuntimeException("NATS down"))
        val tx = GenerationJobFastPublishTransaction(queryService, publishingService, publisher)

        assertEquals(FastPublishOutcome.REJECTED_BUSY, tx.publishOne(80L))
        assertEquals(FastPublishOutcome.FAILED, tx.publishOne(80L))
        assertEquals(2, reverts)
    }

    @Test
    fun `publishOne reverts the job to PENDING when the publish attempt fails`() {
        val jetStream: JetStream = mock()
        whenever(jetStream.publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>()))
            .thenReturn(CompletableFuture.failedFuture(RuntimeException("NATS unreachable")))
        val publisher = DocumentJobPublisher(jetStreamProvider = { jetStream })
        val job = GenerationJob().apply {
            id = 35L
            invoice = de.littletrotter.domain.invoice.Invoice().apply {
                invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber("RE-2026-01-00035")
            }
        }
        var revertedJobId: Long? = null
        var recordCalled = false
        val queryService = object : GenerationJobQueryService(stubRepo()) {
            override fun findByIdWithInvoice(id: Long): GenerationJob? = job
        }
        val publishingService = object : GenerationJobPublishingService(stubRepo(), time = FIXED_TIME_SOURCE) {
            override fun tryMarkDelegated(job: GenerationJob): Boolean = true
            override fun revertToPending(job: GenerationJob): Boolean { revertedJobId = job.id; return true }
            override fun recordDelegatedStreamSeq(job: GenerationJob, streamSeq: Long): Boolean { recordCalled = true; return true }
        }

        assertDoesNotThrow { GenerationJobFastPublishTransaction(queryService, publishingService, publisher).publishOne(35L) }

        assertEquals(35L, revertedJobId, "a failed fast-path publish must hand the claim back so the poller retries")
        assertFalse(recordCalled, "no ack ever arrived -- there is nothing to record a sequence from")
    }

    @Test
    fun `publishOne reverts the job to PENDING when recording the ack's own seqno fails`() {
        // Recording failure is folded into the exact same catch as a
        // publish failure: the claim is handed back, the poller retries the whole publish.
        val jetStream: JetStream = mock()
        val ack: PublishAck = mock()
        whenever(ack.seqno).thenReturn(1L)
        whenever(jetStream.publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>()))
            .thenReturn(CompletableFuture.completedFuture(ack))
        val publisher = DocumentJobPublisher(jetStreamProvider = { jetStream })
        val job = GenerationJob().apply {
            id = 41L
            invoice = de.littletrotter.domain.invoice.Invoice().apply {
                invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber("RE-2026-01-00041")
            }
        }
        var revertedJobId: Long? = null
        val queryService = object : GenerationJobQueryService(stubRepo()) {
            override fun findByIdWithInvoice(id: Long): GenerationJob? = job
        }
        val publishingService = object : GenerationJobPublishingService(stubRepo(), time = FIXED_TIME_SOURCE) {
            override fun tryMarkDelegated(job: GenerationJob): Boolean = true
            override fun revertToPending(job: GenerationJob): Boolean { revertedJobId = job.id; return true }
            override fun recordDelegatedStreamSeq(job: GenerationJob, streamSeq: Long): Boolean = throw RuntimeException("db down")
        }

        assertDoesNotThrow { GenerationJobFastPublishTransaction(queryService, publishingService, publisher).publishOne(41L) }

        assertEquals(41L, revertedJobId, "recording failure is treated exactly like a publish failure")
    }

    @Test
    fun `publishOne logs and does not throw when looking up the job fails unexpectedly`() {
        val queryService = object : GenerationJobQueryService(stubRepo()) {
            override fun findByIdWithInvoice(id: Long): GenerationJob? = throw RuntimeException("db down")
        }

        assertDoesNotThrow { GenerationJobFastPublishTransaction(queryService, defaultPublishingService(), noopPublisher()).publishOne(36L) }
    }

    // ── Fix U — isPostgresDeadlock: a scoped coverage run (full-verify-fixups, 2026-09-18) found
    //    this branch untested here too — the existing "reverts to PENDING" test above only ever
    //    exercises the generic-error branch (a plain RuntimeException), never a real Postgres
    //    deadlock SQLSTATE. ─────────────────────────────────────────────────────────────────────

    @Test
    fun `publishOne logs a Postgres deadlock as a self-healing warning and still reverts to PENDING`() {
        val jetStream: JetStream = mock()
        whenever(jetStream.publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>()))
            .thenReturn(CompletableFuture.failedFuture(java.sql.SQLException("deadlock detected", "40P01")))
        val publisher = DocumentJobPublisher(jetStreamProvider = { jetStream })
        val job = GenerationJob().apply {
            id = 39L
            invoice = de.littletrotter.domain.invoice.Invoice().apply {
                invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber("RE-2026-01-00039")
            }
        }
        var revertedJobId: Long? = null
        val queryService = object : GenerationJobQueryService(stubRepo()) {
            override fun findByIdWithInvoice(id: Long): GenerationJob? = job
        }
        val publishingService = object : GenerationJobPublishingService(stubRepo(), time = FIXED_TIME_SOURCE) {
            override fun tryMarkDelegated(job: GenerationJob): Boolean = true
            override fun revertToPending(job: GenerationJob): Boolean { revertedJobId = job.id; return true }
        }

        assertDoesNotThrow { GenerationJobFastPublishTransaction(queryService, publishingService, publisher).publishOne(39L) }

        assertEquals(39L, revertedJobId, "a deadlock must still hand the claim back to the poller, exactly like any other failure")
    }
}
