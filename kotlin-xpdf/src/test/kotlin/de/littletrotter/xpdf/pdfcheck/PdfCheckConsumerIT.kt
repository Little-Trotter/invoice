package de.littletrotter.xpdf.pdfcheck

import com.sun.net.httpserver.HttpServer
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.documentjob.DocumentJobSubjects
import de.littletrotter.shared.documentjob.DocumentJobWireCodec
import de.littletrotter.shared.documentjob.PdfCheckRequest
import de.littletrotter.shared.documentjob.PdfCheckResult
import de.littletrotter.xpdf.impl.VeraPdfRestClient
import de.littletrotter.xpdf.infrastructure.messaging.NatsTestContainersExtension
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.nats.client.MessageConsumer
import io.nats.client.PullSubscribeOptions
import io.nats.client.api.AckPolicy
import io.nats.client.api.ConsumerConfiguration
import io.nats.client.api.DeliverPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The check round trip against a real broker: a request on the check subject becomes a result on
 * the result subject, and a service that keeps failing ends in an `ERROR` result after the last
 * delivery. The veraPDF service is a local HTTP stand-in; the documents come from a fixed store.
 */
@ExtendWith(NatsTestContainersExtension::class)
class PdfCheckConsumerIT {

    private val pdf = "%PDF-1.7 synthetic".toByteArray()
    private val sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf))
    private var server: HttpServer? = null
    private var consuming: MessageConsumer? = null
    private var durable: String? = null

    private val store = object : DocumentStore {
        override fun put(objectKey: String, data: ByteArray, contentType: String) = Unit
        override fun get(objectKey: String): ByteArray = pdf
        override fun exists(objectKey: String) = true
        override fun enableImmutability(objectKey: String) = Unit
        override fun delete(objectKey: String) = Unit
    }

    @AfterEach
    fun stopServerAndConsumer() {
        // A consumer left running would also pick up the next test's request and call a dead service.
        consuming?.stop()
        durable?.let { NatsTestContainersExtension.connection.jetStreamManagement().deleteConsumer("TEST_STREAM", it) }
        server?.stop(0)
    }

    private fun serviceAnswering(status: Int, body: String, calls: AtomicInteger): String {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/api/validate") { exchange ->
            calls.incrementAndGet()
            exchange.requestBody.readAllBytes()
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.start()
        server = http
        return "http://127.0.0.1:${http.address.port}"
    }

    private fun startConsumer(baseUrl: String) {
        val jsm = NatsTestContainersExtension.connection.jetStreamManagement()
        val durable = "pdfcheck-it-${UUID.randomUUID()}".also { this.durable = it }
        jsm.addOrUpdateConsumer(
            "TEST_STREAM",
            ConsumerConfiguration.builder()
                .durable(durable)
                .filterSubject(DocumentJobSubjects.PDF_CHECKS)
                .ackPolicy(AckPolicy.Explicit)
                // The shared test stream keeps the earlier tests' requests; this test sees only its own.
                .deliverPolicy(DeliverPolicy.New)
                .ackWait(Duration.ofSeconds(30))
                .maxDeliver(PdfCheckConsumer.MAX_DELIVER.toLong())
                .build(),
        )
        val processor = PdfCheckProcessor(store, VeraPdfRestClient(baseUrl = baseUrl, timeoutSeconds = 5), SimpleMeterRegistry())
        val consumer = PdfCheckConsumer(NatsTestContainersExtension.connection, NatsTestContainersExtension.jetStream(), processor)
        consumer.retryDelay = Duration.ofMillis(200)
        consuming = NatsTestContainersExtension.jetStream().getStreamContext("TEST_STREAM").getConsumerContext(durable)
            .consume(consumer.consumeOptions()) { consumer.receive(it) }
    }

    private fun publishRequest(jobId: String) {
        val request = PdfCheckRequest(checkId = "verapdf-$jobId", jobId = jobId, objectKey = "inv/$jobId.pdf", sha256 = sha256)
        NatsTestContainersExtension.jetStream()
            .publish(DocumentJobSubjects.PDF_CHECKS, DocumentJobWireCodec.encodePdfCheckRequest(request).toByteArray())
    }

    private fun awaitResult(jobId: String): PdfCheckResult {
        val subscription = NatsTestContainersExtension.jetStream().subscribe(
            DocumentJobSubjects.PDF_CHECK_RESULTS,
            PullSubscribeOptions.builder().stream("TEST_STREAM").build(),
        )
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (System.nanoTime() < deadline) {
            for (message in subscription.fetch(1, Duration.ofSeconds(1))) {
                message.ack()
                val result = DocumentJobWireCodec.decodePdfCheckResult(String(message.data))
                if (result.jobId == jobId) return result
            }
        }
        error("no PDF check result for job $jobId within 30 s")
    }

    @Test
    fun `a request becomes a verdict on the result subject`() {
        val calls = AtomicInteger()
        val compliant = """{"report":{"jobs":[{"validationResult":{"profileName":"PDF/A-3B validation profile","compliant":true,"details":{"failedRules":0,"failedChecks":0,"ruleSummaries":[]}}}]}}"""
        startConsumer(serviceAnswering(200, compliant, calls))
        val jobId = "it-${UUID.randomUUID()}"

        publishRequest(jobId)

        val result = awaitResult(jobId)
        assertEquals("VALID", result.outcome)
        assertEquals("verapdf-$jobId", result.checkId)
        assertEquals(sha256, result.sha256)
        assertEquals(1, calls.get())
    }

    @Test
    fun `a service that keeps failing ends in an engine error after the last delivery`() {
        val calls = AtomicInteger()
        startConsumer(serviceAnswering(500, "boom", calls))
        val jobId = "it-${UUID.randomUUID()}"

        publishRequest(jobId)

        val result = awaitResult(jobId)
        assertEquals("ERROR", result.outcome)
        assertNotNull(result.engineError)
        assertTrue(result.engineError!!.contains("HTTP 500"), "was: ${result.engineError}")
        assertEquals(PdfCheckConsumer.MAX_DELIVER, calls.get(), "one attempt per delivery")
    }
}
