package de.littletrotter.xpdf.infrastructure.messaging

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import de.littletrotter.shared.ExportResult
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.PdfExportPort
import io.nats.client.ConsumeOptions
import io.nats.client.api.ConsumerConfiguration
import io.nats.client.impl.Headers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.mock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicInteger

/**
 * AP-M2 — the two IT-level
 * proofs the plan explicitly requires against a *real* JetStream broker (mocks don't count for
 * redelivery/heartbeat behavior, per plan §5):
 *
 *   (a) "a handler that takes 3x ackWait finishes without the message ever being redelivered"
 *       — proves the heartbeat actually resets the broker's ack window while a job is genuinely
 *       still being worked on.
 *   (b) "killing the heartbeat (simulated dead worker) leads to redelivery after ~ackWait"
 *       — the control: with the exact same slow handler but no heartbeat reaching the broker,
 *       JetStream redelivers on its own once the (short, test-scoped) ackWait lapses.
 *
 * Both tests use a short, test-scoped `ackWaitSeconds` (3s) instead of the 120s production
 * default so they run in seconds, not minutes — the mechanism under test (a periodic
 * `message.inProgress()` call vs. none at all) is identical regardless of the window's absolute
 * size.
 */
@ExtendWith(NatsTestContainersExtension::class)
class JobConsumerHeartbeatIT {

    private val mapper = jacksonObjectMapper()

    @AfterEach
    fun cleanup() {
        System.clearProperty("job.retry.count")
        System.clearProperty("job.retry.backoff.ms")
        System.clearProperty("s3.endpoint")
        System.clearProperty("s3.bucket")
        System.clearProperty("nats.consumer.ack-wait-seconds")
    }

    @Test
    fun `a handler taking 3x ackWait finishes without the message ever being redelivered, thanks to the heartbeat`() {
        System.setProperty("job.retry.count", "0")
        System.setProperty("s3.endpoint", "http://rustfs-test:9000")
        System.setProperty("s3.bucket", "documents-test")
        // 3s ackWait -> heartbeatIntervalSeconds = 1s (ackWait / 3, JobConsumer's own field).
        System.setProperty("nats.consumer.ack-wait-seconds", "3")

        val jobId = "heartbeat-alive-${UUID.randomUUID()}"
        val jobsSubject = "document-jobs-heartbeat-alive-${UUID.randomUUID()}"
        val exportAttempts = AtomicInteger(0)

        val pdfExportPort = object : PdfExportPort {
            override fun performExport(
                invoice: InvoiceDto, jobId: String, llmOptimize: Boolean, skipValidation: Boolean, veraPdfCheck: Boolean, outputIntent: String?,
            ): ExportResult {
                exportAttempts.incrementAndGet()
                // ~3x the 3s ackWait: long enough that, without a heartbeat, the broker would
                // redeliver at least twice before this single attempt even finishes.
                Thread.sleep(9_000)
                return ExportResult("invoices/$jobId.pdf", "invoices/$jobId.xml", true, "kosit-ok", "sha-$jobId")
            }
        }

        // The consumer's OWN, real (default) heartbeatExecutor is deliberately left in place —
        // this is exactly the mechanism under test.
        val resultPublisher = ResultPublisher(NatsTestContainersExtension.jetStream())
        val consumer = newConsumer(pdfExportPort, resultPublisher)

        subscribe(consumer, jobsSubject, ackWaitSeconds = 3, maxDeliver = 5)
        publishJob(jobsSubject, jobId)

        val result = pollResult(jobId, timeoutSeconds = 30)

        assertEquals("COMPLETED", result?.get("status"), "the export must complete and publish its result despite taking 3x ackWait")
        assertEquals(1, exportAttempts.get(), "the heartbeat must have kept the ack window open - the job must never have been redelivered/re-exported")
    }

    @Test
    fun `killing the heartbeat leads to redelivery once ackWait lapses, for the exact same slow handler`() {
        // job.retry.count now also sizes JobConsumer's own redelivery-poison
        // budget (deliveredCount > retryCount + 1 is treated as poison, see
        // JobConsumer.isExcessivelyRedelivered). This test's whole point is to observe a SECOND
        // delivery of the exact same message (a crashed/dead worker being picked up again, not a
        // handler that already failed and gave up) — "0" (the other heartbeat test's value, inert
        // there since its handler never throws) would make that second delivery read as
        // already-poison and short-circuit before the handler ever runs a second time. A generous
        // budget here keeps the poison bound out of this test's way, exactly as it would in
        // production's own default (3).
        System.setProperty("job.retry.count", "5")
        System.setProperty("s3.endpoint", "http://rustfs-test:9000")
        System.setProperty("s3.bucket", "documents-test")
        System.setProperty("nats.consumer.ack-wait-seconds", "3")

        val jobId = "heartbeat-dead-${UUID.randomUUID()}"
        val jobsSubject = "document-jobs-heartbeat-dead-${UUID.randomUUID()}"
        val exportAttempts = AtomicInteger(0)

        val pdfExportPort = object : PdfExportPort {
            override fun performExport(
                invoice: InvoiceDto, jobId: String, llmOptimize: Boolean, skipValidation: Boolean, veraPdfCheck: Boolean, outputIntent: String?,
            ): ExportResult {
                exportAttempts.incrementAndGet()
                Thread.sleep(9_000)
                return ExportResult("invoices/$jobId.pdf", "invoices/$jobId.xml", true, "kosit-ok", "sha-$jobId")
            }
        }

        val resultPublisher = ResultPublisher(NatsTestContainersExtension.jetStream())
        val consumer = newConsumer(pdfExportPort, resultPublisher).apply {
            // AP-M2 acceptance criterion (b)'s "simulated dead worker": a heartbeatExecutor whose
            // scheduleAtFixedRate is accepted (so handle() proceeds normally, exactly like a real
            // worker that registered its heartbeat) but which never actually invokes the
            // Runnable — no `message.inProgress()` call ever reaches the broker. This is the
            // control for the test above: identical slow handler, only the heartbeat differs.
            heartbeatExecutor = deadHeartbeatExecutor()
        }

        subscribe(consumer, jobsSubject, ackWaitSeconds = 3, maxDeliver = 5)
        publishJob(jobsSubject, jobId)

        // Poll for redelivery directly (exportAttempts >= 2) instead of waiting for the job to
        // finish - the whole point is to observe it *while the first delivery is still running*.
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline && exportAttempts.get() < 2) {
            Thread.sleep(200)
        }

        assertTrue(
            exportAttempts.get() >= 2,
            "without a working heartbeat, ackWait (3s) must lapse mid-processing and JetStream must redeliver - only got ${exportAttempts.get()} attempt(s)",
        )
    }

    // JobConsumer no longer takes pdfExportPort/resultPublisher/payloadExchange directly;
    // it delegates to PoisonMessageHandler/JobDispatcher, built here the same way CDI would wire
    // them in production. The consumer's OWN, real (default) heartbeatExecutor is left in place
    // by default — callers override it via .apply { heartbeatExecutor = ... } when needed.
    private fun newConsumer(pdfExportPort: PdfExportPort, resultPublisher: ResultPublisher): JobConsumer {
        val resultReporter = JobResultReporter(resultPublisher)
        val dispatcher = JobDispatcher(pdfExportPort, mock(), resultReporter)
        val poisonHandler = PoisonMessageHandler(resultPublisher, NatsTestContainersExtension.jetStream())
        return JobConsumer(NatsTestContainersExtension.connection, NatsTestContainersExtension.jetStream(), poisonHandler, dispatcher, mock())
    }

    /** A [ScheduledExecutorService] that accepts scheduling calls (so [JobConsumer.handle] takes
     *  its normal, heartbeat-registered code path) but never actually runs the scheduled
     *  Runnable - simulating a heartbeat mechanism that is wired up but has silently died. */
    private fun deadHeartbeatExecutor(): ScheduledExecutorService {
        val executor: ScheduledExecutorService = mock()
        val future: ScheduledFuture<*> = mock()
        org.mockito.kotlin.whenever(
            executor.scheduleAtFixedRate(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any())
        ).thenReturn(future)
        return executor
    }

    // AP-M4 — adapted from a push Dispatcher/PushSubscribeOptions subscription to the same
    // StreamContext -> ConsumerContext -> consume(ConsumeOptions, handler) pull API JobConsumer's
    // own subscribe() now uses, so this IT proves AP-M2's heartbeat/ackWait semantics still hold
    // under the new API, not just under the old push mechanism.
    private fun subscribe(consumer: JobConsumer, jobsSubject: String, ackWaitSeconds: Long, maxDeliver: Long) {
        val jsm = NatsTestContainersExtension.connection.jetStreamManagement()
        val durable = "heartbeat-test-${UUID.randomUUID()}"
        val config = ConsumerConfiguration.builder()
            .durable(durable)
            .filterSubject(jobsSubject)
            .ackWait(Duration.ofSeconds(ackWaitSeconds))
            .maxDeliver(maxDeliver)
            .build()
        jsm.addOrUpdateConsumer("TEST_STREAM", config)
        val consumerContext = NatsTestContainersExtension.jetStream().getStreamContext("TEST_STREAM").getConsumerContext(durable)
        // AP-M4 — batchSize must be > 1: at batchSize=1 the pull request's entire quota is met by
        // the single real message on first delivery, closing it immediately, with no room left to
        // hand this JVM the *same* message again once it becomes redeliverable (ackWait lapse) —
        // and jnats's own processed-message threshold can then never reopen it (its arithmetic
        // needs >=2 delivered-and-processed messages to cross, impossible with only one job in
        // flight). A batchSize with headroom keeps the request open, able to redeliver the same
        // still-outstanding message through it; a short explicit expiresIn (mirroring JobConsumer's
        // own subscribe(), see its `consumeExpiresInMillis` KDoc) bounds the request's own expiry
        // too, well inside this test's poll windows.
        val consumeOptions = ConsumeOptions.builder().batchSize(10).expiresIn(2_000).build()
        consumerContext.consume(consumeOptions) { msg -> consumer.receive(msg) }
    }

    private fun publishJob(jobsSubject: String, jobId: String) {
        val headers = Headers().add("jobId", jobId)
        val json = mapper.writeValueAsString(
            mapOf(
                "id" to jobId,
                "type" to "PDF_EXPORT",
                "correlationId" to UUID.randomUUID().toString(),
                "payload" to mapOf(
                    "invoice" to mapOf(
                        "invoiceNumber" to mapOf("value" to "INV-$jobId"),
                        "items" to emptyList<Any>(),
                    )
                ),
            )
        )
        NatsTestContainersExtension.jetStream().publish(jobsSubject, headers, json.toByteArray(Charsets.UTF_8))
    }

    private fun pollResult(expectedId: String, timeoutSeconds: Long): Map<String, Any?>? {
        val sub = NatsTestContainersExtension.jetStream().subscribe("document-job-results")
        try {
            val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
            while (System.currentTimeMillis() < deadline) {
                val msg = sub.nextMessage(Duration.ofMillis(300)) ?: continue
                msg.ack()
                val parsed: Map<String, Any?> = mapper.readValue(msg.data)
                if (parsed["id"] == expectedId) return parsed
            }
        } finally {
            sub.unsubscribe()
        }
        return null
    }
}
