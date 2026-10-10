package de.littletrotter.xpdf.infrastructure.messaging

import io.nats.client.JetStream
import io.nats.client.Message
import io.nats.client.impl.Headers
import io.nats.client.impl.NatsJetStreamMetaData
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Dedicated unit coverage for [PoisonMessageHandler], split out of `JobConsumer`. The
 * composed pipeline (decode -> dispatch -> poison-path) stays covered end-to-end by
 * [JobConsumerTest]; this file tests the unit's own decisions in isolation, constructed directly.
 */
class PoisonMessageHandlerTest {

    @AfterEach
    fun clearSystemProperties() {
        System.clearProperty("job.retry.count")
    }

    // dlqSubject is now a plain constructor parameter (Kotlin default
    // "document-jobs-dlq", matching its @ConfigProperty defaultValue): no reflection seam needed.
    private fun newHandler(resultPublisher: ResultPublisher, jetStream: JetStream = mock()): PoisonMessageHandler =
        PoisonMessageHandler(resultPublisher, jetStream)

    private fun messageWithDeliveredCount(deliveredCount: Long, headers: Headers? = null): Message {
        val message: Message = mock()
        whenever(message.data).thenReturn("payload".toByteArray(Charsets.UTF_8))
        whenever(message.subject).thenReturn("document-jobs")
        whenever(message.headers).thenReturn(headers)
        val metaData: NatsJetStreamMetaData = mock()
        whenever(metaData.deliveredCount()).thenReturn(deliveredCount)
        whenever(message.metaData()).thenReturn(metaData)
        return message
    }

    @Test
    fun `isExcessivelyRedelivered is false for a first delivery (happy path)`() {
        val handler = newHandler(mock())
        assertFalse(handler.isExcessivelyRedelivered(messageWithDeliveredCount(1)))
    }

    @Test
    fun `isExcessivelyRedelivered is true once deliveredCount exceeds retryCount plus one`() {
        System.setProperty("job.retry.count", "2")
        val handler = newHandler(mock())
        assertTrue(handler.isExcessivelyRedelivered(messageWithDeliveredCount(4)))
        assertFalse(handler.isExcessivelyRedelivered(messageWithDeliveredCount(3)))
    }

    @Test
    fun `isExcessivelyRedelivered stays inert on a first delivery even with a negative retryCount`() {
        System.setProperty("job.retry.count", "-1")
        val handler = newHandler(mock())
        assertFalse(handler.isExcessivelyRedelivered(messageWithDeliveredCount(1)))
    }

    @Test
    fun `isExcessivelyRedelivered treats a metaData lookup failure as a first delivery`() {
        val message: Message = mock()
        // IllegalStateException, not a bare RuntimeException: the real contract Message.metaData()
        // declares for a message that isn't actually a JetStream delivery.
        whenever(message.metaData()).thenThrow(IllegalStateException("not a JetStream delivery"))
        val handler = newHandler(mock())
        assertFalse(handler.isExcessivelyRedelivered(message))
    }

    @Test
    fun `handleExcessiveRedelivery publishes a FAILED result and acks when a jobId header is present`() {
        val resultPublisher: ResultPublisher = mock()
        val headers = Headers().add("jobId", "job-redelivered")
        val message = messageWithDeliveredCount(5, headers)
        val handler = newHandler(resultPublisher)

        handler.handleExcessiveRedelivery(message)

        val errorCaptor = argumentCaptor<Map<String, String>?>()
        verify(resultPublisher).publishResult(eq("job-redelivered"), eq("FAILED"), errorCaptor.capture(), eq(""), isNull(), anyOrNull())
        assertTrue(errorCaptor.firstValue?.get("error")?.contains("redelivered 5 time(s)") == true)
        verify(message).ack()
    }

    @Test
    fun `handleExcessiveRedelivery drops the message with only an ack when no jobId is recoverable`() {
        val resultPublisher: ResultPublisher = mock()
        val message = messageWithDeliveredCount(5, headers = null)
        val handler = newHandler(resultPublisher)

        handler.handleExcessiveRedelivery(message)

        verify(resultPublisher, never()).publishResult(any(), any(), any(), any(), any(), anyOrNull())
        verify(message).ack()
        verify(message, never()).nak()
    }

    @Test
    fun `handleUnparseableMessage publishes a FAILED result via the jobId header and acks`() {
        val resultPublisher: ResultPublisher = mock()
        val headers = Headers().add("jobId", "job-unparseable")
        val message: Message = mock()
        whenever(message.headers).thenReturn(headers)
        val handler = newHandler(resultPublisher)

        handler.handleUnparseableMessage(message, RuntimeException("broken json"))

        val errorCaptor = argumentCaptor<Map<String, String>?>()
        verify(resultPublisher).publishResult(eq("job-unparseable"), eq("FAILED"), errorCaptor.capture(), eq(""), isNull(), anyOrNull())
        assertTrue(errorCaptor.firstValue?.get("error")?.contains("unparseable job payload") == true)
        verify(message).ack()
    }

    @Test
    fun `handleUnparseableMessage drops the message when it carries no jobId header at all`() {
        val resultPublisher: ResultPublisher = mock()
        val message: Message = mock()
        whenever(message.headers).thenReturn(null)
        val handler = newHandler(resultPublisher)

        handler.handleUnparseableMessage(message, RuntimeException("broken json"))

        verify(resultPublisher, never()).publishResult(any(), any(), any(), any(), any(), anyOrNull())
        verify(message).ack()
    }

    @Test
    fun `handleUnmappableInvoice publishes a FAILED result using the already-parsed job id and correlationId`() {
        val resultPublisher: ResultPublisher = mock()
        val message: Message = mock()
        val job = de.littletrotter.shared.documentjob.DocumentJobRequest(id = "job-unmappable", type = "GENERATE", correlationId = "corr-1")
        val handler = newHandler(resultPublisher)

        handler.handleUnmappableInvoice(message, job, IllegalArgumentException("no invoice in payload"))

        val errorCaptor = argumentCaptor<Map<String, String>?>()
        verify(resultPublisher).publishResult(eq("job-unmappable"), eq("FAILED"), errorCaptor.capture(), eq(""), eq("corr-1"), anyOrNull())
        assertTrue(errorCaptor.firstValue?.get("error")?.contains("unmappable job payload") == true)
        verify(message).ack()
    }

    @Test
    fun `a poison FAILED-publish failure propagates -- no local catch, the caller's own boundary guarantees the ack`() {
        // PoisonMessageHandler no longer catches its own best-effort publish
        // failures; JobConsumer's own per-message catch is now the single place that still acks
        // a poison message no matter what. This test proves the propagation half of that
        // contract; JobConsumerTest's "still acked, not redelivered forever" tests prove the
        // other half through the full pipeline.
        val resultPublisher: ResultPublisher = mock()
        val jetStream: JetStream = mock()
        whenever(resultPublisher.publishResult(any(), any(), any(), any(), org.mockito.kotlin.anyOrNull(), anyOrNull()))
            .thenThrow(RuntimeException("nats down"))
        val message: Message = mock()
        whenever(message.data).thenReturn("broken".toByteArray(Charsets.UTF_8))
        whenever(message.subject).thenReturn("document-jobs")
        whenever(message.headers).thenReturn(Headers().add("jobId", "job-pubfail"))
        val handler = newHandler(resultPublisher, jetStream)

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException::class.java) {
            handler.handleUnparseableMessage(message, RuntimeException("broken"))
        }

        verify(jetStream, never()).publish(any<String>(), any<Headers>(), any())
        verify(message, never()).ack()
    }

    @Test
    fun `poison bytes are archived to the DLQ with jobId, reason and origSubject headers`() {
        val resultPublisher: ResultPublisher = mock()
        val jetStream: JetStream = mock()
        val message: Message = mock()
        whenever(message.data).thenReturn("garbage".toByteArray(Charsets.UTF_8))
        whenever(message.subject).thenReturn("document-jobs")
        whenever(message.headers).thenReturn(Headers().add("jobId", "job-dlq"))
        val handler = newHandler(resultPublisher, jetStream)

        handler.handleUnparseableMessage(message, RuntimeException("broken"))

        val headersCaptor = argumentCaptor<Headers>()
        verify(jetStream).publish(eq("document-jobs-dlq"), headersCaptor.capture(), any())
        assertEquals("job-dlq", headersCaptor.firstValue.getFirst("jobId"))
        assertEquals("document-jobs", headersCaptor.firstValue.getFirst("origSubject"))
        assertTrue(headersCaptor.firstValue.getFirst("reason")?.contains("unparseable job payload") == true)
    }

    @Test
    fun `a DLQ publish failure propagates -- no local catch, the caller's own boundary guarantees the ack`() {
        val resultPublisher: ResultPublisher = mock()
        val jetStream: JetStream = mock()
        doThrow(RuntimeException("dlq down")).whenever(jetStream).publish(any<String>(), any<Headers>(), any())
        val message: Message = mock()
        whenever(message.data).thenReturn("broken".toByteArray(Charsets.UTF_8))
        whenever(message.subject).thenReturn("document-jobs")
        whenever(message.headers).thenReturn(Headers().add("jobId", "job-dlqfail"))
        val handler = newHandler(resultPublisher, jetStream)

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException::class.java) {
            handler.handleUnparseableMessage(message, RuntimeException("broken"))
        }

        verify(resultPublisher).publishResult(any(), any(), any(), any(), org.mockito.kotlin.anyOrNull(), anyOrNull())
        verify(message, never()).ack()
        verify(message, never()).nak()
    }
}
