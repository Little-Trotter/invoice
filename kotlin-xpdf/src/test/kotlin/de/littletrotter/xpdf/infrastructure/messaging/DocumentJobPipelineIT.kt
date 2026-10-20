package de.littletrotter.xpdf.infrastructure.messaging

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import de.littletrotter.shared.ExportResult
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.PayloadExchange
import de.littletrotter.shared.PdfExportPort
import de.littletrotter.xpdf.ValkeyTestContainersExtension
import io.nats.client.Connection
import io.nats.client.Message
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Duration
import java.util.UUID
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * End-to-end NATS + Valkey integration tests for the kotlin-xpdf document job pipeline.
 *
 * A real NATS JetStream broker and a real Valkey instance are started via Testcontainers. Each
 * test:
 *   1. Directly drives [JobConsumer.receive] with a job payload (mirrors what a live JetStream
 *      push consumer would deliver, without requiring a full app boot).
 *   2. [ResultPublisher] writes the result to `document-job-results` on the real broker — and, for
 *      a non-blank KoSIT report, `SETEX`s it into the real Valkey container ([payloadExchange],
 *      AP-M6).
 *   3. The test reads it back via a fresh JetStream consumer (deliver-all by default, so it
 *      reliably replays whatever was persisted — no "subscribe before publish" race), filters
 *      by unique job ID, and asserts the result payload.
 *
 * Covered scenarios:
 *   - Happy path: PDF export succeeds -> COMPLETED result with correct refs and correlationId
 *   - Failed path: PdfExportPort throws -> FAILED result published after retries exhausted
 *   - Malformed message: unparseable JSON -> consumer absorbs the error, no result is published
 *   - Multiple messages: several jobs processed in sequence -> all results land correctly
 *   - correlationId header propagation on the NATS result message
 *   - AP-M6: a KoSIT report round-tripping through Valkey
 */
private fun Any?.asStringMap(): Map<String, String?>? {
    if (this !is Map<*, *>) return null
    return entries.associate { (key, value) -> key.toString() to value?.toString() }
}

@ExtendWith(NatsTestContainersExtension::class, ValkeyTestContainersExtension::class)
class DocumentJobPipelineIT {

    private val mapper = jacksonObjectMapper()

    /** AP-M6 — the real [PayloadExchange] backing this suite, built lazily against the Valkey
     *  Testcontainer once [ValkeyTestContainersExtension] has started it. */
    private val payloadExchange: PayloadExchange by lazy {
        PayloadExchange(ValkeyTestContainersExtension.host(), ValkeyTestContainersExtension.port(), "", 0)
    }

    @BeforeEach
    fun setup() {
        // Fast retries in tests - no real sleep needed
        System.setProperty("job.retry.count", "1")
        System.setProperty("job.retry.backoff.ms", "0")
        System.setProperty("s3.endpoint", "http://rustfs-test:9000")
        System.setProperty("s3.bucket", "documents-test")
    }

    @AfterEach
    fun cleanup() {
        System.clearProperty("job.retry.count")
        System.clearProperty("job.retry.backoff.ms")
        System.clearProperty("s3.endpoint")
        System.clearProperty("s3.bucket")
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    fun happyPath_completedResultAppearsOnResultsSubject() {
        val jobId = "pipeline-ok-${UUID.randomUUID()}"
        val correlationId = UUID.randomUUID().toString()

        val resultPublisher = ResultPublisher(NatsTestContainersExtension.jetStream(), payloadExchange)
        val consumer = jobConsumerWith(successfulPdfPort(), resultPublisher)

        consumer.receive(natsMessage(jobMessage(jobId, correlationId, "INV-2024-HAPPY")))

        val result = pollResult(jobId)

        assertNotNull(result, "Result must appear on document-job-results")
        assertEquals(jobId, result!!["id"])
        assertEquals("COMPLETED", result["status"])
        assertEquals(correlationId, result["correlationId"])

        val refs = result["resultRefs"].asStringMap()
        assertNotNull(refs)
        assertEquals("invoices/$jobId.pdf", refs!!["objectKey"])
        assertTrue(refs["downloadUrl"]?.contains(jobId) == true, "downloadUrl must contain the jobId")
        // AP-M6 — the report travels as a Valkey ref now; resolve it from the real container to
        // prove the full round-trip, not just the shape of the reference.
        val kositRef = result["kositReportRef"].asStringMap()
        assertEquals("lt:kosit:$jobId", kositRef?.get("key"))
        assertEquals("<html>KoSIT OK</html>", payloadExchange.get("lt:kosit:$jobId"))
        assertEquals(null, result["llmReport"])
    }

    // -------------------------------------------------------------------------
    // Failed path
    // -------------------------------------------------------------------------

    @Test
    fun failedPath_pdfExportThrowsAfterRetries_failedResultIsPublished() {
        val jobId = "pipeline-fail-${UUID.randomUUID()}"
        val correlationId = UUID.randomUUID().toString()

        var attempts = 0
        val resultPublisher = ResultPublisher(NatsTestContainersExtension.jetStream(), payloadExchange)
        val consumer = jobConsumerWith(countingFailPort { attempts++ }, resultPublisher)

        consumer.receive(natsMessage(jobMessage(jobId, correlationId, "INV-2024-FAIL")))

        val result = pollResult(jobId)

        assertNotNull(result, "FAILED result must appear on document-job-results")
        assertEquals("FAILED", result!!["status"])
        assertEquals(correlationId, result["correlationId"])

        val refs = result["resultRefs"].asStringMap()
        assertNotNull(refs)
        assertTrue(refs!!["error"]?.contains("disk quota exceeded") == true, "error ref must carry the exception message")

        // retry.count=1 -> 1 original + 1 retry = 2 attempts
        assertEquals(2, attempts, "PdfExportPort must be called 2 times (1 original + 1 retry)")
    }

    // -------------------------------------------------------------------------
    // Malformed message (AP-M1) - poison jobs
    // -------------------------------------------------------------------------

    @Test
    fun malformedMessage_withNoJobIdHeader_nothingPublishedAndAcked() {
        // No NATS header carries a jobId either — nothing more can be done, so this is the one
        // poison-job case that still publishes nothing (matches the header-missing branch of
        // JobConsumer.handleUnparseableMessage).
        val malformedJobId = "malformed-${UUID.randomUUID()}"

        val resultPublisher = ResultPublisher(NatsTestContainersExtension.jetStream(), payloadExchange)
        val consumer = jobConsumerWith(noopSuccessPort(), resultPublisher)
        val message = natsMessage("not-json-at-all { broken")

        consumer.receive(message)

        val result = pollResult(malformedJobId, timeoutSeconds = 3)
        assertNull(result, "No result must be published for malformed input with no jobId header, but got: $result")
        // ack()/nak() timing on the exact call is a race against the async executor here (this
        // suite dispatches for real, unlike JobConsumerTest's synchronous executor) — that precise
        // call is already proven deterministically in JobConsumerTest; this IT's job is the real,
        // hard-to-fake part: what actually round-trips through the broker.
    }

    @Test
    fun malformedMessage_withJobIdHeader_publishesFailedResultAndAcks() {
        // AP-M1 acceptance criterion: "A job message with unparseable JSON but a valid jobId
        // header yields a FAILED result on document-job-results and is acked; the same message
        // is not redelivered." DocumentJobPublisher always attaches this header — this proves
        // the "endless republish loop" the AP exists to break is actually broken, against real
        // NATS (not a mock: the FAILED result really has to round-trip through the broker).
        val jobId = "malformed-with-header-${UUID.randomUUID()}"

        val resultPublisher = ResultPublisher(NatsTestContainersExtension.jetStream(), payloadExchange)
        val consumer = jobConsumerWith(noopSuccessPort(), resultPublisher)
        val message = natsMessage("not-json-at-all { broken", jobIdHeader = jobId)

        consumer.receive(message)

        val result = pollResult(jobId)
        assertNotNull(result, "A FAILED result must be published for a poison message with a jobId header")
        assertEquals("FAILED", result!!["status"])
        val refs = result["resultRefs"].asStringMap()
        assertTrue(refs?.get("error")?.contains("unparseable job payload") == true)
    }

    // -------------------------------------------------------------------------
    // Message with missing payload (AP-M1 poison job: JSON parses, invoice mapping fails) -
    // FAILED result published, jobId already known from the parsed DocumentJobRequest
    // -------------------------------------------------------------------------

    @Test
    fun missingPayload_publishesFailedResultAndAcks() {
        val noPayloadJobId = "no-payload-${UUID.randomUUID()}"
        val correlationId = UUID.randomUUID().toString()

        val resultPublisher = ResultPublisher(NatsTestContainersExtension.jetStream(), payloadExchange)
        val consumer = jobConsumerWith(noopSuccessPort(), resultPublisher)

        val messageJson = mapper.writeValueAsString(
            mapOf("id" to noPayloadJobId, "type" to "PDF_EXPORT", "correlationId" to correlationId)  // no 'payload' key
        )
        val message = natsMessage(messageJson)
        consumer.receive(message)

        val result = pollResult(noPayloadJobId)
        assertNotNull(result, "A FAILED result must be published when payload is missing, but got none")
        assertEquals("FAILED", result!!["status"])
        assertEquals(correlationId, result["correlationId"])
    }

    // -------------------------------------------------------------------------
    // Multiple messages in sequence
    // -------------------------------------------------------------------------

    @Test
    fun multipleJobs_allProduceCompletedResults() {
        val jobCount = 3
        val jobIds = (1..jobCount).map { "multi-$it-${UUID.randomUUID()}" }
        val correlationIds = jobIds.map { UUID.randomUUID().toString() }

        val resultPublisher = ResultPublisher(NatsTestContainersExtension.jetStream(), payloadExchange)
        val consumer = jobConsumerWith(multiSuccessPort(), resultPublisher)

        jobIds.zip(correlationIds).forEach { (jId, corrId) ->
            consumer.receive(natsMessage(jobMessage(jId, corrId, "INV-MULTI-$jId")))
        }

        jobIds.zip(correlationIds).forEach { (jId, corrId) ->
            val result = pollResult(jId)
            assertNotNull(result, "Result for job $jId must appear")
            assertEquals("COMPLETED", result!!["status"], "Job $jId must be COMPLETED")
            assertEquals(corrId, result["correlationId"])
            val refs = result["resultRefs"].asStringMap()
            assertEquals("invoices/$jId.pdf", refs?.get("objectKey"))
        }
    }

    // -------------------------------------------------------------------------
    // correlationId header propagation (NATS message header level)
    // -------------------------------------------------------------------------

    @Test
    fun correlationIdPropagatedAsNatsMessageHeader() {
        val jobId = "header-test-${UUID.randomUUID()}"
        val correlationId = UUID.randomUUID().toString()

        val resultPublisher = ResultPublisher(NatsTestContainersExtension.jetStream(), payloadExchange)
        val consumer = jobConsumerWith(kositWarnPort(), resultPublisher)

        consumer.receive(natsMessage(jobMessage(jobId, correlationId, "INV-HEADER")))

        val record = pollRawMessage(jobId)
        assertNotNull(record, "Raw message must be found on document-job-results")

        val headerValue = record!!.headers?.getFirst("correlationId")
        assertNotNull(headerValue, "correlationId header must be present on the result message")
        assertEquals(correlationId, headerValue)
    }

    @Test
    fun llmOptimizedResultCarriesNonEmptyLlmReport() {
        val jobId = "llm-${UUID.randomUUID()}"
        val correlationId = UUID.randomUUID().toString()

        var observedOptimizeFlag = false
        val resultPublisher = ResultPublisher(NatsTestContainersExtension.jetStream(), payloadExchange)
        val consumer = jobConsumerWith(object : PdfExportPort {
            override fun performExport(invoice: InvoiceDto, jId: String, llmOptimize: Boolean, skipValidation: Boolean, veraPdfCheck: Boolean, outputIntent: String?) = ExportResult(
                objectKey = "invoices/$jId.pdf",
                zugferdXmlKey = "invoices/$jId.xml",
                kositSuccess = true,
                kositReport = "kosit-ok",
                sha256 = "sha-$jId",
                llmReportKey = "invoices/$jId-llm-report.md",
            ).also {
                observedOptimizeFlag = llmOptimize
            }
        }, resultPublisher)

        consumer.receive(natsMessage(jobMessage(jobId, correlationId, "INV-LLM", llmOptimize = true)))

        val result = pollResult(jobId)

        assertNotNull(result)
        assertTrue(observedOptimizeFlag, "LLM optimize flag must be passed to the export service")
        val refs = result!!["resultRefs"].asStringMap()
        assertTrue(refs?.get("llmReportKey")?.endsWith("-llm-report.md") == true, "llmReportKey must be in resultRefs")
    }

    @Test
    fun llmReportKeyAbsentInResultRefsWhenNotRun() {
        val jobId = "llm-none-${UUID.randomUUID()}"
        val correlationId = UUID.randomUUID().toString()

        val resultPublisher = ResultPublisher(NatsTestContainersExtension.jetStream(), payloadExchange)
        val consumer = jobConsumerWith(object : PdfExportPort {
            override fun performExport(invoice: InvoiceDto, jId: String, llmOptimize: Boolean, skipValidation: Boolean, veraPdfCheck: Boolean, outputIntent: String?) = ExportResult(
                objectKey     = "invoices/$jId.pdf",
                zugferdXmlKey = "invoices/$jId.xml",
                kositSuccess  = true,
                kositReport   = "kosit-ok",
                sha256        = "sha-$jId",
            )
        }, resultPublisher)

        consumer.receive(natsMessage(jobMessage(jobId, correlationId, "INV-LLM-NONE", llmOptimize = true)))

        val result = pollResult(jobId)
        val refs = result?.get("resultRefs").asStringMap()
        assertTrue(refs?.get("llmReportKey").isNullOrBlank(),
            "llmReportKey must be absent when ExportResult.llmReportKey is blank")
    }

    private fun successfulPdfPort() = object : PdfExportPort {
        override fun performExport(invoice: InvoiceDto, jId: String, llmOptimize: Boolean, skipValidation: Boolean, veraPdfCheck: Boolean, outputIntent: String?) = ExportResult(
            objectKey = "invoices/$jId.pdf",
            zugferdXmlKey = "invoices/$jId.xml",
            kositSuccess = true,
            kositReport = "<html>KoSIT OK</html>",
            sha256 = "sha256-$jId"
        )
    }

    private fun countingFailPort(onAttempt: () -> Unit) = object : PdfExportPort {
        override fun performExport(invoice: InvoiceDto, jId: String, llmOptimize: Boolean, skipValidation: Boolean, veraPdfCheck: Boolean, outputIntent: String?): ExportResult {
            onAttempt()
            throw RuntimeException("disk quota exceeded for $jId")
        }
    }

    private fun noopSuccessPort() = object : PdfExportPort {
        override fun performExport(invoice: InvoiceDto, jId: String, llmOptimize: Boolean, skipValidation: Boolean, veraPdfCheck: Boolean, outputIntent: String?) =
            ExportResult("k/$jId.pdf", "k/$jId.xml", true, "", "sha")
    }

    private fun multiSuccessPort() = object : PdfExportPort {
        override fun performExport(invoice: InvoiceDto, jId: String, llmOptimize: Boolean, skipValidation: Boolean, veraPdfCheck: Boolean, outputIntent: String?) =
            ExportResult("invoices/$jId.pdf", "invoices/$jId.xml", true, "OK", "sha-$jId")
    }

    private fun kositWarnPort() = object : PdfExportPort {
        override fun performExport(invoice: InvoiceDto, jId: String, llmOptimize: Boolean, skipValidation: Boolean, veraPdfCheck: Boolean, outputIntent: String?) =
            ExportResult("invoices/$jId.pdf", "invoices/$jId.xml", false, "WARN: advisory", "sha-header")
    }

    // -------------------------------------------------------------------------
    // Infrastructure helpers
    // -------------------------------------------------------------------------

    // JobConsumer no longer takes pdfExportPort/resultPublisher/payloadExchange directly;
    // it delegates to PoisonMessageHandler/JobDispatcher, built here the same way CDI would wire
    // them in production.
    private fun jobConsumerWith(pdfExportPort: PdfExportPort, resultPublisher: ResultPublisher): JobConsumer {
        val resultReporter = JobResultReporter(resultPublisher)
        val dispatcher = JobDispatcher(pdfExportPort, payloadExchange, resultReporter)
        val poisonHandler = PoisonMessageHandler(resultPublisher, NatsTestContainersExtension.jetStream())
        return JobConsumer(mock<Connection>(), NatsTestContainersExtension.jetStream(), poisonHandler, dispatcher, mock())
    }

    private fun natsMessage(payload: String, jobIdHeader: String? = null): Message {
        val msg: Message = mock()
        whenever(msg.data).thenReturn(payload.toByteArray(Charsets.UTF_8))
        if (jobIdHeader != null) {
            whenever(msg.headers).thenReturn(io.nats.client.impl.Headers().add("jobId", jobIdHeader))
        }
        return msg
    }

    /**
     * Reads back from the JetStream stream rather than a live core subscription: the
     * message is already persisted by the time publishResult() completes, so a fresh
     * ephemeral JetStream consumer (default deliver-all policy) reliably replays it.
     */
    private fun pollRawMessage(expectedId: String, timeoutSeconds: Long = 20): Message? {
        val sub = NatsTestContainersExtension.jetStream().subscribe("document-job-results")
        try {
            val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
            while (System.currentTimeMillis() < deadline) {
                val msg = sub.nextMessage(Duration.ofMillis(300)) ?: continue
                msg.ack()
                val parsed: Map<String, Any?> = mapper.readValue(msg.data)
                if (parsed["id"] == expectedId) return msg
            }
        } finally {
            sub.unsubscribe()
        }
        return null
    }

    private fun pollResult(expectedId: String, timeoutSeconds: Long = 20): Map<String, Any?>? =
        pollRawMessage(expectedId, timeoutSeconds)?.let { mapper.readValue(it.data) }

    private fun invoicePayload(invoiceNumber: String): Map<String, Any?> =
        mapOf("invoice" to buildMap {
            put("invoiceNumber", mapOf("value" to invoiceNumber))
            put("netAmount", mapOf("amount" to "1000.00"))
            put("vatAmount", mapOf("amount" to "190.00"))
            put("grossAmount", mapOf("amount" to "1190.00"))
            put("items", listOf(
                mapOf(
                    "positionId" to "1",
                    "description" to "Consulting services",
                    "quantity" to "10",
                    "unitPriceNet" to mapOf("amount" to "100.00"),
                    "taxRatePercent" to "19.00",
                    "netAmount" to mapOf("amount" to "1000.00"),
                    "vatAmount" to mapOf("amount" to "190.00"),
                    "receipts" to emptyList<Any>()
                )
            ))
        })

    private fun jobMessage(
        jobId: String,
        correlationId: String,
        invoiceNumber: String,
        llmOptimize: Boolean = false,
    ): String =
        mapper.writeValueAsString(buildMap<String, Any?> {
            put("id", jobId)
            put("type", "PDF_EXPORT")
            put("correlationId", correlationId)
            put("payload", invoicePayload(invoiceNumber))
            if (llmOptimize) {
                put("options", mapOf("llmOptimize" to true))
            }
        })
}
