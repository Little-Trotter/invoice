package de.littletrotter.xpdf.infrastructure.messaging

import de.littletrotter.shared.ExportResult
import de.littletrotter.shared.FacturXExportOutcome
import de.littletrotter.shared.PdfExportPort
import de.littletrotter.shared.ValidationMessage
import io.nats.client.ConsumeOptions
import io.nats.client.Connection
import io.nats.client.ConsumerContext
import io.nats.client.JetStream
import io.nats.client.JetStreamManagement
import io.nats.client.Message
import io.nats.client.MessageHandler
import io.nats.client.StreamContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.times
import org.mockito.kotlin.whenever
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class JobConsumerTest {

    @AfterEach
    fun clearSystemProperties() {
        System.clearProperty("job.retry.count")
        System.clearProperty("job.retry.backoff.ms")
        System.clearProperty("s3.endpoint")
        System.clearProperty("s3.bucket")
        System.clearProperty("job.worker.threads")
        System.clearProperty("nats.consumer.ack-wait-seconds")
    }

    @Test
    fun `worker thread count is read from the system property when set`() {
        System.setProperty("job.worker.threads", "1")
        val consumer = JobConsumer(mock(), mock(), mock(), mock(), mock())
        assertNotNull(consumer.executor)
    }

    @Test
    fun `receive publishes a FAILED result and acks when payload is missing (AP-M1 poison job)`() {
        // The JSON parses fine (job.id="job-1" is known), so this exercises
        // handleUnmappableInvoice, not handleUnparseableMessage — no NATS header needed, unlike
        // the sibling "unparseable JSON" tests below.
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        val message = message(
            """
                {"id":"job-1","type":"GENERATE","correlationId":"corr-missing"}
            """.trimIndent()
        )
        val consumer = newConsumer(pdfExportPort, resultPublisher)

        consumer.receive(message)

        verify(pdfExportPort, never()).performExport(any(), any(), any(), any(), any(), anyOrNull())
        val errorCaptor = org.mockito.kotlin.argumentCaptor<Map<String, String>?>()
        verify(resultPublisher).publishResult(eq("job-1"), eq("FAILED"), errorCaptor.capture(), eq(""), eq("corr-missing"), anyOrNull())
        assertTrue(errorCaptor.firstValue?.get("error")?.contains("unmappable job payload") == true)
        verify(message).ack()
        verify(message, never()).nak()
    }

    // ── AP-M1 — poison jobs: unparseable JSON ────

    @Test
    fun `receive returns early for invalid json with no jobId header logs and acks without publishing`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        val message = message("not-json")
        val consumer = newConsumer(pdfExportPort, resultPublisher)

        consumer.receive(message)

        verify(pdfExportPort, never()).performExport(any(), any(), any(), any(), any(), anyOrNull())
        verify(resultPublisher, never()).publishResult(any(), any(), any(), any(), any(), anyOrNull())
        verify(message).ack()
        verify(message, never()).nak()
    }

    @Test
    fun `an unparseable json message with a jobId header publishes a FAILED result and acks`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        val message = message("not-json-at-all { broken")
        val headers = io.nats.client.impl.Headers().add("jobId", "poison-job-1")
        whenever(message.headers).thenReturn(headers)
        val consumer = newConsumer(pdfExportPort, resultPublisher)

        consumer.receive(message)

        verify(pdfExportPort, never()).performExport(any(), any(), any(), any(), any(), anyOrNull())
        val errorCaptor = org.mockito.kotlin.argumentCaptor<Map<String, String>?>()
        verify(resultPublisher).publishResult(eq("poison-job-1"), eq("FAILED"), errorCaptor.capture(), eq(""), isNull(), anyOrNull())
        assertTrue(errorCaptor.firstValue?.get("error")?.contains("unparseable job payload") == true)
        verify(message).ack()
        verify(message, never()).nak()
    }

    @Test
    fun `a poison job whose FAILED publish also fails is still acked, not redelivered forever`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(resultPublisher.publishResult(eq("poison-job-pubfail"), eq("FAILED"), any(), any(), anyOrNull(), anyOrNull()))
            .thenThrow(RuntimeException("nats down"))
        val message = message("not-json-at-all { broken")
        val headers = io.nats.client.impl.Headers().add("jobId", "poison-job-pubfail")
        whenever(message.headers).thenReturn(headers)
        val consumer = newConsumer(pdfExportPort, resultPublisher)

        consumer.receive(message)

        verify(message).ack()
        verify(message, never()).nak()
    }

    // ── AP-M3 §4 — poison messages also land raw on the DLQ, alongside the FAILED result ────────

    @Test
    fun `an unmappable invoice poison job also publishes its raw bytes to the DLQ with jobId, reason and origSubject headers`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        val jetStream: JetStream = mock()
        val payload = """{"id":"job-dlq-1","type":"GENERATE","correlationId":"corr-dlq-1"}"""
        val message = message(payload, subject = "document-jobs")
        val consumer = newConsumer(pdfExportPort, resultPublisher, jetStream)

        consumer.receive(message)

        val headersCaptor = org.mockito.kotlin.argumentCaptor<io.nats.client.impl.Headers>()
        val bytesCaptor = org.mockito.kotlin.argumentCaptor<ByteArray>()
        verify(jetStream).publish(eq("document-jobs-dlq"), headersCaptor.capture(), bytesCaptor.capture())
        assertTrue(bytesCaptor.firstValue.toString(Charsets.UTF_8) == payload)
        assertTrue(headersCaptor.firstValue.getFirst("jobId") == "job-dlq-1")
        assertTrue(headersCaptor.firstValue.getFirst("origSubject") == "document-jobs")
        assertTrue(headersCaptor.firstValue.getFirst("reason")?.contains("unmappable job payload") == true)
        verify(message).ack()
    }

    @Test
    fun `an unparseable poison job also publishes its raw bytes to the DLQ`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        val jetStream: JetStream = mock()
        val message = message("not-json-at-all { broken", subject = "document-jobs")
        val headers = io.nats.client.impl.Headers().add("jobId", "poison-dlq-2")
        whenever(message.headers).thenReturn(headers)
        val consumer = newConsumer(pdfExportPort, resultPublisher, jetStream)

        consumer.receive(message)

        val dlqHeadersCaptor = org.mockito.kotlin.argumentCaptor<io.nats.client.impl.Headers>()
        verify(jetStream).publish(eq("document-jobs-dlq"), dlqHeadersCaptor.capture(), any<ByteArray>())
        assertTrue(dlqHeadersCaptor.firstValue.getFirst("jobId") == "poison-dlq-2")
        assertTrue(dlqHeadersCaptor.firstValue.getFirst("reason")?.contains("unparseable job payload") == true)
        verify(message).ack()
    }

    @Test
    fun `a DLQ publish failure for a poison job is logged but still acks`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        val jetStream: JetStream = mock()
        whenever(jetStream.publish(eq("document-jobs-dlq"), any<io.nats.client.impl.Headers>(), any<ByteArray>()))
            .thenThrow(RuntimeException("dlq down"))
        val payload = """{"id":"job-dlq-fail","type":"GENERATE","correlationId":"corr-dlq-fail"}"""
        val message = message(payload)
        val consumer = newConsumer(pdfExportPort, resultPublisher, jetStream)

        consumer.receive(message)

        verify(message).ack()
        verify(message, never()).nak()
    }

    @Test
    fun `receive publishes completed result on successful export`() {
        System.setProperty("s3.endpoint", "http://rustfs-test:9000")
        System.setProperty("s3.bucket", "documents-test")

        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-1"), any(), any(), any(), anyOrNull())).thenReturn(
            ExportResult(
                objectKey = "invoice/job-1.pdf",
                zugferdXmlKey = "invoice/job-1.xml",
                kositSuccess = true,
                kositReport = "kosit-ok",
                sha256 = "abc123"
            )
        )

        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-1", includeInvoiceWrapper = true))

        consumer.receive(message)

        verify(resultPublisher).publishResult(
            eq("job-1"),
            eq("COMPLETED"),
            any(),
            eq("kosit-ok"),
            eq("corr-1"), anyOrNull())
        verify(message).ack()
        verify(message, never()).nak()
    }

    @Test
    fun `P3-T05 - a legacy message with the removed sender headline field is processed, not rejected`() {
        System.setProperty("s3.endpoint", "http://rustfs-test:9000")
        System.setProperty("s3.bucket", "documents-test")

        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-legacy-headline"), any(), any(), any(), anyOrNull())).thenReturn(
            ExportResult(
                objectKey = "invoice/job-legacy-headline.pdf",
                zugferdXmlKey = "invoice/job-legacy-headline.xml",
                kositSuccess = true,
                kositReport = "kosit-ok",
                sha256 = "abc123"
            )
        )

        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val legacyPayload = """
            {
              "id": "job-legacy-headline",
              "type": "GENERATE",
              "correlationId": "corr-1",
              "payload": {
                "invoice": {
                  "invoiceNumber": { "value": "INV-1" },
                  "items": [],
                  "senderProfile": {
                    "name": "Legacy GmbH",
                    "headerNote": "Ihr zuverlässiger Partner"
                  }
                }
              }
            }
        """.trimIndent()
        val message = message(legacyPayload)

        consumer.receive(message)

        // the unknown "headerNote" field must be silently ignored, not cause a nak/error
        verify(pdfExportPort).performExport(any(), eq("job-legacy-headline"), any(), any(), any(), anyOrNull())
        verify(message).ack()
        verify(message, never()).nak()
    }

    @Test
    fun `receive retries and then succeeds`() {
        System.setProperty("job.retry.count", "3")
        System.setProperty("job.retry.backoff.ms", "0")

        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-2"), any(), any(), any(), anyOrNull()))
            .thenThrow(RuntimeException("temporary"))
            .thenReturn(ExportResult("invoice/job-2.pdf", "invoice/job-2.xml", true, "", "hash"))

        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-2", includeInvoiceWrapper = false))

        consumer.receive(message)

        verify(pdfExportPort, times(2)).performExport(any(), eq("job-2"), any(), any(), any(), anyOrNull())
        verify(resultPublisher, times(1)).publishResult(
            eq("job-2"),
            eq("COMPLETED"),
            any(),
            eq(""),
            eq("corr-1"), anyOrNull())
        verify(message).ack()
    }

    @Test
    fun `receive publishes failed result after retry exhausted`() {
        System.setProperty("job.retry.count", "1")
        System.setProperty("job.retry.backoff.ms", "0")

        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-3"), any(), any(), any(), anyOrNull())).thenThrow(RuntimeException("hard-fail"))

        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-3", includeInvoiceWrapper = true))

        consumer.receive(message)

        verify(pdfExportPort, times(2)).performExport(any(), eq("job-3"), any(), any(), any(), anyOrNull())
        verify(resultPublisher, times(1)).publishResult(
            eq("job-3"),
            eq("FAILED"),
            any(),
            eq(""),
            eq("corr-1"), anyOrNull())
        verify(message).ack()
    }

    // ── Bounded poison redelivery: max_deliver -1 plus the app-side stale-
    //    DELEGATED reset now only ever firing for a genuinely gone message means a job whose
    //    RESULT keeps failing to publish (the one case handle() itself naks for) would otherwise
    //    redeliver -- and re-run its whole export -- forever. ─────────────────────────────────

    @Test
    fun `a message redelivered past the retry budget is treated as poison instead of processed again`() {
        System.setProperty("job.retry.count", "3")

        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        val message = message(validMessage("job-redelivered", includeInvoiceWrapper = false))
        // The mock built (and internally stubbed) by metaDataWithDeliveredCount must be assigned
        // to a local val before it is handed to thenReturn(...): passing it inline would start a
        // second, nested stub while message's own stub is still open, which Mockito rejects with
        // UnfinishedStubbingException.
        val metaData = metaDataWithDeliveredCount(5) // budget is retryCount + 1 = 4
        whenever(message.metaData()).thenReturn(metaData)
        val consumer = newConsumer(pdfExportPort, resultPublisher)

        consumer.receive(message)

        verify(pdfExportPort, never()).performExport(any(), any(), any(), any(), any(), anyOrNull())
        val errorCaptor = argumentCaptor<Map<String, String>?>()
        verify(resultPublisher).publishResult(eq("job-redelivered"), eq("FAILED"), errorCaptor.capture(), eq(""), eq("corr-1"), anyOrNull())
        assertTrue(errorCaptor.firstValue?.get("error")?.contains("redelivered 5 time(s)") == true)
        verify(message).ack()
        verify(message, never()).nak()
    }

    @Test
    fun `a message at exactly the redelivery budget is still processed normally`() {
        System.setProperty("job.retry.count", "3")

        val pdfExportPort: PdfExportPort = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-at-budget"), any(), any(), any(), anyOrNull()))
            .thenReturn(ExportResult("invoice/job-at-budget.pdf", "invoice/job-at-budget.xml", true, "", "hash"))
        val resultPublisher: ResultPublisher = mock()
        val message = message(validMessage("job-at-budget", includeInvoiceWrapper = false))
        val metaData = metaDataWithDeliveredCount(4) // budget is retryCount + 1 = 4, not yet exceeded
        whenever(message.metaData()).thenReturn(metaData)
        val consumer = newConsumer(pdfExportPort, resultPublisher)

        consumer.receive(message)

        verify(pdfExportPort).performExport(any(), eq("job-at-budget"), any(), any(), any(), anyOrNull())
        verify(resultPublisher).publishResult(eq("job-at-budget"), eq("COMPLETED"), any(), eq(""), eq("corr-1"), anyOrNull())
        verify(message).ack()
    }

    @Test
    fun `a redelivered poison message with no jobId anywhere is dropped, acked, never processed`() {
        System.setProperty("job.retry.count", "3")

        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        val message = message("not-json-at-all { broken")
        val metaData = metaDataWithDeliveredCount(10)
        whenever(message.metaData()).thenReturn(metaData)
        val consumer = newConsumer(pdfExportPort, resultPublisher)

        consumer.receive(message)

        verify(pdfExportPort, never()).performExport(any(), any(), any(), any(), any(), anyOrNull())
        verify(resultPublisher, never()).publishResult(any(), any(), any(), any(), any(), anyOrNull())
        verify(message).ack()
        verify(message, never()).nak()
    }

    @Test
    fun `a negative retry count -- skip entirely -- never treats a message's first delivery as over budget`() {
        System.setProperty("job.retry.count", "-1")

        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        val message = message(validMessage("job-first-delivery", includeInvoiceWrapper = false))
        val metaData = metaDataWithDeliveredCount(1)
        whenever(message.metaData()).thenReturn(metaData)
        val consumer = newConsumer(pdfExportPort, resultPublisher)

        consumer.receive(message)

        // retryCount=-1 means runWithRetry itself skips every attempt without publishing anything
        // at all (see "a negative configured retry count skips every attempt, including the
        // first" above) -- the point here is only that isExcessivelyRedelivered's coercion does
        // not short-circuit a first delivery straight to the poison path before that logic even
        // runs: had it done so, publishResult WOULD have been called (with a "redelivered"
        // reason), which is exactly what this asserts never happens.
        verify(pdfExportPort, never()).performExport(any(), any(), any(), any(), any(), anyOrNull())
        verify(resultPublisher, never()).publishResult(any(), any(), any(), any(), any(), anyOrNull())
        verify(message).ack()
    }

    @Test
    fun `a negative retry count still treats an actual redelivery as excessive`() {
        System.setProperty("job.retry.count", "-1")

        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        val message = message(validMessage("job-redelivered-neg", includeInvoiceWrapper = false))
        val metaData = metaDataWithDeliveredCount(2) // budget coerced to 1 -- this IS excessive
        whenever(message.metaData()).thenReturn(metaData)
        val consumer = newConsumer(pdfExportPort, resultPublisher)

        consumer.receive(message)

        verify(pdfExportPort, never()).performExport(any(), any(), any(), any(), any(), anyOrNull())
        val errorCaptor = argumentCaptor<Map<String, String>?>()
        verify(resultPublisher).publishResult(eq("job-redelivered-neg"), eq("FAILED"), errorCaptor.capture(), eq(""), eq("corr-1"), anyOrNull())
        assertTrue(errorCaptor.firstValue?.get("error")?.contains("redelivered 2 time(s)") == true)
        verify(message).ack()
    }

    @Test
    fun `a message whose metaData lookup itself throws is treated as a first delivery, never poison`() {
        System.setProperty("job.retry.count", "3")

        val pdfExportPort: PdfExportPort = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-bad-metadata"), any(), any(), any(), anyOrNull()))
            .thenReturn(ExportResult("invoice/job-bad-metadata.pdf", "invoice/job-bad-metadata.xml", true, "", "hash"))
        val resultPublisher: ResultPublisher = mock()
        val message = message(validMessage("job-bad-metadata", includeInvoiceWrapper = false))
        whenever(message.metaData()).thenThrow(IllegalArgumentException("not a JetStream message"))
        val consumer = newConsumer(pdfExportPort, resultPublisher)

        consumer.receive(message)

        verify(pdfExportPort).performExport(any(), eq("job-bad-metadata"), any(), any(), any(), anyOrNull())
        verify(resultPublisher).publishResult(eq("job-bad-metadata"), eq("COMPLETED"), any(), eq(""), eq("corr-1"), anyOrNull())
    }

    /** A concrete, non-final [io.nats.client.impl.NatsJetStreamMetaData] mock (standard Mockito
     *  subclassing, same as every other jnats API-object mock in this suite) reporting
     *  [deliveredCount] for [Message.metaData]. */
    private fun metaDataWithDeliveredCount(deliveredCount: Long): io.nats.client.impl.NatsJetStreamMetaData {
        val metaData: io.nats.client.impl.NatsJetStreamMetaData = mock()
        whenever(metaData.deliveredCount()).thenReturn(deliveredCount)
        return metaData
    }

    // ── DSC001-2025-09-00001 — a job that fails during/before the deterministic stages
    // must still carry llmStatus in its FAILED resultRefs, so ValidationReportDialog's
    // llmRequested flag never has to fall back to "unknown" for a job that plainly did request
    // llmOptimize. ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a retry-exhausted FAILED result carries llmStatus FAILED when llmOptimize was requested`() {
        System.setProperty("job.retry.count", "0")
        System.setProperty("job.retry.backoff.ms", "0")

        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-llm-retry-exhausted"), any(), any(), any(), anyOrNull()))
            .thenThrow(RuntimeException("hard-fail"))

        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-llm-retry-exhausted", includeInvoiceWrapper = true, llmOptimize = true))

        consumer.receive(message)

        val refsCaptor = org.mockito.kotlin.argumentCaptor<Map<String, String>?>()
        verify(resultPublisher).publishResult(eq("job-llm-retry-exhausted"), eq("FAILED"), refsCaptor.capture(), eq(""), eq("corr-1"), anyOrNull())
        assertEquals("FAILED", refsCaptor.firstValue?.get("llmStatus"))
    }

    @Test
    fun `a retry-exhausted FAILED result carries llmStatus NOT_REQUESTED when llmOptimize was off`() {
        System.setProperty("job.retry.count", "0")
        System.setProperty("job.retry.backoff.ms", "0")

        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-llm-retry-off"), any(), any(), any(), anyOrNull()))
            .thenThrow(RuntimeException("hard-fail"))

        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-llm-retry-off", includeInvoiceWrapper = true, llmOptimize = false))

        consumer.receive(message)

        val refsCaptor = org.mockito.kotlin.argumentCaptor<Map<String, String>?>()
        verify(resultPublisher).publishResult(eq("job-llm-retry-off"), eq("FAILED"), refsCaptor.capture(), eq(""), eq("corr-1"), anyOrNull())
        assertEquals("NOT_REQUESTED", refsCaptor.firstValue?.get("llmStatus"))
    }

    @Test
    fun `an unknown-engine FAILED result carries llmStatus FAILED when llmOptimize was requested`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(
            validMessage("job-llm-unknown-engine", includeInvoiceWrapper = true, llmOptimize = true, engine = "BOGUS"),
        )

        consumer.receive(message)

        val refsCaptor = org.mockito.kotlin.argumentCaptor<Map<String, String>?>()
        verify(resultPublisher).publishResult(eq("job-llm-unknown-engine"), eq("FAILED"), refsCaptor.capture(), eq(""), eq("corr-1"), anyOrNull())
        assertEquals("FAILED", refsCaptor.firstValue?.get("llmStatus"))
        assertTrue(refsCaptor.firstValue?.get("error")?.contains("Unknown engine") == true)
    }

    // ── AP-M1 — processFacturXJob's infra-exception retry loop (the try/catch around
    // performFacturXExport, distinct from the engine's own Success/Failed verdict) needs the same
    // two coverage cases as processMustangJob above: a transient failure that gets retried and
    // succeeds, and one where every attempt fails and the retry budget is exhausted. ─────────

    @Test
    fun `receive retries the FacturX engine path and then succeeds`() {
        System.setProperty("job.retry.count", "3")
        System.setProperty("job.retry.backoff.ms", "0")

        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(pdfExportPort.performFacturXExport(any(), eq("job-facturx-retry-ok"), any(), any(), any(), anyOrNull()))
            .thenThrow(RuntimeException("transient receipt load failure"))
            .thenReturn(FacturXExportOutcome.Success(ExportResult("invoice/job-facturx-retry-ok.pdf", "invoice/job-facturx-retry-ok.xml", true, "", "hash")))

        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-facturx-retry-ok", includeInvoiceWrapper = true, engine = "FACTURX"))

        consumer.receive(message)

        verify(pdfExportPort, times(2)).performFacturXExport(any(), eq("job-facturx-retry-ok"), any(), any(), any(), anyOrNull())
        verify(resultPublisher, times(1)).publishResult(
            eq("job-facturx-retry-ok"), eq("COMPLETED"), any(), eq(""), eq("corr-1"), anyOrNull(),
        )
        verify(message).ack()
        verify(message, never()).nak()
    }

    @Test
    fun `receive publishes failed result after FacturX engine retries exhausted (infra exception, not an engine verdict)`() {
        System.setProperty("job.retry.count", "1")
        System.setProperty("job.retry.backoff.ms", "0")

        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(pdfExportPort.performFacturXExport(any(), eq("job-facturx-retry-exhausted"), any(), any(), any(), anyOrNull()))
            .thenThrow(RuntimeException("receipt load hard-fail"))

        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-facturx-retry-exhausted", includeInvoiceWrapper = true, engine = "FACTURX"))

        consumer.receive(message)

        verify(pdfExportPort, times(2)).performFacturXExport(any(), eq("job-facturx-retry-exhausted"), any(), any(), any(), anyOrNull())
        verify(resultPublisher, times(1)).publishResult(
            eq("job-facturx-retry-exhausted"), eq("FAILED"), any(), eq(""), eq("corr-1"), anyOrNull(),
        )
        verify(message).ack()
        verify(message, never()).nak()
    }

    @Test
    fun `receive passes llm optimize flag to export service`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-5"), eq(true), any(), any(), anyOrNull())).thenReturn(
            ExportResult(
                objectKey = "invoice/job-5.pdf",
                zugferdXmlKey = "invoice/job-5.xml",
                kositSuccess = true,
                kositReport = "kosit-ok",
                sha256 = "hash-5",
                llmReportKey = "invoice/job-5-llm-report.md",
            )
        )

        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-5", includeInvoiceWrapper = true, llmOptimize = true))

        consumer.receive(message)

        verify(pdfExportPort).performExport(any(), eq("job-5"), eq(true), any(), any(), anyOrNull())
        verify(message).ack()
    }

    @Test
    fun `receive accepts legacy ollamaOptimize option as fallback`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-6"), eq(true), any(), any(), anyOrNull())).thenReturn(
            ExportResult(
                objectKey = "invoice/job-6.pdf",
                zugferdXmlKey = "invoice/job-6.xml",
                kositSuccess = true,
                kositReport = "kosit-ok",
                sha256 = "hash-6",
            )
        )

        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-6", includeInvoiceWrapper = true, llmOptimize = false, legacyOllamaOptimize = true))

        consumer.receive(message)

        verify(pdfExportPort).performExport(any(), eq("job-6"), eq(true), any(), any(), anyOrNull())
        verify(message).ack()
    }

    // ── the veraPdfCheck option is a plain passthrough, resolved entirely by
    // kotlin-invoice's DocumentJobPublisher; this worker has no DB access of its own.

    @Test
    fun `T9b - passes veraPdfCheck true through to the export port`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-7"), any(), any(), eq(true), anyOrNull())).thenReturn(
            ExportResult(
                objectKey = "invoice/job-7.pdf",
                zugferdXmlKey = "invoice/job-7.xml",
                kositSuccess = true,
                kositReport = "kosit-ok",
                sha256 = "hash-7",
            )
        )

        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-7", includeInvoiceWrapper = true, veraPdfCheck = true))

        consumer.receive(message)

        verify(pdfExportPort).performExport(any(), eq("job-7"), any(), any(), eq(true), anyOrNull())
        verify(message).ack()
    }

    @Test
    fun `T9b - defaults veraPdfCheck to false when the option is absent`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-8"), any(), any(), eq(false), anyOrNull())).thenReturn(
            ExportResult(
                objectKey = "invoice/job-8.pdf",
                zugferdXmlKey = "invoice/job-8.xml",
                kositSuccess = true,
                kositReport = "kosit-ok",
                sha256 = "hash-8",
            )
        )

        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-8", includeInvoiceWrapper = true))

        consumer.receive(message)

        verify(pdfExportPort).performExport(any(), eq("job-8"), any(), any(), eq(false), anyOrNull())
        verify(message).ack()
    }

    // ── no silent Mustang fallback for `engine` ───────────────────────────────────────────────
    // The bug this fixes: DocumentJobRequest had no `engine` field and the mapper tolerated unknown
    // properties, so a message carrying `"engine":"FACTURX"` was processed as Mustang without
    // anyone noticing. These three tests pin down the fixed behavior for every value the wire can
    // carry, plus the legacy (no key at all) case.

    @Test
    fun `engine absent from the message still runs the Mustang export path unchanged`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-legacy-engine"), any(), any(), any(), anyOrNull())).thenReturn(
            ExportResult("invoice/job-legacy-engine.pdf", "invoice/job-legacy-engine.xml", true, "kosit-ok", "hash")
        )

        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-legacy-engine", includeInvoiceWrapper = true))

        consumer.receive(message)

        verify(pdfExportPort).performExport(any(), eq("job-legacy-engine"), any(), any(), any(), anyOrNull())
        verify(resultPublisher).publishResult(eq("job-legacy-engine"), eq("COMPLETED"), any(), eq("kosit-ok"), eq("corr-1"), anyOrNull())
        verify(message).ack()
        verify(message, never()).nak()
    }

    @Test
    fun `engine MUSTANG runs the export path exactly like an absent engine`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-mustang"), any(), any(), any(), anyOrNull())).thenReturn(
            ExportResult("invoice/job-mustang.pdf", "invoice/job-mustang.xml", true, "kosit-ok", "hash")
        )

        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-mustang", includeInvoiceWrapper = true, engine = "MUSTANG"))

        consumer.receive(message)

        verify(pdfExportPort).performExport(any(), eq("job-mustang"), any(), any(), any(), anyOrNull())
        verify(resultPublisher).publishResult(eq("job-mustang"), eq("COMPLETED"), any(), eq("kosit-ok"), eq("corr-1"), anyOrNull())
        verify(message).ack()
        verify(message, never()).nak()
    }

    // ── (facturx-arc-improve.md §7) — "FACTURX" now reaches the real port instead of the
    // earlier placeholder. These two tests pin down both branches of `FacturXExportOutcome`: a
    // successful engine round-trip publishes COMPLETED exactly like Mustang (same
    // `performExport`/`performFacturXExport` result shape), and a `Failed` outcome publishes the
    // engine's own findings as a clean, non-retried FAILED — never `performExport` (the Mustang
    // path), never a second attempt.

    @Test
    fun `engine FACTURX calls performFacturXExport and publishes a COMPLETED result on success`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(pdfExportPort.performFacturXExport(any(), eq("job-facturx-ok"), any(), any(), any(), anyOrNull())).thenReturn(
            FacturXExportOutcome.Success(
                ExportResult(
                    objectKey = "invoice/job-facturx-ok.pdf",
                    zugferdXmlKey = "invoice/job-facturx-ok.xml",
                    kositSuccess = true,
                    kositReport = "kosit-ok-facturx",
                    sha256 = "hash-facturx",
                )
            )
        )

        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-facturx-ok", includeInvoiceWrapper = true, engine = "FACTURX"))

        consumer.receive(message)

        verify(pdfExportPort, never()).performExport(any(), any(), any(), any(), any(), anyOrNull())
        verify(pdfExportPort).performFacturXExport(any(), eq("job-facturx-ok"), any(), any(), any(), anyOrNull())
        verify(resultPublisher).publishResult(
            eq("job-facturx-ok"), eq("COMPLETED"), any(), eq("kosit-ok-facturx"), eq("corr-1"), anyOrNull(),
        )
        verify(message).ack()
        verify(message, never()).nak()
    }

    @Test
    fun `engine FACTURX with an engine Failed outcome publishes FAILED with the returned findings and never retries`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        val findings = listOf(
            ValidationMessage(
                severity = "error",
                message = "Payload (999 bytes) exceeds the engine's 25MB upload limit",
                ruleId = "FACTURX-PAYLOAD-TOO-LARGE",
                stage = "FACTURX",
                validator = "facturx-engine",
            )
        )
        val reportJson = """{"status":"ERROR","summary":"too big"}"""
        whenever(pdfExportPort.performFacturXExport(any(), eq("job-facturx-fail"), any(), any(), any(), anyOrNull())).thenReturn(
            FacturXExportOutcome.Failed(findings, reportJson)
        )

        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-facturx-fail", includeInvoiceWrapper = true, engine = "FACTURX"))

        consumer.receive(message)

        verify(pdfExportPort, never()).performExport(any(), any(), any(), any(), any(), anyOrNull())
        // exactly once — a Failed outcome is a definitive engine answer, not a transient fault
        verify(pdfExportPort, times(1)).performFacturXExport(any(), eq("job-facturx-fail"), any(), any(), any(), anyOrNull())
        val errorCaptor = org.mockito.kotlin.argumentCaptor<Map<String, String>?>()
        verify(resultPublisher).publishResult(
            eq("job-facturx-fail"), eq("FAILED"), errorCaptor.capture(), eq(reportJson), eq("corr-1"), anyOrNull(),
        )
        assertTrue(errorCaptor.firstValue?.get("error")?.contains("FACTURX-PAYLOAD-TOO-LARGE") != true) // error map carries the message text, not the ruleId
        assertTrue(errorCaptor.firstValue?.get("error")?.contains("25MB upload limit") == true)
        verify(message).ack()
        verify(message, never()).nak()
    }

    @Test
    fun `engine with an unrecognized value fails the job and names the received value`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()

        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-typo", includeInvoiceWrapper = true, engine = "FAKTURX"))

        consumer.receive(message)

        verify(pdfExportPort, never()).performExport(any(), any(), any(), any(), any(), anyOrNull())
        val errorCaptor = org.mockito.kotlin.argumentCaptor<Map<String, String>?>()
        verify(resultPublisher).publishResult(
            eq("job-typo"), eq("FAILED"), errorCaptor.capture(), any(), eq("corr-1"), anyOrNull(),
        )
        assertTrue(errorCaptor.firstValue?.get("error")?.contains("FAKTURX") == true)
        verify(message).ack()
        verify(message, never()).nak()
    }

    // ── AP-M10 — closing the gap to 100 % line+branch ─────────────────────────────────────

    // ── AP-M4 — pull-consumer subscribe(): StreamContext -> ConsumerContext -> consume() ────────

    @Test
    fun `subscribe ensures the durable consumer via JetStreamManagement and wires consume() to receive`() {
        // Pinned explicitly: workerThreads' own default is now derived from this
        // JVM's heap ceiling (JobConsumer.defaultWorkerThreads), not the old fixed literal 4, so
        // this test's own maxAckPending/batchSize assertions below need a deterministic value
        // independent of whatever heap size happens to run this test.
        System.setProperty("job.worker.threads", "4")
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        val connection: Connection = mock()
        val jetStream: JetStream = mock()
        val jsm: JetStreamManagement = mock()
        val streamContext: StreamContext = mock()
        val consumerContext: ConsumerContext = mock()
        whenever(connection.jetStreamManagement()).thenReturn(jsm)
        whenever(jsm.addOrUpdateConsumer(any(), any())).thenReturn(mock())
        whenever(jetStream.getStreamContext(de.littletrotter.shared.NatsStreamSetup.STREAM_NAME)).thenReturn(streamContext)
        whenever(streamContext.getConsumerContext("kotlin-xpdf-group")).thenReturn(consumerContext)
        whenever(pdfExportPort.performExport(any(), eq("job-sub"), any(), any(), any(), anyOrNull())).thenReturn(
            ExportResult("invoice/job-sub.pdf", "invoice/job-sub.xml", true, "kosit-ok", "hash-sub")
        )

        // jobsSubject/durableConsumerName default to "document-jobs"/"kotlin-xpdf-group"
        // exactly what this test needs, no override required.
        val consumer = newConsumer(pdfExportPort, resultPublisher, jetStream = jetStream, connection = connection)

        consumer.subscribe()

        // durable consumer creation/drift-update goes through the shared NatsStreamSetup helper,
        // filtered to this consumer's own subject — never the stream's other (results) subject.
        val configCaptor = argumentCaptor<io.nats.client.api.ConsumerConfiguration>()
        verify(jsm).addOrUpdateConsumer(eq(de.littletrotter.shared.NatsStreamSetup.STREAM_NAME), configCaptor.capture())
        assertEquals("kotlin-xpdf-group", configCaptor.firstValue.durable)
        assertEquals("document-jobs", configCaptor.firstValue.filterSubject)
        assertEquals(8L, configCaptor.firstValue.maxAckPending) // 2 * pinned workerThreads(4)

        val consumeOptionsCaptor = argumentCaptor<ConsumeOptions>()
        val handlerCaptor = argumentCaptor<MessageHandler>()
        verify(consumerContext).consume(consumeOptionsCaptor.capture(), handlerCaptor.capture())
        assertEquals(4, consumeOptionsCaptor.firstValue.batchSize) // pinned workerThreads
        assertEquals(10_000L, consumeOptionsCaptor.firstValue.expiresInMillis)

        // exercises subscribe()'s lambda itself, not just receive() directly — proves the
        // consumer context is actually wired to this consumer's own receive().
        val message = message(validMessage("job-sub", includeInvoiceWrapper = true))
        handlerCaptor.firstValue.onMessage(message)
        verify(message).ack()
    }

    @Test
    fun `subscribe floors the pull batch at 2 for a single worker thread so jnats keeps re-pulling`() {
        // The heap-derived default legitimately yields 1 thread on a small container; jnats never
        // re-pulls at batchSize = 1 (re-pull threshold 0), which stalled the demo backlog at one
        // job per heartbeat alarm.
        System.setProperty("job.worker.threads", "1")
        val connection: Connection = mock()
        val jetStream: JetStream = mock()
        val jsm: JetStreamManagement = mock()
        val streamContext: StreamContext = mock()
        val consumerContext: ConsumerContext = mock()
        whenever(connection.jetStreamManagement()).thenReturn(jsm)
        whenever(jsm.addOrUpdateConsumer(any(), any())).thenReturn(mock())
        whenever(jetStream.getStreamContext(de.littletrotter.shared.NatsStreamSetup.STREAM_NAME)).thenReturn(streamContext)
        whenever(streamContext.getConsumerContext("kotlin-xpdf-group")).thenReturn(consumerContext)

        // jobsSubject/durableConsumerName default to "document-jobs"/"kotlin-xpdf-group"
        // exactly what this test needs, no override required.
        val consumer = newConsumer(mock(), mock(), jetStream = jetStream, connection = connection)

        consumer.subscribe()

        val configCaptor = argumentCaptor<io.nats.client.api.ConsumerConfiguration>()
        verify(jsm).addOrUpdateConsumer(eq(de.littletrotter.shared.NatsStreamSetup.STREAM_NAME), configCaptor.capture())
        assertEquals(2L, configCaptor.firstValue.maxAckPending) // memory bound stays 2 * workerThreads(1)

        val consumeOptionsCaptor = argumentCaptor<ConsumeOptions>()
        verify(consumerContext).consume(consumeOptionsCaptor.capture(), any<MessageHandler>())
        assertEquals(JobConsumer.MIN_PULL_BATCH_SIZE, consumeOptionsCaptor.firstValue.batchSize)
    }

    @Test
    fun `executor defaults to a real thread pool, reachable via its internal getter`() {
        val consumer = JobConsumer(mock(), mock(), mock(), mock(), mock())
        assertNotNull(consumer.executor)
    }

    @Test
    fun `a negative configured retry count skips every attempt, including the first`() {
        System.setProperty("job.retry.count", "-1")

        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-skip", includeInvoiceWrapper = true))

        consumer.receive(message)

        verify(pdfExportPort, never()).performExport(any(), any(), any(), any(), any(), anyOrNull())
        verify(resultPublisher, never()).publishResult(any(), any(), any(), any(), any(), anyOrNull())
        verify(message).ack()
    }

    @Test
    fun `an interrupted retry backoff stops retrying and restores the interrupt flag`() {
        System.setProperty("job.retry.count", "2")
        System.setProperty("job.retry.backoff.ms", "1000")

        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-int"), any(), any(), any(), anyOrNull()))
            .thenThrow(RuntimeException("temporary"))

        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-int", includeInvoiceWrapper = true))

        Thread.currentThread().interrupt()
        try {
            consumer.receive(message)
        } finally {
            assertTrue(Thread.interrupted(), "the interrupt flag must be restored, not swallowed")
        }

        // only the first attempt runs: the sleep before a second attempt throws InterruptedException
        // immediately because the thread was already interrupted, and that path returns without
        // publishing anything.
        verify(pdfExportPort, times(1)).performExport(any(), eq("job-int"), any(), any(), any(), anyOrNull())
        verify(resultPublisher, never()).publishResult(any(), any(), any(), any(), any(), anyOrNull())
    }

    // ── AP-M1 — processFacturXJob's `while(true)` refactor needs the same two edge-case tests
    // processMustangJob already had, now also for the FacturX engine
    // path — otherwise the new `if (retryCount < 0) return` guard and the interrupted-backoff
    // `return` inside processFacturXJob would themselves be uncovered branches. ──────────────

    @Test
    fun `a negative configured retry count skips every attempt for the FacturX engine too`() {
        System.setProperty("job.retry.count", "-1")

        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-facturx-skip", includeInvoiceWrapper = true, engine = "FACTURX"))

        consumer.receive(message)

        verify(pdfExportPort, never()).performFacturXExport(any(), any(), any(), any(), any(), anyOrNull())
        verify(resultPublisher, never()).publishResult(any(), any(), any(), any(), any(), anyOrNull())
        verify(message).ack()
        verify(message, never()).nak()
    }

    @Test
    fun `an interrupted retry backoff stops retrying the FacturX engine path and restores the interrupt flag`() {
        System.setProperty("job.retry.count", "2")
        System.setProperty("job.retry.backoff.ms", "1000")

        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(pdfExportPort.performFacturXExport(any(), eq("job-facturx-int"), any(), any(), any(), anyOrNull()))
            .thenThrow(RuntimeException("temporary"))

        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-facturx-int", includeInvoiceWrapper = true, engine = "FACTURX"))

        Thread.currentThread().interrupt()
        try {
            consumer.receive(message)
        } finally {
            assertTrue(Thread.interrupted(), "the interrupt flag must be restored, not swallowed")
        }

        verify(pdfExportPort, times(1)).performFacturXExport(any(), eq("job-facturx-int"), any(), any(), any(), anyOrNull())
        verify(resultPublisher, never()).publishResult(any(), any(), any(), any(), any(), anyOrNull())
    }

    // ── AP-M1 §3 — a publish failure at any of these call sites must now nak the message instead
    // of acking it, so JetStream redelivers and the result gets a second chance (except the two
    // poison-job call sites above, which always still ack — see their own tests). ────────────

    @Test
    fun `a resultPublisher failure on the happy COMPLETED path naks so the export is not lost`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-pubfail"), any(), any(), any(), anyOrNull())).thenReturn(
            ExportResult("invoice/job-pubfail.pdf", "invoice/job-pubfail.xml", true, "kosit-ok", "hash")
        )
        whenever(resultPublisher.publishResult(eq("job-pubfail"), eq("COMPLETED"), any(), any(), any(), anyOrNull()))
            .thenThrow(RuntimeException("nats down"))

        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-pubfail", includeInvoiceWrapper = true))

        consumer.receive(message)

        // export ran exactly once — a publish failure must not re-run the (already successful)
        // export, only redeliver so the publish itself gets a second chance
        verify(pdfExportPort, times(1)).performExport(any(), eq("job-pubfail"), any(), any(), any(), anyOrNull())
        verify(message, never()).ack()
        verify(message).nak()
    }

    @Test
    fun `a resultPublisher failure on the FAILED path after retries exhausted naks so the failure is not lost`() {
        System.setProperty("job.retry.count", "0")
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-failfail"), any(), any(), any(), anyOrNull()))
            .thenThrow(RuntimeException("hard-fail"))
        whenever(resultPublisher.publishResult(eq("job-failfail"), eq("FAILED"), any(), any(), any(), anyOrNull()))
            .thenThrow(RuntimeException("nats down"))

        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-failfail", includeInvoiceWrapper = true))

        consumer.receive(message)

        verify(message, never()).ack()
        verify(message).nak()
    }

    @Test
    fun `llmStatus llmModel and llmError result fields are carried when present and omitted when blank`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-llmfields"), any(), any(), any(), anyOrNull())).thenReturn(
            ExportResult(
                objectKey = "invoice/job-llmfields.pdf",
                zugferdXmlKey = "invoice/job-llmfields.xml",
                kositSuccess = true,
                kositReport = "kosit-ok",
                sha256 = "hash",
                llmStatus = "DONE",
                llmModel = "gpt-oss-20b",
                llmError = "",
                llmDurationMs = 1500,
            )
        )

        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-llmfields", includeInvoiceWrapper = true))

        val refsCaptor = argumentCaptor<Map<String, String>>()
        consumer.receive(message)

        verify(resultPublisher).publishResult(eq("job-llmfields"), eq("COMPLETED"), refsCaptor.capture(), any(), any(), anyOrNull())
        val refs = refsCaptor.firstValue
        assertTrue(refs.containsKey("llmStatus"))
        assertTrue(refs.containsKey("llmModel"))
        assertTrue(refs.containsKey("llmDurationMs"))
        assertFalse(refs.containsKey("llmError"), "a blank llmError must not appear on the wire")
    }

    // ── sha256/zugferdXmlSha256 carried through to resultRefs ──────────

    @Test
    fun `RS-10 - sha256 and zugferdXmlSha256 are carried into resultRefs when present`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-rs10-checksums"), any(), any(), any(), anyOrNull())).thenReturn(
            ExportResult(
                objectKey = "invoice/job-rs10-checksums.pdf",
                zugferdXmlKey = "invoice/job-rs10-checksums.xml",
                kositSuccess = true,
                kositReport = "kosit-ok",
                sha256 = "a".repeat(64),
                zugferdXmlSha256 = "b".repeat(64),
            )
        )

        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-rs10-checksums", includeInvoiceWrapper = true))

        val refsCaptor = argumentCaptor<Map<String, String>>()
        consumer.receive(message)

        verify(resultPublisher).publishResult(eq("job-rs10-checksums"), eq("COMPLETED"), refsCaptor.capture(), any(), any(), anyOrNull())
        val refs = refsCaptor.firstValue
        assertEquals("a".repeat(64), refs["sha256"])
        assertEquals("b".repeat(64), refs["zugferdXmlSha256"])
    }

    @Test
    fun `RS-10 - a null zugferdXmlSha256 (rejected generateXml) is omitted from resultRefs, never a literal null`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-rs10-no-xml"), any(), any(), any(), anyOrNull())).thenReturn(
            ExportResult(
                objectKey = "invoice/job-rs10-no-xml.pdf",
                zugferdXmlKey = "",
                kositSuccess = true,
                kositReport = "kosit-ok",
                sha256 = "a".repeat(64),
                zugferdXmlSha256 = null,
            )
        )

        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-rs10-no-xml", includeInvoiceWrapper = true))

        val refsCaptor = argumentCaptor<Map<String, String>>()
        consumer.receive(message)

        verify(resultPublisher).publishResult(eq("job-rs10-no-xml"), eq("COMPLETED"), refsCaptor.capture(), any(), any(), anyOrNull())
        val refs = refsCaptor.firstValue
        assertEquals("a".repeat(64), refs["sha256"])
        assertFalse(refs.containsKey("zugferdXmlSha256"), "no XML was ever produced — the key must be absent, not null")
    }

    // ── archiveObjectKey/archiveSha256 carried through to resultRefs ────

    @Test
    fun `RS-9 - archiveObjectKey and archiveSha256 are carried into resultRefs when present`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-rs9-archive"), any(), any(), any(), anyOrNull())).thenReturn(
            ExportResult(
                objectKey = "invoice/job-rs9-archive.pdf",
                zugferdXmlKey = "invoice/job-rs9-archive.xml",
                kositSuccess = true,
                kositReport = "kosit-ok",
                sha256 = "a".repeat(64),
                zugferdXmlSha256 = "b".repeat(64),
                archiveObjectKey = "invoice/job-rs9-archive.tar.gz",
                archiveSha256 = "c".repeat(64),
            )
        )

        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-rs9-archive", includeInvoiceWrapper = true))

        val refsCaptor = argumentCaptor<Map<String, String>>()
        consumer.receive(message)

        verify(resultPublisher).publishResult(eq("job-rs9-archive"), eq("COMPLETED"), refsCaptor.capture(), any(), any(), anyOrNull())
        val refs = refsCaptor.firstValue
        assertEquals("invoice/job-rs9-archive.tar.gz", refs["archiveObjectKey"])
        assertEquals("c".repeat(64), refs["archiveSha256"])
    }

    @Test
    fun `RS-9 - a null archiveObjectKey (draft export) is omitted from resultRefs, never a literal null`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-rs9-draft"), any(), any(), any(), anyOrNull())).thenReturn(
            ExportResult(
                objectKey = "invoice/job-rs9-draft.pdf",
                zugferdXmlKey = "invoice/job-rs9-draft.xml",
                kositSuccess = true,
                kositReport = "kosit-ok",
                sha256 = "a".repeat(64),
                zugferdXmlSha256 = "b".repeat(64),
                archiveObjectKey = null,
                archiveSha256 = null,
            )
        )

        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-rs9-draft", includeInvoiceWrapper = true))

        val refsCaptor = argumentCaptor<Map<String, String>>()
        consumer.receive(message)

        verify(resultPublisher).publishResult(eq("job-rs9-draft"), eq("COMPLETED"), refsCaptor.capture(), any(), any(), anyOrNull())
        val refs = refsCaptor.firstValue
        assertFalse(refs.containsKey("archiveObjectKey"), "a draft export never builds a package — the key must be absent, not null")
        assertFalse(refs.containsKey("archiveSha256"))
    }

    @Test
    fun `RS-9-RS-10 - the degraded Factur-X outcome with no XML at all omits sha256, zugferdXmlSha256, archiveObjectKey and archiveSha256`() {
        // blank (not null) on purpose for zugferdXmlSha256/archiveObjectKey/archiveSha256 — the
        // null path is already covered by the tests above; takeIf { isNotBlank() }
        // needs its own non-null-but-blank case exercised too, for full branch coverage.
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-rs9-rs10-no-xml-at-all"), any(), any(), any(), anyOrNull())).thenReturn(
            ExportResult(
                objectKey = "invoice/job-rs9-rs10-no-xml-at-all.pdf",
                zugferdXmlKey = "",
                kositSuccess = true,
                kositReport = "kosit-ok",
                sha256 = "",
                zugferdXmlSha256 = "",
                archiveObjectKey = "",
                archiveSha256 = "",
            )
        )

        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-rs9-rs10-no-xml-at-all", includeInvoiceWrapper = true))

        val refsCaptor = argumentCaptor<Map<String, String>>()
        consumer.receive(message)

        verify(resultPublisher).publishResult(eq("job-rs9-rs10-no-xml-at-all"), eq("COMPLETED"), refsCaptor.capture(), any(), any(), anyOrNull())
        val refs = refsCaptor.firstValue
        assertFalse(refs.containsKey("sha256"), "a blank sha256 must not appear on the wire")
        assertFalse(refs.containsKey("zugferdXmlSha256"), "no XML was ever produced — the key must be absent, not null")
        assertFalse(refs.containsKey("archiveObjectKey"), "no XML means no archive package either — the key must be absent, not null")
        assertFalse(refs.containsKey("archiveSha256"))
        assertEquals("invoice/job-rs9-rs10-no-xml-at-all.pdf", refs["objectKey"])
        assertTrue(refs.containsKey("downloadUrl"))
        assertEquals("", refs["zugferdObjectKey"])
    }

    // ── formatGuideline carried through to resultRefs (2026-08-24) ──────────────

    @Test
    fun `formatGuideline is carried into resultRefs when the worker extracted one`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-guideline"), any(), any(), any(), anyOrNull())).thenReturn(
            ExportResult(
                objectKey = "invoice/job-guideline.pdf",
                zugferdXmlKey = "invoice/job-guideline.xml",
                kositSuccess = true,
                kositReport = "kosit-ok",
                sha256 = "hash",
                formatGuideline = "urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0",
            )
        )

        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-guideline", includeInvoiceWrapper = true))

        val refsCaptor = argumentCaptor<Map<String, String>>()
        consumer.receive(message)

        verify(resultPublisher).publishResult(eq("job-guideline"), eq("COMPLETED"), refsCaptor.capture(), any(), any(), anyOrNull())
        assertEquals(
            "urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0",
            refsCaptor.firstValue["formatGuideline"],
        )
    }

    @Test
    fun `formatGuideline is omitted from resultRefs when the worker found none (legacy-compatible)`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-no-guideline"), any(), any(), any(), anyOrNull())).thenReturn(
            ExportResult(
                objectKey = "invoice/job-no-guideline.pdf",
                zugferdXmlKey = "invoice/job-no-guideline.xml",
                kositSuccess = true,
                kositReport = "kosit-ok",
                sha256 = "hash",
                formatGuideline = null,
            )
        )

        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-no-guideline", includeInvoiceWrapper = true))

        val refsCaptor = argumentCaptor<Map<String, String>>()
        consumer.receive(message)

        verify(resultPublisher).publishResult(eq("job-no-guideline"), eq("COMPLETED"), refsCaptor.capture(), any(), any(), anyOrNull())
        assertFalse(refsCaptor.firstValue.containsKey("formatGuideline"))
    }

    @Test
    fun `formatGuideline is omitted from resultRefs when the extractor returned blank`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-blank-guideline"), any(), any(), any(), anyOrNull())).thenReturn(
            ExportResult(
                objectKey = "invoice/job-blank-guideline.pdf",
                zugferdXmlKey = "invoice/job-blank-guideline.xml",
                kositSuccess = true,
                kositReport = "kosit-ok",
                sha256 = "hash",
                formatGuideline = "",
            )
        )

        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-blank-guideline", includeInvoiceWrapper = true))

        val refsCaptor = argumentCaptor<Map<String, String>>()
        consumer.receive(message)

        verify(resultPublisher).publishResult(eq("job-blank-guideline"), eq("COMPLETED"), refsCaptor.capture(), any(), any(), anyOrNull())
        assertFalse(refsCaptor.firstValue.containsKey("formatGuideline"))
    }

    @Test
    fun `publishFacturXEngineFailure naks when its own publish fails, the engine verdict is not lost`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        val findings = listOf(
            ValidationMessage(
                severity = "error",
                message = "Payload exceeds the engine's upload limit",
                ruleId = "FACTURX-PAYLOAD-TOO-LARGE",
                stage = "FACTURX",
                validator = "facturx-engine",
            )
        )
        whenever(pdfExportPort.performFacturXExport(any(), eq("job-facturx-pubfail"), any(), any(), any(), anyOrNull()))
            .thenReturn(FacturXExportOutcome.Failed(findings, """{"status":"ERROR"}"""))
        whenever(resultPublisher.publishResult(eq("job-facturx-pubfail"), eq("FAILED"), any(), any(), any(), anyOrNull()))
            .thenThrow(RuntimeException("nats down"))

        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-facturx-pubfail", includeInvoiceWrapper = true, engine = "FACTURX"))

        consumer.receive(message)

        // never retried — a Failed outcome is definitive, only the publish gets another chance
        verify(pdfExportPort, times(1)).performFacturXExport(any(), eq("job-facturx-pubfail"), any(), any(), any(), anyOrNull())
        verify(message, never()).ack()
        verify(message).nak()
    }

    @Test
    fun `failUnknownEngine naks when its own publish fails, the FAILED verdict is not lost`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(resultPublisher.publishResult(eq("job-unknown-pubfail"), eq("FAILED"), any(), any(), any(), anyOrNull()))
            .thenThrow(RuntimeException("nats down"))

        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-unknown-pubfail", includeInvoiceWrapper = true, engine = "FAKTURX"))

        consumer.receive(message)

        verify(message, never()).ack()
        verify(message).nak()
    }

    @Test
    fun `retry backoff worker-thread and status fields fall back to environment variables when no system property is set`() {
        // System.getProperty(...) ?: readEnv(...) ?: default — every field on JobConsumer follows
        // this pattern. Tests elsewhere always exercise the property-set or both-unset(default)
        // branches; the middle branch (property unset, env set) needs the environment itself
        // faked — routed through readEnv (de.littletrotter.xpdf.EnvConfig) specifically so
        // it can be intercepted this way (Mockito refuses to mock java.lang.System directly).
        mockStatic(Class.forName("de.littletrotter.xpdf.EnvConfig")).use { staticEnv ->
            staticEnv.`when`<String?> { de.littletrotter.xpdf.readEnv("JOB_RETRY_COUNT") }.thenReturn("0")
            staticEnv.`when`<String?> { de.littletrotter.xpdf.readEnv("JOB_RETRY_BACKOFF_MS") }.thenReturn("0")
            staticEnv.`when`<String?> { de.littletrotter.xpdf.readEnv("S3_ENDPOINT") }.thenReturn("http://rustfs-env:9000")
            staticEnv.`when`<String?> { de.littletrotter.xpdf.readEnv("S3_BUCKET") }.thenReturn("documents-env")
            staticEnv.`when`<String?> { de.littletrotter.xpdf.readEnv("JOB_WORKER_THREADS") }.thenReturn("1")

            val pdfExportPort: PdfExportPort = mock()
            val resultPublisher: ResultPublisher = mock()
            whenever(pdfExportPort.performExport(any(), eq("job-env"), any(), any(), any(), anyOrNull())).thenReturn(
                ExportResult("job-env.pdf", "job-env.xml", true, "kosit-ok", "hash")
            )
            val consumer = newConsumer(pdfExportPort, resultPublisher)
            val message = message(validMessage("job-env", includeInvoiceWrapper = true))

            consumer.receive(message)

            val refsCaptor = argumentCaptor<Map<String, String>>()
            verify(resultPublisher).publishResult(eq("job-env"), eq("COMPLETED"), refsCaptor.capture(), any(), any(), anyOrNull())
            assertTrue(
                refsCaptor.firstValue.getValue("downloadUrl").startsWith("http://rustfs-env:9000/documents-env/"),
                "s3Endpoint/bucket must come from the env-var fallback, not the built-in default",
            )
        }
    }

    @Test
    fun `a non-blank llmError result field is carried on the wire`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-llmerr"), any(), any(), any(), anyOrNull())).thenReturn(
            ExportResult(
                objectKey = "invoice/job-llmerr.pdf",
                zugferdXmlKey = "invoice/job-llmerr.xml",
                kositSuccess = true,
                kositReport = "kosit-ok",
                sha256 = "hash",
                llmError = "model timed out",
            )
        )

        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-llmerr", includeInvoiceWrapper = true))

        val refsCaptor = argumentCaptor<Map<String, String>>()
        consumer.receive(message)

        verify(resultPublisher).publishResult(eq("job-llmerr"), eq("COMPLETED"), refsCaptor.capture(), any(), any(), anyOrNull())
        org.junit.jupiter.api.Assertions.assertEquals("model timed out", refsCaptor.firstValue["llmError"])
    }

    @Test
    fun `an exception thrown by message-ack itself is caught by the outer safety net and the message is nak'd`() {
        // message.ack() is called directly inside handle()'s own try/catch (not inside
        // processJob's — which swallows every exception it can see internally, by design, so it
        // never escapes). A real NATS client's ack() can genuinely throw an IOException; this is
        // the one legitimate way handle()'s own outer catch (as opposed to any of the
        // per-branch catches inside processJob) is ever reached.
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-ackfail"), any(), any(), any(), anyOrNull())).thenReturn(
            ExportResult("invoice/job-ackfail.pdf", "invoice/job-ackfail.xml", true, "kosit-ok", "hash")
        )
        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-ackfail", includeInvoiceWrapper = true))
        doThrow(RuntimeException("ack failed")).whenever(message).ack()

        consumer.receive(message)

        verify(message).nak()
    }

    @Test
    fun `a FAILED path with a null exception message still reports cleanly`() {
        System.setProperty("job.retry.count", "0")
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-nullmsg"), any(), any(), any(), anyOrNull()))
            .thenThrow(RuntimeException(null as String?))

        val consumer = newConsumer(pdfExportPort, resultPublisher)
        val message = message(validMessage("job-nullmsg", includeInvoiceWrapper = true))

        val errorCaptor = argumentCaptor<Map<String, String>?>()
        consumer.receive(message)

        verify(resultPublisher).publishResult(eq("job-nullmsg"), eq("FAILED"), errorCaptor.capture(), any(), any(), anyOrNull())
        org.junit.jupiter.api.Assertions.assertEquals("", errorCaptor.firstValue?.get("error"))
        verify(message).ack()
    }

    // ── AP-M2 — in-progress heartbeat ─────────
    // Registered in handle() at fixed rate ackWait/3, deregistered in handle()'s own finally.
    // The real, shared `heartbeatExecutor` schedules its first tick 40s out by default — far
    // outside any unit test's runtime — so these tests substitute a mock to (a) capture the
    // exact scheduling parameters instead of waiting for a timer, and (b) invoke the captured
    // Runnable directly to exercise its own try/catch around message.inProgress().

    @Test
    fun `handle schedules a heartbeat at ackWait divided by three and cancels it once the job finishes`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-heartbeat"), any(), any(), any(), anyOrNull())).thenReturn(
            ExportResult("invoice/job-heartbeat.pdf", "invoice/job-heartbeat.xml", true, "kosit-ok", "hash")
        )
        val heartbeatExecutor: ScheduledExecutorService = mock()
        val future: ScheduledFuture<*> = mock()
        val runnableCaptor = argumentCaptor<Runnable>()
        val initialDelayCaptor = argumentCaptor<Long>()
        val periodCaptor = argumentCaptor<Long>()
        val unitCaptor = argumentCaptor<TimeUnit>()
        whenever(
            heartbeatExecutor.scheduleAtFixedRate(
                runnableCaptor.capture(), initialDelayCaptor.capture(), periodCaptor.capture(), unitCaptor.capture(),
            )
        ).thenReturn(future)

        val consumer = newConsumer(pdfExportPort, resultPublisher).apply { this.heartbeatExecutor = heartbeatExecutor }
        val message = message(validMessage("job-heartbeat", includeInvoiceWrapper = true))

        consumer.receive(message)

        // default ackWaitSeconds = 120 -> heartbeatIntervalSeconds = 120 / 3 = 40
        assertEquals(40L, initialDelayCaptor.firstValue)
        assertEquals(40L, periodCaptor.firstValue)
        assertEquals(TimeUnit.SECONDS, unitCaptor.firstValue)
        verify(future).cancel(false)

        // the captured Runnable never runs on its own within this test's lifetime — invoke it
        // directly to prove it actually calls message.inProgress() (the heartbeat's whole point).
        runnableCaptor.firstValue.run()
        verify(message).inProgress()
    }

    @Test
    fun `the heartbeat runnable logs and swallows a failure sending inProgress, without failing the job`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-heartbeat-fail"), any(), any(), any(), anyOrNull())).thenReturn(
            ExportResult("invoice/job-heartbeat-fail.pdf", "invoice/job-heartbeat-fail.xml", true, "kosit-ok", "hash")
        )
        val heartbeatExecutor: ScheduledExecutorService = mock()
        val future: ScheduledFuture<*> = mock()
        val runnableCaptor = argumentCaptor<Runnable>()
        whenever(heartbeatExecutor.scheduleAtFixedRate(runnableCaptor.capture(), any(), any(), any()))
            .thenReturn(future)

        val consumer = newConsumer(pdfExportPort, resultPublisher).apply { this.heartbeatExecutor = heartbeatExecutor }
        val message = message(validMessage("job-heartbeat-fail", includeInvoiceWrapper = true))
        doThrow(RuntimeException("nats hiccup")).whenever(message).inProgress()

        consumer.receive(message)

        // the job itself must complete normally regardless of the (still-unexecuted) heartbeat
        verify(resultPublisher).publishResult(eq("job-heartbeat-fail"), eq("COMPLETED"), any(), any(), any(), anyOrNull())
        verify(message).ack()

        // now exercise the runnable's own catch branch directly — if the catch didn't swallow
        // the failure, this call itself would throw and fail the test.
        runnableCaptor.firstValue.run()
        verify(message).inProgress()
    }

    // ── The heartbeat gap: under a burst exceeding the worker pool's immediate
    // capacity, a queued (not hung) message used to get no lease protection at all until a worker
    // thread actually started running it — potentially longer than ackWaitSeconds/
    // staleCutoffMinutes. receive() now schedules the heartbeat before handing the message to the
    // executor, so this is observable synchronously even while a second job is still queued behind
    // a first one that is genuinely still running. Deliberately does NOT use `newConsumer` (which
    // always swaps in a synchronous executor): this test needs a real, bounded background worker
    // pool actually occupied by job A while job B sits in its queue. ────────────────────────────

    @Test
    fun `ARC91-06 receive schedules the heartbeat for a queued message immediately, before a worker thread ever starts processing it`() {
        System.setProperty("job.worker.threads", "1")

        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        val startedProcessingA = java.util.concurrent.CountDownLatch(1)
        val releaseA = java.util.concurrent.CountDownLatch(1)
        whenever(pdfExportPort.performExport(any(), eq("job-burst-a"), any(), any(), any(), anyOrNull())).thenAnswer {
            startedProcessingA.countDown()
            releaseA.await(5, TimeUnit.SECONDS)
            ExportResult("invoice/job-burst-a.pdf", "invoice/job-burst-a.xml", true, "kosit-ok", "hash-a")
        }
        whenever(pdfExportPort.performExport(any(), eq("job-burst-b"), any(), any(), any(), anyOrNull())).thenReturn(
            ExportResult("invoice/job-burst-b.pdf", "invoice/job-burst-b.xml", true, "kosit-ok", "hash-b")
        )

        val heartbeatExecutor: ScheduledExecutorService = mock()
        val future: ScheduledFuture<*> = mock()
        whenever(heartbeatExecutor.scheduleAtFixedRate(any(), any(), any(), any())).thenReturn(future)

        val consumer = newConsumer(pdfExportPort, resultPublisher, syncExecutor = false).apply {
            this.heartbeatExecutor = heartbeatExecutor
        }

        try {
            consumer.receive(message(validMessage("job-burst-a", includeInvoiceWrapper = true)))
            assertTrue(
                startedProcessingA.await(5, TimeUnit.SECONDS),
                "job A must actually start running on the (only) worker thread before job B is submitted",
            )

            // Job B is handed to the same bounded executor while its single worker thread is still
            // busy running job A's (genuinely in-progress, not hung) export — exactly the burst
            // shape that queues more work than the pool can immediately start. Previously,
            // scheduleHeartbeat only ran at the top of handle(), so job B's heartbeat would not be
            // scheduled until this same worker thread later reached it — only after job A finished.
            consumer.receive(message(validMessage("job-burst-b", includeInvoiceWrapper = true)))

            // Verified on THIS (calling) thread, without job B ever starting to run: receive()
            // itself already scheduled job B's heartbeat before handing it to the executor.
            verify(heartbeatExecutor, times(2)).scheduleAtFixedRate(any(), any(), any(), any())
        } finally {
            releaseA.countDown()
        }

        val pool = consumer.executor as java.util.concurrent.ThreadPoolExecutor
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "both queued jobs must finish once job A is released")

        verify(resultPublisher).publishResult(eq("job-burst-a"), eq("COMPLETED"), any(), any(), any(), anyOrNull())
        verify(resultPublisher).publishResult(eq("job-burst-b"), eq("COMPLETED"), any(), any(), any(), anyOrNull())
        verify(future, times(2)).cancel(false)
    }

    @Test
    fun `ackWaitSeconds is read from the system property, reflected in the heartbeat schedule interval`() {
        System.setProperty("nats.consumer.ack-wait-seconds", "30")
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-ackwait-prop"), any(), any(), any(), anyOrNull())).thenReturn(
            ExportResult("invoice/job-ackwait-prop.pdf", "invoice/job-ackwait-prop.xml", true, "kosit-ok", "hash")
        )
        val heartbeatExecutor: ScheduledExecutorService = mock()
        val future: ScheduledFuture<*> = mock()
        val initialDelayCaptor = argumentCaptor<Long>()
        whenever(heartbeatExecutor.scheduleAtFixedRate(any(), initialDelayCaptor.capture(), any(), any()))
            .thenReturn(future)

        val consumer = newConsumer(pdfExportPort, resultPublisher).apply { this.heartbeatExecutor = heartbeatExecutor }
        val message = message(validMessage("job-ackwait-prop", includeInvoiceWrapper = true))

        consumer.receive(message)

        assertEquals(10L, initialDelayCaptor.firstValue) // 30 / 3
    }

    @Test
    fun `ackWaitSeconds falls back to the environment variable when no system property is set`() {
        mockStatic(Class.forName("de.littletrotter.xpdf.EnvConfig")).use { staticEnv ->
            staticEnv.`when`<String?> { de.littletrotter.xpdf.readEnv("JOB_RETRY_COUNT") }.thenReturn(null)
            staticEnv.`when`<String?> { de.littletrotter.xpdf.readEnv("JOB_RETRY_BACKOFF_MS") }.thenReturn(null)
            staticEnv.`when`<String?> { de.littletrotter.xpdf.readEnv("S3_ENDPOINT") }.thenReturn(null)
            staticEnv.`when`<String?> { de.littletrotter.xpdf.readEnv("S3_BUCKET") }.thenReturn(null)
            staticEnv.`when`<String?> { de.littletrotter.xpdf.readEnv("JOB_WORKER_THREADS") }.thenReturn(null)
            staticEnv.`when`<String?> { de.littletrotter.xpdf.readEnv("NATS_CONSUMER_ACK_WAIT_SECONDS") }.thenReturn("60")

            val pdfExportPort: PdfExportPort = mock()
            val resultPublisher: ResultPublisher = mock()
            whenever(pdfExportPort.performExport(any(), eq("job-ackwait-env"), any(), any(), any(), anyOrNull())).thenReturn(
                ExportResult("invoice/job-ackwait-env.pdf", "invoice/job-ackwait-env.xml", true, "kosit-ok", "hash")
            )
            val heartbeatExecutor: ScheduledExecutorService = mock()
            val future: ScheduledFuture<*> = mock()
            val initialDelayCaptor = argumentCaptor<Long>()
            whenever(heartbeatExecutor.scheduleAtFixedRate(any(), initialDelayCaptor.capture(), any(), any()))
                .thenReturn(future)

            val consumer = newConsumer(pdfExportPort, resultPublisher).apply {
                this.heartbeatExecutor = heartbeatExecutor
            }
            val message = message(validMessage("job-ackwait-env", includeInvoiceWrapper = true))

            consumer.receive(message)

            assertEquals(20L, initialDelayCaptor.firstValue) // 60 / 3
        }
    }

    @Test
    fun `heartbeatExecutor defaults to a real scheduled executor, reachable via its internal getter`() {
        val consumer = JobConsumer(mock(), mock(), mock(), mock(), mock())
        assertNotNull(consumer.heartbeatExecutor)
    }

    private fun newConsumer(
        pdfExportPort: PdfExportPort,
        resultPublisher: ResultPublisher,
        jetStream: JetStream = mock(),
        // A fresh mock by default (get() returns null); tests exercising ref resolution pass their own.
        payloadExchange: de.littletrotter.shared.PayloadExchange = mock(),
        connection: Connection = mock(),
        // AP-M4 — a few tests (the burst/backpressure ones) need the REAL bounded
        // ThreadPoolExecutor to observe queuing behaviour; every other test wants receive() to
        // run synchronously so assertions right after it see the completed result deterministically.
        syncExecutor: Boolean = true,
    ): JobConsumer {
        // JobConsumer no longer takes pdfExportPort/resultPublisher/payloadExchange
        // directly; it delegates to PoisonMessageHandler/JobDispatcher, built here the same way
        // CDI would wire them in production.
        val resultReporter = JobResultReporter(resultPublisher)
        val dispatcher = JobDispatcher(pdfExportPort, payloadExchange, resultReporter)
        // dlqSubject/jobsSubject/durableConsumerName are now plain constructor
        // parameters (Kotlin defaults "document-jobs-dlq"/"document-jobs"/"kotlin-xpdf-group",
        // matching their @ConfigProperty defaultValue): no reflection seam needed any more.
        val poisonHandler = PoisonMessageHandler(resultPublisher, jetStream)
        return JobConsumer(connection, jetStream, poisonHandler, dispatcher, mock()).apply {
            if (syncExecutor) executor = java.util.concurrent.Executor { it.run() }
        }
    }

    private fun message(payload: String, subject: String = "document-jobs"): Message {
        val msg: Message = mock()
        whenever(msg.data).thenReturn(payload.toByteArray(Charsets.UTF_8))
        whenever(msg.subject).thenReturn(subject)
        return msg
    }

    private fun validMessage(
        jobId: String,
        includeInvoiceWrapper: Boolean,
        llmOptimize: Boolean = false,
        legacyOllamaOptimize: Boolean = false,
        veraPdfCheck: Boolean = false,
        engine: String? = null,
    ): String {
        val payload = if (includeInvoiceWrapper) {
            """
                "payload": {
                  "invoice": {
                    "invoiceNumber": { "value": "INV-1" },
                    "items": []
                  }
                }
            """.trimIndent()
        } else {
            """
                "payload": {
                  "invoiceNumber": { "value": "INV-1" },
                  "items": []
                }
            """.trimIndent()
        }

        val options = when {
            llmOptimize -> """,
              "options": {
                "llmOptimize": true
              }"""
            legacyOllamaOptimize -> """,
              "options": {
                "ollamaOptimize": true
              }"""
            veraPdfCheck -> """,
              "options": {
                "veraPdfCheck": true
              }"""
            else -> ""
        }

        val engineField = if (engine != null) """,
              "engine": "$engine"""" else ""

        val msg = """
            {
              "id": "$jobId",
              "type": "GENERATE",
              "correlationId": "corr-1",
              $payload$options$engineField
            }
        """.trimIndent()

        assertTrue(msg.contains("\"id\": \"$jobId\""))
        return msg
    }

    // ── Trip workbookRef resolution before rendering ──────────────────────────────────────

    private fun jobMessageWithItems(jobId: String, itemsJson: String): String = """
        {
          "id": "$jobId",
          "type": "GENERATE",
          "correlationId": "corr-trip",
          "payload": {
            "invoice": {
              "invoiceNumber": { "value": "INV-TRIP" },
              "items": [$itemsJson]
            }
          }
        }
    """.trimIndent()

    private fun tripItemJson(positionId: String, workbookRefJson: String): String = """
        {
          "positionId": "$positionId",
          "category": "TRIP",
          "taxRatePercent": 19,
          "trip": {
            "detailedOnInvoice": false,
            "fixedNetPrice": null,
            "calculatedNet": { "amount": 0 },
            "workbookRef": $workbookRefJson
          }
        }
    """.trimIndent()

    private fun serviceItemJson(positionId: String): String = """
        {
          "positionId": "$positionId",
          "category": "SERVICE",
          "taxRatePercent": 19
        }
    """.trimIndent()

    @Test
    fun `RK-4 - a resolvable trip workbookRef is fetched from PayloadExchange and reaches the export service as resolvedWorkbookBase64`() {
        val workbookContent = "base64-workbook-content"
        val sha256 = de.littletrotter.shared.PayloadExchange.sha256Hex(workbookContent)
        val key = "lt:tripwb:$sha256"
        val payloadExchange: de.littletrotter.shared.PayloadExchange = mock()
        whenever(payloadExchange.get(key)).thenReturn(workbookContent)

        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        val invoiceCaptor = org.mockito.kotlin.argumentCaptor<de.littletrotter.shared.InvoiceDto>()
        whenever(pdfExportPort.performExport(invoiceCaptor.capture(), eq("job-trip-ok"), any(), any(), any(), anyOrNull())).thenReturn(
            ExportResult("invoice/job-trip-ok.pdf", "invoice/job-trip-ok.xml", true, "kosit-ok", "sha")
        )

        val itemsJson = tripItemJson("1", """{ "key": "$key", "sha256": "$sha256", "fileName": "trip.xlsx" }""")
        val consumer = newConsumer(pdfExportPort, resultPublisher, payloadExchange = payloadExchange)
        consumer.receive(message(jobMessageWithItems("job-trip-ok", itemsJson)))

        verify(pdfExportPort).performExport(any(), eq("job-trip-ok"), any(), any(), any(), anyOrNull())
        assertEquals(workbookContent, invoiceCaptor.firstValue.items.single().trip?.resolvedWorkbookBase64)
        verify(resultPublisher).publishResult(eq("job-trip-ok"), eq("COMPLETED"), any(), any(), any(), anyOrNull())
    }

    @Test
    fun `RK-4 - a missing trip workbookRef key leaves resolvedWorkbookBase64 null and still completes the job`() {
        val key = "lt:tripwb:${"0".repeat(64)}"
        val sha256 = "0".repeat(64)
        val payloadExchange: de.littletrotter.shared.PayloadExchange = mock()
        whenever(payloadExchange.get(key)).thenReturn(null)

        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        val invoiceCaptor = org.mockito.kotlin.argumentCaptor<de.littletrotter.shared.InvoiceDto>()
        whenever(pdfExportPort.performExport(invoiceCaptor.capture(), eq("job-trip-missing"), any(), any(), any(), anyOrNull())).thenReturn(
            ExportResult("invoice/job-trip-missing.pdf", "invoice/job-trip-missing.xml", true, "kosit-ok", "sha")
        )

        val itemsJson = tripItemJson("1", """{ "key": "$key", "sha256": "$sha256", "fileName": "trip.xlsx" }""")
        val consumer = newConsumer(pdfExportPort, resultPublisher, payloadExchange = payloadExchange)
        consumer.receive(message(jobMessageWithItems("job-trip-missing", itemsJson)))

        assertEquals(null, invoiceCaptor.firstValue.items.single().trip?.resolvedWorkbookBase64)
        verify(resultPublisher).publishResult(eq("job-trip-missing"), eq("COMPLETED"), any(), any(), any(), anyOrNull())
    }

    @Test
    fun `RK-4 - a trip workbookRef whose resolved content no longer matches sha256 also leaves resolvedWorkbookBase64 null`() {
        val key = "lt:tripwb:${"a".repeat(64)}"
        val expectedSha256 = "a".repeat(64)
        val payloadExchange: de.littletrotter.shared.PayloadExchange = mock()
        // Content stored under the key no longer hashes to what the job references (Valkey
        // eviction/rewrite race) — same race the trip workbook resolution guards against, but degrading
        // instead of failing the job.
        whenever(payloadExchange.get(key)).thenReturn("different-content")

        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        val invoiceCaptor = org.mockito.kotlin.argumentCaptor<de.littletrotter.shared.InvoiceDto>()
        whenever(pdfExportPort.performExport(invoiceCaptor.capture(), eq("job-trip-mismatch"), any(), any(), any(), anyOrNull())).thenReturn(
            ExportResult("invoice/job-trip-mismatch.pdf", "invoice/job-trip-mismatch.xml", true, "kosit-ok", "sha")
        )

        val itemsJson = tripItemJson("1", """{ "key": "$key", "sha256": "$expectedSha256", "fileName": "trip.xlsx" }""")
        val consumer = newConsumer(pdfExportPort, resultPublisher, payloadExchange = payloadExchange)
        consumer.receive(message(jobMessageWithItems("job-trip-mismatch", itemsJson)))

        assertEquals(null, invoiceCaptor.firstValue.items.single().trip?.resolvedWorkbookBase64)
        verify(resultPublisher).publishResult(eq("job-trip-mismatch"), eq("COMPLETED"), any(), any(), any(), anyOrNull())
    }

    @Test
    fun `RK-4 - an invoice with no TRIP items never touches PayloadExchange for workbook resolution`() {
        val payloadExchange: de.littletrotter.shared.PayloadExchange = mock()
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-trip-none"), any(), any(), any(), anyOrNull())).thenReturn(
            ExportResult("invoice/job-trip-none.pdf", "invoice/job-trip-none.xml", true, "kosit-ok", "sha")
        )

        val itemsJson = serviceItemJson("1")
        val consumer = newConsumer(pdfExportPort, resultPublisher, payloadExchange = payloadExchange)
        consumer.receive(message(jobMessageWithItems("job-trip-none", itemsJson)))

        verify(payloadExchange, never()).get(any())
        verify(resultPublisher).publishResult(eq("job-trip-none"), eq("COMPLETED"), any(), any(), any(), anyOrNull())
    }

    @Test
    fun `RK-4 - a TRIP item with a null workbookRef and a non-TRIP item both pass through unresolved, alongside a resolving sibling item`() {
        val workbookContent = "base64-workbook-content-2"
        val sha256 = de.littletrotter.shared.PayloadExchange.sha256Hex(workbookContent)
        val key = "lt:tripwb:$sha256"
        val payloadExchange: de.littletrotter.shared.PayloadExchange = mock()
        whenever(payloadExchange.get(key)).thenReturn(workbookContent)

        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        val invoiceCaptor = org.mockito.kotlin.argumentCaptor<de.littletrotter.shared.InvoiceDto>()
        whenever(pdfExportPort.performExport(invoiceCaptor.capture(), eq("job-trip-mixed"), any(), any(), any(), anyOrNull())).thenReturn(
            ExportResult("invoice/job-trip-mixed.pdf", "invoice/job-trip-mixed.xml", true, "kosit-ok", "sha")
        )

        // item 1 resolves normally, item 2 is a TRIP item whose workbookRef is null (nothing to
        // resolve), item 3 has no trip at all (not a TRIP category) — all three coexist in one
        // invoice so resolveTripWorkbookRefs' per-item branch (trip == null || ref == null) sees
        // every outcome in a single job.
        val resolvingItem = tripItemJson("1", """{ "key": "$key", "sha256": "$sha256", "fileName": "trip.xlsx" }""")
        val nullRefItem = tripItemJson("2", "null")
        val noTripItem = serviceItemJson("3")
        val itemsJson = "$resolvingItem, $nullRefItem, $noTripItem"

        val consumer = newConsumer(pdfExportPort, resultPublisher, payloadExchange = payloadExchange)
        consumer.receive(message(jobMessageWithItems("job-trip-mixed", itemsJson)))

        val items = invoiceCaptor.firstValue.items
        assertEquals(workbookContent, items[0].trip?.resolvedWorkbookBase64)
        assertEquals(null, items[1].trip?.resolvedWorkbookBase64)
        assertEquals(null, items[2].trip)
        verify(resultPublisher).publishResult(eq("job-trip-mixed"), eq("COMPLETED"), any(), any(), any(), anyOrNull())
    }

    // ── JobConsumer.defaultWorkerThreads' own memory formula ────────────────────────────────────

    @Test
    fun `defaultWorkerThreads scales up with a larger heap ceiling`() {
        val small = JobConsumer.defaultWorkerThreads(maxHeapBytes = 700L * 1024 * 1024)
        val large = JobConsumer.defaultWorkerThreads(maxHeapBytes = 2_000L * 1024 * 1024)
        assertTrue(large > small, "a larger heap ceiling must derive a larger default thread count")
    }

    @Test
    fun `defaultWorkerThreads never returns fewer than the documented minimum, even for a heap under the boot reserve`() {
        val result = JobConsumer.defaultWorkerThreads(maxHeapBytes = 100L * 1024 * 1024)
        assertEquals(JobConsumer.MIN_WORKER_THREADS, result, "a heap too small for even the boot reserve must still return the floor, never zero or negative")
    }

    @Test
    fun `defaultWorkerThreads never exceeds the documented maximum, even for an unbounded-looking heap`() {
        val result = JobConsumer.defaultWorkerThreads(maxHeapBytes = Long.MAX_VALUE / 2)
        assertEquals(JobConsumer.MAX_WORKER_THREADS, result, "the formula must stay capped regardless of how large the heap ceiling reports")
    }

    @Test
    fun `defaultWorkerThreads at the previous known-crashing 768 MiB limit stays low`() {
        // The 2026-09-18 stress finding's own numbers: -Xmx512m inside a 768 MiB container limit,
        // fixed threads=4 -- this proves the new formula would never have picked 4 at that heap
        // size, i.e. it genuinely changes the previously-crashing default, not just the code path.
        val result = JobConsumer.defaultWorkerThreads(maxHeapBytes = 512L * 1024 * 1024)
        assertTrue(result < 4, "at the previous crashing heap size the formula must pick fewer worker threads than the old fixed default")
    }

    @Test
    fun `receive handles excessive redelivery with a malformed payload and no jobId header`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        val message = message("not-json-at-all")
        whenever(message.headers?.getFirst("jobId")).thenReturn(null)
        val consumer = newConsumer(pdfExportPort, resultPublisher)

        consumer.receive(message)

        verify(pdfExportPort, never()).performExport(any(), any(), any(), any(), any(), anyOrNull())
        verify(resultPublisher, never()).publishResult(any(), any(), any(), any(), any(), anyOrNull())
        verify(message).ack()
        verify(message, never()).nak()
    }

    @Test
    fun `receive handles excessive redelivery with a malformed payload but a jobId header`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        val message: io.nats.client.Message = mock()
        whenever(message.data).thenReturn("not-json-at-all".toByteArray(Charsets.UTF_8))
        whenever(message.subject).thenReturn("document-jobs")
        val headers: io.nats.client.impl.Headers = mock()
        whenever(headers.getFirst("jobId")).thenReturn("job-poison-redeliver")
        whenever(message.headers).thenReturn(headers)
        val consumer = newConsumer(pdfExportPort, resultPublisher)

        consumer.receive(message)

        verify(pdfExportPort, never()).performExport(any(), any(), any(), any(), any(), anyOrNull())
        val errorCaptor = org.mockito.kotlin.argumentCaptor<Map<String, String>?>()
        verify(resultPublisher).publishResult(eq("job-poison-redeliver"), eq("FAILED"), errorCaptor.capture(), eq(""), eq(null), anyOrNull())
        val error = errorCaptor.firstValue?.get("error") ?: ""
        assertTrue(error.contains("redelivered") || error.contains("unparseable"), "error message should mention redelivered or unparseable: $error")
        verify(message).ack()
        verify(message, never()).nak()
    }

    @Test
    fun `receive handles excessive redelivery with a malformed payload and correlationId header`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        val message: io.nats.client.Message = mock()
        whenever(message.data).thenReturn("not-json-at-all".toByteArray(Charsets.UTF_8))
        whenever(message.subject).thenReturn("document-jobs")
        val headers: io.nats.client.impl.Headers = mock()
        whenever(headers.getFirst("jobId")).thenReturn("job-poison-correlation")
        whenever(headers.getFirst("correlationId")).thenReturn("corr-12345")
        whenever(message.headers).thenReturn(headers)
        val consumer = newConsumer(pdfExportPort, resultPublisher)

        consumer.receive(message)

        verify(pdfExportPort, never()).performExport(any(), any(), any(), any(), any(), anyOrNull())
        val errorCaptor = org.mockito.kotlin.argumentCaptor<Map<String, String>?>()
        verify(resultPublisher).publishResult(eq("job-poison-correlation"), eq("FAILED"), errorCaptor.capture(), eq(""), eq(null), anyOrNull())
        val error = errorCaptor.firstValue?.get("error") ?: ""
        assertTrue(error.contains("redelivered") || error.contains("unparseable"), "error message should mention redelivered or unparseable: $error")
        verify(message).ack()
        verify(message, never()).nak()
    }

    @Test
    fun `receive handles unparseable message with correlationId in payload`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        val message: io.nats.client.Message = mock()
        whenever(message.data).thenReturn("""{"id":"unparseable-123","type":"PDF_EXPORT","correlationId":"corr-456"}""".toByteArray(Charsets.UTF_8))
        whenever(message.subject).thenReturn("document-jobs")
        val headers: io.nats.client.impl.Headers = mock()
        whenever(headers.getFirst("jobId")).thenReturn("unparseable-123")
        whenever(message.headers).thenReturn(headers)
        val consumer = newConsumer(pdfExportPort, resultPublisher)

        consumer.receive(message)

        verify(pdfExportPort, never()).performExport(any(), any(), any(), any(), any(), anyOrNull())
        val errorCaptor = org.mockito.kotlin.argumentCaptor<Map<String, String>?>()
        verify(resultPublisher).publishResult(eq("unparseable-123"), eq("FAILED"), errorCaptor.capture(), eq(""), eq("corr-456"), anyOrNull())
        val error = errorCaptor.firstValue?.get("error") ?: ""
        assertTrue(error.contains("unmappable"), "error message should mention unmappable: $error")
        verify(message).ack()
        verify(message, never()).nak()
    }

    @Test
    fun `receive handles excessive redelivery with malformed payload and no jobId header`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        val message: io.nats.client.Message = mock()
        whenever(message.data).thenReturn("""{"id":"unparseable-123","type":"PDF_EXPORT","correlationId":"corr-456"}""".toByteArray(Charsets.UTF_8))
        whenever(message.subject).thenReturn("document-jobs")
        val headers: io.nats.client.impl.Headers = mock()
        whenever(headers.getFirst("jobId")).thenReturn("unparseable-123")
        whenever(message.headers).thenReturn(headers)
        val consumer = newConsumer(pdfExportPort, resultPublisher)

        consumer.receive(message)

        verify(pdfExportPort, never()).performExport(any(), any(), any(), any(), any(), anyOrNull())
        val errorCaptor = org.mockito.kotlin.argumentCaptor<Map<String, String>?>()
        verify(resultPublisher).publishResult(eq("unparseable-123"), eq("FAILED"), errorCaptor.capture(), eq(""), eq("corr-456"), anyOrNull())
        val error = errorCaptor.firstValue?.get("error") ?: ""
        assertTrue(error.contains("unmappable"), "error message should mention unmappable: $error")
        verify(message).ack()
        verify(message, never()).nak()
    }

    @Test
    fun `receive handles unparseable message with no jobId header`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        val message: io.nats.client.Message = mock()
        whenever(message.data).thenReturn("not-json-at-all".toByteArray(Charsets.UTF_8))
        whenever(message.subject).thenReturn("document-jobs")
        val headers: io.nats.client.impl.Headers = mock()
        whenever(message.headers).thenReturn(headers)
        val consumer = newConsumer(pdfExportPort, resultPublisher)

        consumer.receive(message)

        verify(pdfExportPort, never()).performExport(any(), any(), any(), any(), any(), anyOrNull())
        verify(resultPublisher, never()).publishResult(any(), any(), any(), any(), any(), anyOrNull())
        verify(message).ack()
        verify(message, never()).nak()
    }
}
