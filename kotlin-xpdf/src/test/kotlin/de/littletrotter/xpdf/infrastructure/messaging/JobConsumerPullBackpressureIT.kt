package de.littletrotter.xpdf.infrastructure.messaging

import org.mockito.kotlin.mock
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.littletrotter.shared.ExportResult
import de.littletrotter.shared.FacturXExportOutcome
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.PdfExportPort
import io.nats.client.ConsumeOptions
import io.nats.client.api.ConsumerConfiguration
import io.nats.client.impl.Headers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * AP-M4 acceptance criterion — "publish 100 jobs against a worker whose handler blocks; the
 * worker JVM holds at most `maxAckPending` messages ... the rest stays on the server."
 *
 * `job.worker.threads=4` -> `maxAckPending = 2 * workerThreads = 8` (JobConsumer.subscribe()'s own
 * formula, mirrored here). The [PdfExportPort] used blocks every attempt on a [CountDownLatch]
 * until the test releases it, so every one of the 100 published jobs would sit un-acked for the
 * whole test if the broker handed them all out at once — proving this needs a *real* JetStream
 * broker (a mock JetStream can't enforce `maxAckPending`), not just JobConsumer's own dispatch
 * logic.
 */
@ExtendWith(NatsTestContainersExtension::class)
class JobConsumerPullBackpressureIT {

    private val mapper = jacksonObjectMapper()

    @AfterEach
    fun cleanup() {
        System.clearProperty("job.worker.threads")
        System.clearProperty("job.retry.count")
        System.clearProperty("s3.endpoint")
        System.clearProperty("s3.bucket")
    }

    @Test
    fun `a worker whose handler blocks never holds more than maxAckPending messages, the rest stays on the server`() {
        System.setProperty("job.worker.threads", "4")
        System.setProperty("job.retry.count", "0")
        System.setProperty("s3.endpoint", "http://rustfs-test:9000")
        System.setProperty("s3.bucket", "documents-test")

        val jobsSubject = "document-jobs-backpressure-${UUID.randomUUID()}"
        val durable = "backpressure-test-${UUID.randomUUID()}"
        val maxAckPending = 8 // 2 * workerThreads(4), matching JobConsumer.subscribe()'s own formula

        val release = CountDownLatch(1)
        val startedAttempts = AtomicInteger(0)
        val blockingPort = object : PdfExportPort {
            override fun performExport(
                invoice: InvoiceDto, jobId: String, llmOptimize: Boolean, skipValidation: Boolean, veraPdfCheck: Boolean, outputIntent: String?,
            ): ExportResult {
                startedAttempts.incrementAndGet()
                release.await()
                return ExportResult("invoices/$jobId.pdf", "invoices/$jobId.xml", true, "kosit-ok", "sha-$jobId")
            }

            override fun performFacturXExport(
                invoice: InvoiceDto, jobId: String, llmOptimize: Boolean, skipValidation: Boolean, veraPdfCheck: Boolean, outputIntent: String?,
            ): FacturXExportOutcome =
                throw UnsupportedOperationException("Mustang-only test double; performFacturXExport was not expected to be called.")
        }

        val resultPublisher = ResultPublisher(NatsTestContainersExtension.jetStream())
        // Picks up job.worker.threads=4 from the system property set above at construction time —
        // the same bounded ThreadPoolExecutor (queue capacity 2*workerThreads, CallerRunsPolicy)
        // JobConsumer wires by default (AP-M4 §1).
        // JobConsumer no longer takes pdfExportPort/resultPublisher/payloadExchange
        // directly; it delegates to PoisonMessageHandler/JobDispatcher, built here the same way
        // CDI would wire them in production.
        val resultReporter = JobResultReporter(resultPublisher)
        val dispatcher = JobDispatcher(blockingPort, org.mockito.kotlin.mock(), resultReporter)
        val poisonHandler = PoisonMessageHandler(resultPublisher, NatsTestContainersExtension.jetStream())
        val consumer = JobConsumer(NatsTestContainersExtension.connection, NatsTestContainersExtension.jetStream(), poisonHandler, dispatcher, mock())

        val jsm = NatsTestContainersExtension.connection.jetStreamManagement()
        val config = ConsumerConfiguration.builder()
            .durable(durable)
            .filterSubject(jobsSubject)
            .ackWait(Duration.ofSeconds(30))
            .maxAckPending(maxAckPending.toLong())
            .build()
        jsm.addOrUpdateConsumer("TEST_STREAM", config)
        val consumerContext = NatsTestContainersExtension.jetStream().getStreamContext("TEST_STREAM").getConsumerContext(durable)
        val consumeOptions = ConsumeOptions.builder().batchSize(4).build() // batchSize = workerThreads
        val messageConsumer = consumerContext.consume(consumeOptions) { msg -> consumer.receive(msg) }

        try {
            repeat(100) { i -> publishJob(jobsSubject, "backpressure-job-$i") }

            // Wait until the worker is actually saturated (all 4 threads blocked in the handler)
            // before asserting — otherwise we might just be observing an empty startup window.
            val warmupDeadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < warmupDeadline && startedAttempts.get() < 4) {
                Thread.sleep(50)
            }
            assertTrue(startedAttempts.get() >= 4, "the worker must have picked up at least workerThreads(4) jobs, only got ${startedAttempts.get()}")

            // Poll numAckPending for a few seconds while 100 jobs are staged behind a blocked
            // worker — it must never exceed maxAckPending, proving the rest genuinely stayed
            // queued on the broker instead of being pulled into the JVM's heap (F8).
            var observedAny = false
            val pollDeadline = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < pollDeadline) {
                val info = jsm.getConsumerInfo("TEST_STREAM", durable)
                if (info.numAckPending > 0) observedAny = true
                assertTrue(
                    info.numAckPending <= maxAckPending,
                    "numAckPending must never exceed maxAckPending=$maxAckPending, was ${info.numAckPending}",
                )
                Thread.sleep(150)
            }
            assertTrue(observedAny, "test sanity: the consumer must have actually observed pending (unacked) messages at some point")
        } finally {
            release.countDown()
            messageConsumer.stop()
        }
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
}
