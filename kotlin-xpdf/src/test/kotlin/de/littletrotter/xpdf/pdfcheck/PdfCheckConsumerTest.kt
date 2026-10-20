package de.littletrotter.xpdf.pdfcheck

import de.littletrotter.shared.documentjob.DocumentJobSubjects
import de.littletrotter.shared.documentjob.DocumentJobWireCodec
import de.littletrotter.shared.documentjob.PdfCheckRequest
import de.littletrotter.shared.documentjob.PdfCheckResult
import de.littletrotter.shared.NatsStreamSetup
import io.nats.client.Connection
import io.nats.client.ConsumeOptions
import io.nats.client.ConsumerContext
import io.nats.client.JetStream
import io.nats.client.JetStreamApiException
import io.nats.client.JetStreamManagement
import io.nats.client.Message
import io.nats.client.MessageConsumer
import io.nats.client.MessageHandler
import io.nats.client.PublishOptions
import io.nats.client.StreamContext
import io.nats.client.api.ConsumerConfiguration
import io.nats.client.api.ConsumerInfo
import io.nats.client.impl.NatsJetStreamMetaData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException
import java.time.Duration
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture

class PdfCheckConsumerTest {

    private val request = PdfCheckRequest(checkId = "verapdf-7", jobId = "7", objectKey = "inv/7.pdf", sha256 = "ab")
    private val connection: Connection = mock()
    private val jetStream: JetStream = mock()
    private val processor: PdfCheckProcessor = mock()
    private val consumer = PdfCheckConsumer(connection, jetStream, processor).also { it.executor = java.util.concurrent.Executor { task -> task.run() } }

    private fun result(outcome: String, engineError: String? = null) = PdfCheckResult(
        checkId = "verapdf-7", jobId = "7", objectKey = "inv/7.pdf", sha256 = "ab", outcome = outcome,
        profile = "PDF/A-3B", validator = "veraPDF 1.30.2", engineError = engineError,
    )

    private fun message(delivery: Long, body: String = DocumentJobWireCodec.encodePdfCheckRequest(request)): Message {
        val metaData: NatsJetStreamMetaData = mock()
        whenever(metaData.deliveredCount()).thenReturn(delivery)
        val message: Message = mock()
        whenever(message.data).thenReturn(body.toByteArray())
        whenever(message.metaData()).thenReturn(metaData)
        return message
    }

    private fun publishedResult(): PdfCheckResult {
        val body = argumentCaptor<ByteArray>()
        verify(jetStream).publish(eq(DocumentJobSubjects.PDF_CHECK_RESULTS), body.capture(), any<PublishOptions>())
        return DocumentJobWireCodec.decodePdfCheckResult(String(body.firstValue))
    }

    @Test
    fun `a verdict is published under a stable id and the request acknowledged`() {
        whenever(processor.check(request)).thenReturn(result("INVALID"))
        val message = message(delivery = 1)

        consumer.handle(message)

        val options = argumentCaptor<PublishOptions>()
        verify(jetStream).publish(eq(DocumentJobSubjects.PDF_CHECK_RESULTS), any<ByteArray>(), options.capture())
        assertEquals("pdfcheck-verapdf-7", options.firstValue.messageId)
        assertEquals("INVALID", publishedResult().outcome)
        verify(message).ack()
    }

    @Test
    fun `a failed check is redelivered after a pause while deliveries remain`() {
        whenever(processor.check(request)).thenReturn(result("ERROR", "service down"))
        val message = message(delivery = 1)

        consumer.handle(message)

        verify(message).nakWithDelay(any<Duration>())
        verify(message, never()).ack()
        verify(jetStream, never()).publish(any<String>(), any<ByteArray>(), any<PublishOptions>())
    }

    @Test
    fun `the last delivery publishes the failure as an engine error and acknowledges`() {
        whenever(processor.check(request)).thenReturn(result("ERROR", "service down"))
        val message = message(delivery = PdfCheckConsumer.MAX_DELIVER.toLong())

        consumer.handle(message)

        val published = publishedResult()
        assertEquals("ERROR", published.outcome)
        assertEquals("service down", published.engineError)
        assertEquals("7", published.jobId)
        verify(message).ack()
    }

    @Test
    fun `an exception during the check is handled like a failed check`() {
        whenever(processor.check(request)).thenThrow(IllegalStateException("object store down"))
        val first = message(delivery = 1)
        val last = message(delivery = PdfCheckConsumer.MAX_DELIVER.toLong())

        consumer.handle(first)
        consumer.handle(last)

        verify(first).nakWithDelay(any<Duration>())
        verify(last).ack()
        assertEquals("object store down", publishedResult().engineError)
    }

    @Test
    fun `an unreadable request goes to the dead-letter subject`() {
        val message = message(delivery = 1, body = "not json {{{")

        consumer.handle(message)

        verify(jetStream).publish(eq(DocumentJobSubjects.PDF_CHECKS_DLQ), any<ByteArray>())
        verify(message).ack()
        verify(processor, never()).check(any())
    }

    @Test
    fun `an unreadable request stays for redelivery when even the dead-letter publish fails`() {
        doThrow(IOException("broker down")).whenever(jetStream).publish(eq(DocumentJobSubjects.PDF_CHECKS_DLQ), any<ByteArray>())
        val message = message(delivery = 1, body = "not json {{{")

        consumer.handle(message)

        verify(message).nak()
        verify(message, never()).ack()
    }

    @Test
    fun `a result that cannot be published leaves the request for redelivery`() {
        whenever(processor.check(request)).thenReturn(result("VALID"))
        doThrow(IOException("broker down")).whenever(jetStream).publish(any<String>(), any<ByteArray>(), any<PublishOptions>())
        val message = message(delivery = 1)

        consumer.handle(message)

        verify(message).nakWithDelay(any<Duration>())
        verify(message, never()).ack()
    }

    @Test
    fun `receive keeps the lease alive while the check runs and stops afterwards`() {
        whenever(processor.check(request)).thenReturn(result("VALID"))
        val future: ScheduledFuture<*> = mock()
        val scheduler: ScheduledExecutorService = mock()
        whenever(scheduler.scheduleAtFixedRate(any(), any(), any(), any())).thenReturn(future)
        consumer.heartbeatExecutor = scheduler
        val message = message(delivery = 1)

        consumer.receive(message)

        val heartbeat = argumentCaptor<Runnable>()
        verify(scheduler).scheduleAtFixedRate(heartbeat.capture(), any(), any(), any())
        heartbeat.firstValue.run()
        verify(message).inProgress()
        verify(future).cancel(false)
    }

    @Test
    fun `an error result without a reason is reported as an unknown error on the last delivery`() {
        whenever(processor.check(request)).thenReturn(result("ERROR", engineError = null))
        val message = message(delivery = PdfCheckConsumer.MAX_DELIVER.toLong())

        consumer.handle(message)

        assertEquals("unbekannter Fehler", publishedResult().engineError)
        verify(message).ack()
    }

    @Test
    fun `an exception without a message is reported by its type on the last delivery`() {
        whenever(processor.check(request)).thenThrow(IllegalStateException())
        val message = message(delivery = PdfCheckConsumer.MAX_DELIVER.toLong())

        consumer.handle(message)

        assertEquals("IllegalStateException", publishedResult().engineError)
        verify(message).ack()
    }

    @Test
    fun `a result rejected by the stream leaves the request for redelivery`() {
        whenever(processor.check(request)).thenReturn(result("VALID"))
        doThrow(mock<JetStreamApiException>()).whenever(jetStream).publish(any<String>(), any<ByteArray>(), any<PublishOptions>())
        val message = message(delivery = 1)

        consumer.handle(message)

        verify(message).nakWithDelay(any<Duration>())
        verify(message, never()).ack()
    }

    @Test
    fun `an unreadable request stays for redelivery when the dead-letter subject is rejected`() {
        doThrow(mock<JetStreamApiException>()).whenever(jetStream).publish(eq(DocumentJobSubjects.PDF_CHECKS_DLQ), any<ByteArray>())
        val message = message(delivery = 1, body = "not json {{{")

        consumer.handle(message)

        verify(message).nak()
        verify(message, never()).ack()
    }

    @Test
    fun `a heartbeat that cannot be sent does not stop the check`() {
        whenever(processor.check(request)).thenReturn(result("VALID"))
        val scheduler: ScheduledExecutorService = mock()
        whenever(scheduler.scheduleAtFixedRate(any(), any(), any(), any())).thenReturn(mock<ScheduledFuture<*>>())
        consumer.heartbeatExecutor = scheduler
        val message = message(delivery = 1)
        whenever(message.inProgress()).thenThrow(IllegalStateException("connection closed"))

        consumer.receive(message)

        val heartbeat = argumentCaptor<Runnable>()
        verify(scheduler).scheduleAtFixedRate(heartbeat.capture(), any(), any(), any())
        heartbeat.firstValue.run()
        verify(message).ack()
    }

    @Test
    fun `the pull batch is large enough for jnats to pull again`() {
        val options = consumer.consumeOptions()

        assertEquals(PdfCheckConsumer.PULL_BATCH_SIZE, options.batchSize)
        assertTrue(options.batchSize >= 2, "a batch of one never crosses the re-pull threshold")
    }

    @Test
    fun `subscribing creates the durable on the check subject and hands received requests to the consumer`() {
        val management: JetStreamManagement = mock()
        whenever(connection.jetStreamManagement()).thenReturn(management)
        whenever(management.addOrUpdateConsumer(any(), any())).thenReturn(mock<ConsumerInfo>())
        val consumerContext: ConsumerContext = mock()
        val streamContext: StreamContext = mock()
        whenever(streamContext.getConsumerContext(PdfCheckConsumer.DURABLE)).thenReturn(consumerContext)
        whenever(jetStream.getStreamContext(NatsStreamSetup.STREAM_NAME)).thenReturn(streamContext)
        whenever(consumerContext.consume(any<ConsumeOptions>(), any<MessageHandler>())).thenReturn(mock<MessageConsumer>())
        whenever(processor.check(request)).thenReturn(result("VALID"))

        consumer.subscribe()

        val config = argumentCaptor<ConsumerConfiguration>()
        verify(management).addOrUpdateConsumer(eq(NatsStreamSetup.STREAM_NAME), config.capture())
        assertEquals(PdfCheckConsumer.DURABLE, config.firstValue.durable)
        assertEquals(DocumentJobSubjects.PDF_CHECKS, config.firstValue.filterSubject)
        assertEquals(PdfCheckConsumer.MAX_DELIVER.toLong(), config.firstValue.maxDeliver)
        val handler = argumentCaptor<MessageHandler>()
        verify(consumerContext).consume(any<ConsumeOptions>(), handler.capture())
        val message = message(delivery = 1)
        handler.firstValue.onMessage(message)
        verify(message).ack()
    }
}
