package de.littletrotter.xpdf.infrastructure.messaging

import de.littletrotter.shared.PayloadExchange
import de.littletrotter.shared.PdfExportPort
import io.nats.client.Connection
import io.nats.client.JetStream
import io.nats.client.Message
import io.nats.client.impl.Headers
import io.nats.client.impl.NatsJetStreamMetaData
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * A message that exhausted its redelivery budget while its JSON never parsed:
 * the poison report can only use the NATS `jobId` header, and without that header the message
 * is dropped. The parseable-payload case lives in [JobConsumerTest].
 */
class JobConsumerExcessiveRedeliveryTest {

    @AfterEach
    fun clearSystemProperties() {
        System.clearProperty("job.retry.count")
    }

    @Test
    fun `boundary - unparseable payload with a jobId header publishes a FAILED poison result without correlation id and acks`() {
        System.setProperty("job.retry.count", "1")
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        val headers: Headers = mock()
        whenever(headers.getFirst("jobId")).thenReturn("job-poison-header")
        val message = redeliveredMessage("not-json-at-all", headers, deliveredCount = 5)

        newConsumer(pdfExportPort, resultPublisher).receive(message)

        verify(pdfExportPort, never()).performExport(any(), any(), any(), any(), any(), anyOrNull())
        val errorCaptor = argumentCaptor<Map<String, String>?>()
        verify(resultPublisher).publishResult(eq("job-poison-header"), eq("FAILED"), errorCaptor.capture(), eq(""), isNull(), anyOrNull())
        assertTrue(errorCaptor.firstValue?.get("error")?.contains("redelivered 5 time(s)") == true)
        verify(message).ack()
        verify(message, never()).nak()
    }

    @Test
    fun `boundary - unparseable payload without any headers is dropped with an ack and no result`() {
        System.setProperty("job.retry.count", "1")
        val pdfExportPort: PdfExportPort = mock()
        val resultPublisher: ResultPublisher = mock()
        val message = redeliveredMessage("not-json-at-all", headers = null, deliveredCount = 3)

        newConsumer(pdfExportPort, resultPublisher).receive(message)

        verify(pdfExportPort, never()).performExport(any(), any(), any(), any(), any(), anyOrNull())
        verify(resultPublisher, never()).publishResult(any(), any(), any(), any(), any(), anyOrNull())
        verify(message).ack()
        verify(message, never()).nak()
    }

    private fun redeliveredMessage(payload: String, headers: Headers?, deliveredCount: Long): Message {
        val message: Message = mock()
        whenever(message.data).thenReturn(payload.toByteArray(Charsets.UTF_8))
        whenever(message.subject).thenReturn("document-jobs")
        whenever(message.headers).thenReturn(headers)
        val metaData: NatsJetStreamMetaData = mock()
        whenever(metaData.deliveredCount()).thenReturn(deliveredCount)
        whenever(message.metaData()).thenReturn(metaData)
        return message
    }

    // JobConsumer no longer takes pdfExportPort/resultPublisher/payloadExchange directly;
    // it delegates to PoisonMessageHandler/JobDispatcher, built here the same way CDI would wire
    // them in production.
    private fun newConsumer(pdfExportPort: PdfExportPort, resultPublisher: ResultPublisher): JobConsumer {
        val resultReporter = JobResultReporter(resultPublisher)
        val dispatcher = JobDispatcher(pdfExportPort, mock<PayloadExchange>(), resultReporter)
        // dlqSubject is now a plain constructor parameter (Kotlin default
        // "document-jobs-dlq", matching its @ConfigProperty defaultValue): no reflection needed.
        val poisonHandler = PoisonMessageHandler(resultPublisher, mock<JetStream>())
        return JobConsumer(mock<Connection>(), mock<JetStream>(), poisonHandler, dispatcher, mock()).apply {
            executor = java.util.concurrent.Executor { it.run() }
        }
    }
}
