package de.littletrotter.e2e.nats

import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.fixture.aGenerationJob
import de.littletrotter.infrastructure.messaging.NatsDelegatedMessageProbe
import de.littletrotter.shared.ExportResult
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.NatsStreamSetup
import de.littletrotter.shared.PdfExportPort
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scenario 5 — recovery: a job published while the worker is down stays exactly where the
 * publish left it (DELEGATED, per `GenerationJobStateMachine`'s edge table — the state machine has
 * no "worker unreachable" edge of its own, because nothing about the message being merely
 * undelivered ever reaches the app at all); once the worker (consumer) attaches, JetStream's own
 * WorkQueue-retention backlog delivers the still-pending message and the job completes normally.
 *
 * Scope note: "per the state machine" also names the
 * DELEGATED->PENDING stale-guard sweep (`GenerationJobProcessor`'s scheduled poller,
 * `StaleDelegatedResetCoordinator`), the app-side half of real-world worker-down recovery. That
 * poller is a Quarkus `@Scheduled` bean in `kotlin-invoice` and is out of reach here for the same
 * reason already documented for booting the full app (`LinkageError` on kotlin-shared enums) - this
 * scenario is scoped to the messaging layer's own recovery guarantee (an undelivered message is
 * never lost and is not silently treated as failed), not the scheduler-driven stale-reset path.
 */
@ExtendWith(NatsE2EExtension::class)
class NatsWorkerRecoveryE2EIT {

    private val workerDurable = "e2e-recovery-worker"
    private val appDurable = "e2e-recovery-app"

    @AfterEach
    fun cleanup() {
        NatsE2ESupport.deleteConsumer(NatsStreamSetup.STREAM_NAME, workerDurable)
        NatsE2ESupport.deleteConsumer(NatsStreamSetup.STREAM_NAME, appDurable)
        NatsE2ESupport.purgeSubject(NatsE2EExtension.JOBS_SUBJECT)
        NatsE2ESupport.purgeSubject(NatsE2EExtension.RESULTS_SUBJECT)
    }

    @Test
    fun `a job published while the worker is down stays DELEGATED, then completes once the worker attaches`() {
        val jobId = 900_005L
        val repo = RecordingGenerationJobRepository(
            aGenerationJob { id = jobId; status = GenerationJobStatus.DELEGATED },
        )
        val resultConsumer = NatsE2ESupport.newAppResultConsumer(repo)
        val appHandle = NatsE2ESupport.subscribeAppAdapter(resultConsumer, appDurable)

        // "Worker down": the job is published to the real, durable WorkQueue stream before any
        // worker-side durable consumer is attached at all - nothing is listening yet.
        val publisher = NatsE2ESupport.newPublisher()
        publisher.publish(NatsE2ESupport.newInvoice(jobId, "INV-2026-9005"), jobId).get(10, TimeUnit.SECONDS)

        Thread.sleep(2_000)
        assertEquals(
            GenerationJobStatus.DELEGATED, repo.findById(jobId)?.status,
            "with no worker attached, the job must stay exactly where the publish left it - never silently failed or completed",
        )

        // "Worker reconnects": the durable consumer is created and attached only now: JetStream's
        // WorkQueue retention has been holding the message the whole time.
        val exportAttempts = AtomicInteger(0)
        val pdfExportPort = object : PdfExportPort {
            override fun performExport(
                invoice: InvoiceDto, jobId: String, llmOptimize: Boolean, skipValidation: Boolean, veraPdfCheck: Boolean, outputIntent: String?,
            ): ExportResult {
                exportAttempts.incrementAndGet()
                return ExportResult("invoices/$jobId.pdf", "invoices/$jobId.xml", true, "kosit-ok", "sha-$jobId")
            }
        }
        val workerConsumer = NatsE2ESupport.newWorkerConsumer(pdfExportPort)
        val workerHandle = NatsE2ESupport.subscribeWorker(workerConsumer, workerDurable)

        try {
            val completed = NatsE2ESupport.waitUntil(30) { repo.findById(jobId)?.status == GenerationJobStatus.COMPLETED }
            assertTrue(completed, "the backlogged message must be delivered and completed once the worker attaches")
            assertEquals(1, exportAttempts.get(), "the backlogged message must be processed exactly once")
        } finally {
            NatsE2ESupport.closeConsumer(workerHandle)
            NatsE2ESupport.closeConsumer(appHandle)
        }
    }

    /**
     * The real production [NatsDelegatedMessageProbe] (kotlin-invoice) against a real NATS
     * JetStream, complementing the scenario above: that one proves the messaging layer's own
     * recovery guarantee (an undelivered message is never lost); this one proves the SIGNAL
     * `StaleDelegatedResetCoordinator` reads to decide whether a stale-DELEGATED reset should
     * actually fire — a real message's existence in the stream, not merely "a durable consumer
     * object exists". `GenerationJobProcessor`/`StaleDelegatedResetCoordinator` themselves are out
     * of reach here for the same `LinkageError` reason this file's own class KDoc documents
     * — this test is scoped to the probe's own real-NATS behavior.
     */
    @Test
    fun `NatsDelegatedMessageProbe reports the real delivered cursor and per-message existence`() {
        val availabilityDurable = "e2e-availability-worker"
        val probe = NatsDelegatedMessageProbe(NatsE2EExtension.connection, availabilityDurable, "$availabilityDurable-interactive")

        assertEquals(
            0L, probe.snapshot().deliveredStreamSeq,
            "no durable consumer named $availabilityDurable exists yet -- must report delivered=0, not throw",
        )

        val jobId = 900_006L
        val publisher = NatsE2ESupport.newPublisher()
        val ack = publisher.publish(NatsE2ESupport.newInvoice(jobId, "INV-2026-9006"), jobId).get(10, TimeUnit.SECONDS)

        assertTrue(probe.exists(ack.seqno), "a freshly published, never-consumed message must still exist in the stream")

        val exportAttempts = AtomicInteger(0)
        val pdfExportPort = object : PdfExportPort {
            override fun performExport(
                invoice: InvoiceDto, jobId: String, llmOptimize: Boolean, skipValidation: Boolean, veraPdfCheck: Boolean, outputIntent: String?,
            ): ExportResult {
                exportAttempts.incrementAndGet()
                return ExportResult("invoices/$jobId.pdf", "invoices/$jobId.xml", true, "kosit-ok", "sha-$jobId")
            }
        }
        val workerConsumer = NatsE2ESupport.newWorkerConsumer(pdfExportPort)
        val workerHandle = NatsE2ESupport.subscribeWorker(workerConsumer, availabilityDurable)
        try {
            val delivered = NatsE2ESupport.waitUntil(10) { probe.snapshot().deliveredStreamSeq >= ack.seqno }
            assertTrue(delivered, "the delivered cursor must advance once an actively-pulling durable consumer attaches")

            val consumed = NatsE2ESupport.waitUntil(10) { exportAttempts.get() == 1 }
            assertTrue(consumed, "the worker must have processed (and acked) the message")

            val gone = NatsE2ESupport.waitUntil(10) { !probe.exists(ack.seqno) }
            assertTrue(gone, "an acked message must no longer exist in the stream")
        } finally {
            NatsE2ESupport.closeConsumer(workerHandle)
            NatsE2ESupport.deleteConsumer(NatsStreamSetup.STREAM_NAME, availabilityDurable)
        }
    }
}
