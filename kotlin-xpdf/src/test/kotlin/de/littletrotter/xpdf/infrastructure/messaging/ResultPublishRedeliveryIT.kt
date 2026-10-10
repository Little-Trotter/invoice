package de.littletrotter.xpdf.infrastructure.messaging

import org.mockito.kotlin.mock
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import de.littletrotter.shared.ExportResult
import de.littletrotter.shared.FacturXExportOutcome
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.PdfExportPort
import io.nats.client.ConsumeOptions
import io.nats.client.PublishOptions
import io.nats.client.api.ConsumerConfiguration
import io.nats.client.impl.Headers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import java.io.IOException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * AP-M1 acceptance criterion — "With NATS temporarily
 * unreachable at result-publish time ..., the job message is redelivered and the result
 * eventually arrives — no silent loss."
 *
 * The spec's own parenthetical names "Testcontainers pause" as the technique. That is not used
 * here: `NatsTestContainersExtension`'s broker is a single, `withReuse(true)` container shared by
 * the whole suite (and, via Testcontainers reuse, across separate `mvn verify` runs on a
 * developer machine) — pausing it has no reliable way to land exactly during the result-publish
 * call without racing this test's own synchronous export/dispatch, and a pause left stuck by a
 * failing assertion would corrupt every later test in the module.
 *
 * Instead, a Mockito spy wraps the *real* `JetStream` and fails only the first few calls to
 * `publish("document-job-results", ...)` (matching [ResultPublisher]'s own retry budget) —
 * deterministically
 * reproducing "NATS unreachable for the whole retry budget" without touching the broker process.
 * Everything downstream of that simulated failure is real: [ResultPublisher]'s own retry/backoff,
 * the `nak()`, JetStream's own redelivery, the second delivery's export re-run, and the eventual
 * result fetched back off the real broker.
 */
@ExtendWith(NatsTestContainersExtension::class)
class ResultPublishRedeliveryIT {

    private val mapper = jacksonObjectMapper()

    /** Mirrors [ResultPublisher]'s own private `MAX_ATTEMPTS` (AP-M1: "up to 3 attempts"). Kept as
     *  a literal here rather than reflection — this test only
     *  needs "fail the whole retry budget", not to track the exact constant. */
    private val maxResultPublishAttempts = 3

    @AfterEach
    fun cleanup() {
        System.clearProperty("job.retry.count")
        System.clearProperty("s3.endpoint")
        System.clearProperty("s3.bucket")
    }

    @Test
    fun `NATS unreachable for the whole publish retry budget redelivers the job and the result eventually arrives`() {
        // job.retry.count now also sizes JobConsumer's own redelivery-poison
        // budget (deliveredCount > retryCount + 1 is treated as poison, see
        // JobConsumer.isExcessivelyRedelivered). This test's whole point is a SECOND delivery:
        // the first one burns ResultPublisher's whole in-JVM retry budget on the simulated
        // NATS outage and is nak'd, and the redelivery carries the recovery (export re-run and a
        // successful COMPLETED publish). "0" would shrink the budget to 1 and make that
        // recovery delivery read as already-poison — short-circuiting to a FAILED result
        // (Observed exactly that in the Forgejo CI, run 41). Same coupling-fix pattern as the
        // dead-heartbeat IT (commit 824dd9629); a generous budget stays out of the way, exactly
        // as production's own default (3) would.
        System.setProperty("job.retry.count", "5")
        System.setProperty("s3.endpoint", "http://rustfs-test:9000")
        System.setProperty("s3.bucket", "documents-test")

        val jobId = "redelivery-${UUID.randomUUID()}"
        val correlationId = UUID.randomUUID().toString()
        val jobsSubject = "document-jobs-redelivery-${UUID.randomUUID()}"

        val exportAttempts = AtomicInteger(0)
        val pdfExportPort = object : PdfExportPort {
            override fun performExport(
                invoice: InvoiceDto, jobId: String, llmOptimize: Boolean, skipValidation: Boolean, veraPdfCheck: Boolean, outputIntent: String?,
            ): ExportResult {
                exportAttempts.incrementAndGet()
                return ExportResult("invoices/$jobId.pdf", "invoices/$jobId.xml", true, "kosit-ok", "sha-$jobId")
            }

            override fun performFacturXExport(
                invoice: InvoiceDto, jobId: String, llmOptimize: Boolean, skipValidation: Boolean, veraPdfCheck: Boolean, outputIntent: String?,
            ): FacturXExportOutcome =
                throw UnsupportedOperationException("Mustang-only test double; performFacturXExport was not expected to be called.")
        }

        val realJetStream = NatsTestContainersExtension.jetStream()
        val publishCallCount = AtomicInteger(0)
        val flakyJetStream = Mockito.spy(realJetStream)
        Mockito.doAnswer { invocation ->
            val subject = invocation.getArgument<String>(0)
            if (subject == "document-job-results" && publishCallCount.getAndIncrement() < maxResultPublishAttempts) {
                throw IOException("simulated NATS outage, publish attempt ${publishCallCount.get()}")
            }
            invocation.callRealMethod()
        }.`when`(flakyJetStream).publish(eq("document-job-results"), any<Headers>(), any<ByteArray>(), any<PublishOptions>())

        val resultPublisher = ResultPublisher(flakyJetStream)
        // JobConsumer no longer takes pdfExportPort/resultPublisher/payloadExchange
        // directly; it delegates to PoisonMessageHandler/JobDispatcher, built here the same way
        // CDI would wire them in production.
        val resultReporter = JobResultReporter(resultPublisher)
        val dispatcher = JobDispatcher(pdfExportPort, Mockito.mock(de.littletrotter.shared.PayloadExchange::class.java), resultReporter)
        val poisonHandler = PoisonMessageHandler(resultPublisher, realJetStream)
        val consumer = JobConsumer(NatsTestContainersExtension.connection, realJetStream, poisonHandler, dispatcher, mock()).apply {
            executor = Executor { it.run() }
        }

        // AP-M4 — adapted from a push Dispatcher/PushSubscribeOptions subscription to the same
        // StreamContext -> ConsumerContext -> consume(ConsumeOptions, handler) pull API
        // JobConsumer's own subscribe() now uses.
        val jsm = NatsTestContainersExtension.connection.jetStreamManagement()
        val durable = "redelivery-test-${UUID.randomUUID()}"
        val config = ConsumerConfiguration.builder()
            .durable(durable)
            .filterSubject(jobsSubject)
            .ackWait(Duration.ofSeconds(15))
            .build()
        jsm.addOrUpdateConsumer("TEST_STREAM", config)
        val consumerContext = realJetStream.getStreamContext("TEST_STREAM").getConsumerContext(durable)
        // AP-M4 — batchSize must be > 1 here too (see JobConsumerHeartbeatIT's `subscribe` helper
        // for the full reasoning): batchSize=1 would close the pull request on the first delivery
        // with no room left to redeliver the same nak'd message through it.
        val consumeOptions = ConsumeOptions.builder().batchSize(10).expiresIn(2_000).build()
        consumerContext.consume(consumeOptions) { msg -> consumer.receive(msg) }

        val jobHeaders = Headers().add("jobId", jobId)
        val jobJson = mapper.writeValueAsString(
            mapOf(
                "id" to jobId,
                "type" to "PDF_EXPORT",
                "correlationId" to correlationId,
                "payload" to mapOf(
                    "invoice" to mapOf(
                        "invoiceNumber" to mapOf("value" to "INV-REDELIVERY"),
                        "items" to emptyList<Any>(),
                    )
                ),
            )
        )
        realJetStream.publish(jobsSubject, jobHeaders, jobJson.toByteArray(Charsets.UTF_8))

        val result = pollResult(jobId, timeoutSeconds = 20)

        assertNotNull(result, "Result must eventually arrive despite the outage during the first delivery's publish attempts")
        assertEquals("COMPLETED", result!!["status"])
        assertEquals(correlationId, result["correlationId"])
        assertTrue(exportAttempts.get() >= 2, "export must run again on redelivery: got ${exportAttempts.get()} attempt(s)")
        assertTrue(
            publishCallCount.get() >= maxResultPublishAttempts + 1,
            "expected the $maxResultPublishAttempts failing attempts plus at least 1 succeeding attempt, got ${publishCallCount.get()}",
        )
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
