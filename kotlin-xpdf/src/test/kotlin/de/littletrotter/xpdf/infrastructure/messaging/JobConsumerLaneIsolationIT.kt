package de.littletrotter.xpdf.infrastructure.messaging

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.littletrotter.shared.ExportResult
import de.littletrotter.shared.FacturXExportOutcome
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.PdfExportPort
import io.nats.client.api.ConsumerConfiguration
import io.nats.client.impl.Headers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.mock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lane isolation against a real JetStream broker: while every bulk worker is blocked and bulk
 * messages sit unacked, a job published on the interactive subject must still reach the
 * interactive consumer within seconds. A mock cannot prove this, because the guarantee comes from
 * the broker handing each durable only its own filter subject and from separate worker pools.
 */
@ExtendWith(NatsTestContainersExtension::class)
class JobConsumerLaneIsolationIT {

    private val mapper = jacksonObjectMapper()

    @AfterEach
    fun cleanup() {
        System.clearProperty("job.retry.count")
        System.clearProperty("s3.endpoint")
        System.clearProperty("s3.bucket")
    }

    @Test
    fun `an interactive job is processed promptly while the bulk lane is saturated and blocked`() {
        System.setProperty("job.retry.count", "0")
        System.setProperty("s3.endpoint", "http://rustfs-test:9000")
        System.setProperty("s3.bucket", "documents-test")

        val suffix = UUID.randomUUID()
        val bulkLane = JobLane("bulk", "document-jobs-lane-bulk-$suffix", "lane-bulk-$suffix", workerThreads = 2)
        val interactiveLane = JobLane("interactive", "document-jobs-lane-interactive-$suffix", "lane-interactive-$suffix", workerThreads = 1)

        val release = CountDownLatch(1)
        val bulkStarted = AtomicInteger(0)
        val interactiveDone = CountDownLatch(1)
        val bulkConsumer = consumerFor(bulkLane, port { bulkStarted.incrementAndGet(); release.await() })
        val interactiveConsumer = consumerFor(interactiveLane, port { interactiveDone.countDown() })

        val stoppers = listOf(bulkLane, interactiveLane).zip(listOf(bulkConsumer, interactiveConsumer)).map { (lane, consumer) ->
            NatsTestContainersExtension.connection.jetStreamManagement().addOrUpdateConsumer(
                "TEST_STREAM",
                ConsumerConfiguration.builder()
                    .durable(lane.durableName)
                    .filterSubject(lane.subject)
                    .ackWait(Duration.ofSeconds(30))
                    .maxAckPending(2L * lane.workerThreads)
                    .build(),
            )
            NatsTestContainersExtension.jetStream().getStreamContext("TEST_STREAM")
                .getConsumerContext(lane.durableName)
                .consume(consumer.consumeOptions()) { msg -> consumer.receive(msg) }
        }

        try {
            repeat(30) { publishJob(bulkLane.subject, "bulk-job-$it") }
            val saturationDeadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < saturationDeadline && bulkStarted.get() < bulkLane.workerThreads) Thread.sleep(50)
            assertTrue(bulkStarted.get() >= bulkLane.workerThreads, "bulk lane must be saturated, started=${bulkStarted.get()}")

            publishJob(interactiveLane.subject, "interactive-job-1")

            assertTrue(
                interactiveDone.await(5, TimeUnit.SECONDS),
                "the interactive job must be processed while all bulk workers are blocked",
            )
            val bulkInfo = NatsTestContainersExtension.connection.jetStreamManagement().getConsumerInfo("TEST_STREAM", bulkLane.durableName)
            assertTrue(bulkInfo.numAckPending <= 2L * bulkLane.workerThreads, "bulk numAckPending=${bulkInfo.numAckPending} exceeds its own maxAckPending")
        } finally {
            release.countDown()
            stoppers.forEach { it.stop() }
        }
    }

    private fun port(onExport: () -> Unit) = object : PdfExportPort {
        override fun performExport(
            invoice: InvoiceDto, jobId: String, llmOptimize: Boolean, skipValidation: Boolean, veraPdfCheck: Boolean, outputIntent: String?,
        ): ExportResult {
            onExport()
            return ExportResult("invoices/$jobId.pdf", "invoices/$jobId.xml", true, "kosit-ok", "sha-$jobId")
        }

        override fun performFacturXExport(
            invoice: InvoiceDto, jobId: String, llmOptimize: Boolean, skipValidation: Boolean, veraPdfCheck: Boolean, outputIntent: String?,
        ): FacturXExportOutcome =
            throw UnsupportedOperationException("Mustang-only test double; performFacturXExport was not expected to be called.")
    }

    private fun consumerFor(lane: JobLane, port: PdfExportPort): JobConsumer {
        val resultPublisher = ResultPublisher(NatsTestContainersExtension.jetStream())
        val dispatcher = JobDispatcher(port, mock(), JobResultReporter(resultPublisher))
        val poisonHandler = PoisonMessageHandler(resultPublisher, NatsTestContainersExtension.jetStream())
        return JobConsumer(NatsTestContainersExtension.connection, NatsTestContainersExtension.jetStream(), poisonHandler, dispatcher, mock(), lane)
    }

    private fun publishJob(subject: String, jobId: String) {
        val json = mapper.writeValueAsString(
            mapOf(
                "id" to jobId,
                "type" to "PDF_EXPORT",
                "correlationId" to UUID.randomUUID().toString(),
                "payload" to mapOf(
                    "invoice" to mapOf(
                        "invoiceNumber" to mapOf("value" to "INV-$jobId"),
                        "items" to emptyList<Any>(),
                    ),
                ),
            ),
        )
        NatsTestContainersExtension.jetStream().publish(subject, Headers().add("jobId", jobId), json.toByteArray(Charsets.UTF_8))
    }
}
