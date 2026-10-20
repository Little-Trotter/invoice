package de.littletrotter.e2e.nats

import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.fixture.aGenerationJob
import de.littletrotter.shared.ExportResult
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.NatsStreamSetup
import de.littletrotter.shared.PdfExportPort
import de.littletrotter.xpdf.infrastructure.messaging.ResultPublisher
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scenario 3 — redelivery: a delivery whose export succeeds but whose result publish
 * cannot reach the app (a downstream outage, e.g. NATS transiently unreachable from the worker's
 * own result-publish side) must NAK, not ack — JetStream then redelivers, attempts are counted by
 * the broker itself, and the app-side job is never falsely marked terminal in the meantime (it
 * simply stays exactly where the round trip left it, DELEGATED — this messaging layer never
 * reports [de.littletrotter.domain.invoice.GenerationJobStatus.RUNNING] at all, see
 * `GenerationJobStateMachine`'s own edge table: only a worker-originated "job started" message
 * could ever move a job there, and neither `JobConsumer` nor `ResultPublisher` ever sends one).
 *
 * The outage is modeled with a real [ResultPublisher] pointed at a results subject that is
 * deliberately never provisioned on any stream (`jetStream.publish` on an uncovered subject always
 * throws) — this is the exact "NATS being unreachable means nobody could have received the
 * result anyway" case [de.littletrotter.xpdf.JobConsumer.handle]'s own KDoc describes for why it
 * naks rather than acks on a publish failure. `maxDeliver` is bounded so the outage does not
 * redeliver forever within the test's own timeout, proving there is no infinite redelivery loop.
 */
@ExtendWith(NatsE2EExtension::class)
class NatsRedeliveryE2EIT {

    private val workerDurable = "e2e-redelivery-worker"

    @AfterEach
    fun cleanup() {
        System.clearProperty("job.retry.count")
        NatsE2ESupport.deleteConsumer(NatsStreamSetup.STREAM_NAME, workerDurable)
        // The whole point of this scenario is a job message that can never be acked - it would
        // otherwise sit unacked forever on the shared WorkQueue stream (exhausting maxDeliver only
        // stops THIS consumer from redelivering it, it does not remove it) and leak into whichever
        // scenario creates the next durable consumer on "document-jobs".
        NatsE2ESupport.purgeSubject(NatsE2EExtension.JOBS_SUBJECT)
    }

    @Test
    fun `a result-publish outage naks the message, JetStream redelivers a bounded number of times, and the job is never falsely marked terminal`() {
        // The worker's own redelivery budget is bounded to retry.count + 1 deliveries before it
        // treats the message as poison and acks it; the scenario below needs every JetStream
        // redelivery up to maxDeliver (3) to be processed, so the budget is aligned with maxDeliver.
        System.setProperty("job.retry.count", "2")
        val jobId = 900_003L

        val exportAttempts = AtomicInteger(0)
        val pdfExportPort = object : PdfExportPort {
            override fun performExport(
                invoice: InvoiceDto, jobId: String, llmOptimize: Boolean, skipValidation: Boolean, veraPdfCheck: Boolean, outputIntent: String?,
            ): ExportResult {
                exportAttempts.incrementAndGet()
                return ExportResult("invoices/$jobId.pdf", "invoices/$jobId.xml", true, "kosit-ok", "sha-$jobId")
            }
        }

        // A real ResultPublisher, deliberately pointed at a subject no stream covers - every
        // publishResult call exhausts its own 3-attempt retry and throws, exactly like a genuine,
        // ongoing NATS-side outage on the result-publish path.
        val brokenResultPublisher = ResultPublisher(NatsE2EExtension.jetStream(), "e2e-redelivery-results-unprovisioned")
        val consumer = NatsE2ESupport.newWorkerConsumer(pdfExportPort, resultPublisher = brokenResultPublisher)

        val repo = RecordingGenerationJobRepository(
            aGenerationJob { id = jobId; status = GenerationJobStatus.DELEGATED },
        )

        val maxDeliver = 3L
        val workerHandle = NatsE2ESupport.subscribeWorker(consumer, workerDurable, maxDeliver = maxDeliver, ackWaitSeconds = 10)

        try {
            val publisher = NatsE2ESupport.newPublisher()
            publisher.publish(NatsE2ESupport.newInvoice(jobId, "INV-2026-9004"), jobId).get(10, TimeUnit.SECONDS)

            val redeliveredAtLeastTwice = NatsE2ESupport.waitUntil(30) { exportAttempts.get() >= 2 }
            assertTrue(redeliveredAtLeastTwice, "the outage must cause at least one genuine redelivery, only got ${exportAttempts.get()} attempt(s)")

            // The broker's own consumer info corroborates the client-observed attempts with its
            // own redelivery counter - proof this is real JetStream redelivery, not an artifact of
            // the stub port being called twice for some unrelated reason.
            val info = NatsE2EExtension.jetStreamManagement().getConsumerInfo(NatsStreamSetup.STREAM_NAME, workerDurable)
            assertTrue(info.redelivered > 0, "the broker must report at least one redelivered message for this consumer, was ${info.redelivered}")

            // No infinite loop: the outage never clears, so delivery attempts must stop growing
            // exactly at maxDeliver, never beyond it.
            val exhausted = NatsE2ESupport.waitUntil(15) { exportAttempts.get() >= maxDeliver }
            assertTrue(exhausted, "delivery attempts must reach maxDeliver=$maxDeliver, only got ${exportAttempts.get()}")
            Thread.sleep(3_000)
            assertEquals(maxDeliver.toInt(), exportAttempts.get(), "delivery attempts must stop exactly at maxDeliver=$maxDeliver, never beyond it")

            // The outage never reached the app at all (every result-publish attempt failed before
            // leaving the worker) - the job must still read exactly as the round trip left it,
            // never silently flipped to a wrong terminal state.
            assertEquals(
                GenerationJobStatus.DELEGATED, repo.findById(jobId)?.status,
                "a job whose every result-publish attempt failed must never be marked terminal by a message that never arrived",
            )
        } finally {
            NatsE2ESupport.closeConsumer(workerHandle)
        }
    }
}
