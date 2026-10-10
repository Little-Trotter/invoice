package de.littletrotter.xpdf.infrastructure.messaging

import org.mockito.kotlin.mock
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import de.littletrotter.shared.ExportResult
import de.littletrotter.shared.FacturXExportOutcome
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.PdfExportPort
import io.nats.client.Message
import io.nats.client.api.ConsumerConfiguration
import io.nats.client.impl.Headers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Duration
import java.util.UUID

/**
 * AP-M4 acceptance criterion — "Throughput smoke: 100 trivial jobs complete not slower than a
 * reasonable budget." Proves the pull-consumer migration (batched pulls + a bounded executor with
 * `CallerRunsPolicy`, replacing the old unbounded push dispatch) doesn't introduce a material
 * processing slowdown or deadlock for the common case of fast jobs.
 */
@ExtendWith(NatsTestContainersExtension::class)
class JobConsumerPullThroughputIT {

    private val mapper = jacksonObjectMapper()

    /** Generous on purpose — this is a regression smoke test against flakiness on a loaded CI
     *  box, not a tight performance benchmark. 100 trivial (no-op, no real I/O) jobs across a
     *  4-thread pull consumer should complete in well under this on any reasonable machine. */
    private val throughputBudgetMs = 30_000L

    @AfterEach
    fun cleanup() {
        System.clearProperty("job.worker.threads")
        System.clearProperty("job.retry.count")
        System.clearProperty("s3.endpoint")
        System.clearProperty("s3.bucket")
    }

    @Test
    fun `100 trivial jobs complete within a reasonable throughput budget under the pull consumer`() {
        assertThroughput(workerThreads = 4)
    }

    /**
     * The heap-derived worker-thread default yields 1 on a small container. jnats never re-pulls at
     * `batchSize = 1`, so consumption used to fall back to one job per heartbeat alarm (~30 s) —
     * 100 jobs would miss this budget by orders of magnitude without [JobConsumer]'s pull-batch floor.
     */
    @Test
    fun `a single worker thread keeps pulling continuously instead of once per heartbeat alarm`() {
        assertThroughput(workerThreads = 1)
    }

    private fun assertThroughput(workerThreads: Int) {
        System.setProperty("job.worker.threads", workerThreads.toString())
        System.setProperty("job.retry.count", "0")
        System.setProperty("s3.endpoint", "http://rustfs-test:9000")
        System.setProperty("s3.bucket", "documents-test")

        val jobsSubject = "document-jobs-throughput-${UUID.randomUUID()}"
        val durable = "throughput-test-${UUID.randomUUID()}"

        val trivialPort = object : PdfExportPort {
            override fun performExport(
                invoice: InvoiceDto, jobId: String, llmOptimize: Boolean, skipValidation: Boolean, veraPdfCheck: Boolean, outputIntent: String?,
            ) = ExportResult("invoices/$jobId.pdf", "invoices/$jobId.xml", true, "kosit-ok", "sha-$jobId")

            override fun performFacturXExport(
                invoice: InvoiceDto, jobId: String, llmOptimize: Boolean, skipValidation: Boolean, veraPdfCheck: Boolean, outputIntent: String?,
            ): FacturXExportOutcome =
                throw UnsupportedOperationException("Mustang-only test double; performFacturXExport was not expected to be called.")
        }

        val resultPublisher = ResultPublisher(NatsTestContainersExtension.jetStream())
        // JobConsumer no longer takes pdfExportPort/resultPublisher/payloadExchange
        // directly; it delegates to PoisonMessageHandler/JobDispatcher, built here the same way
        // CDI would wire them in production.
        val resultReporter = JobResultReporter(resultPublisher)
        val dispatcher = JobDispatcher(trivialPort, org.mockito.kotlin.mock(), resultReporter)
        val poisonHandler = PoisonMessageHandler(resultPublisher, NatsTestContainersExtension.jetStream())
        val consumer = JobConsumer(NatsTestContainersExtension.connection, NatsTestContainersExtension.jetStream(), poisonHandler, dispatcher, mock())

        val jsm = NatsTestContainersExtension.connection.jetStreamManagement()
        val config = ConsumerConfiguration.builder()
            .durable(durable)
            .filterSubject(jobsSubject)
            .ackWait(Duration.ofSeconds(30))
            .maxAckPending(2L * workerThreads)
            .build()
        jsm.addOrUpdateConsumer("TEST_STREAM", config)
        val consumerContext = NatsTestContainersExtension.jetStream().getStreamContext("TEST_STREAM").getConsumerContext(durable)
        val messageConsumer = consumerContext.consume(consumer.consumeOptions()) { msg -> consumer.receive(msg) }

        try {
            val jobIds = (1..100).map { "throughput-job-$it-${UUID.randomUUID()}" }

            val start = System.currentTimeMillis()
            jobIds.forEach { publishJob(jobsSubject, it) }

            val results = pollAllResults(jobIds.toSet(), timeoutSeconds = 30)
            val elapsedMs = System.currentTimeMillis() - start

            jobIds.forEach { jobId ->
                val result = results[jobId]
                assertNotNull(result, "result for $jobId must appear on document-job-results")
                assertEquals("COMPLETED", result!!["status"])
            }

            assertTrue(
                elapsedMs < throughputBudgetMs,
                "100 trivial jobs must complete within the ${throughputBudgetMs}ms throughput budget, took ${elapsedMs}ms",
            )
        } finally {
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

    /**
     * Reads back from the JetStream stream rather than a live core subscription: the message is
     * already persisted by the time publishResult() completes, so a fresh ephemeral JetStream
     * consumer (default deliver-all policy) reliably replays it — same underlying technique as
     * DocumentJobPipelineIT/JobConsumerHeartbeatIT's own single-id `pollResult` helpers, but
     * collecting all [expectedIds] in one pass instead of re-scanning the whole (shared,
     * test-container-wide) results stream once per job — the O(n) vs. O(n^2) difference matters
     * once n is 100, both for wall-clock time and for not skewing the throughput measurement
     * itself with redundant re-reads.
     */
    private fun pollAllResults(expectedIds: Set<String>, timeoutSeconds: Long): Map<String, Map<String, Any?>> {
        val found = mutableMapOf<String, Map<String, Any?>>()
        val sub = NatsTestContainersExtension.jetStream().subscribe("document-job-results")
        try {
            val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
            while (System.currentTimeMillis() < deadline && found.size < expectedIds.size) {
                val msg: Message = sub.nextMessage(Duration.ofMillis(300)) ?: continue
                msg.ack()
                val parsed: Map<String, Any?> = mapper.readValue(msg.data)
                val id = parsed["id"] as? String
                if (id != null && id in expectedIds) found[id] = parsed
            }
        } finally {
            sub.unsubscribe()
        }
        return found
    }
}
