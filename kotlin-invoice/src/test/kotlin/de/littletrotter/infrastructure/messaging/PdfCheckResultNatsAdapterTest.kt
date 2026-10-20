package de.littletrotter.infrastructure.messaging

import de.littletrotter.application.invoice.port.inbound.PdfCheckVerdict
import de.littletrotter.application.invoice.port.inbound.RecordPdfCheckResultUseCase
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.ValidationMessage
import de.littletrotter.shared.documentjob.DocumentJobSubjects
import de.littletrotter.shared.documentjob.DocumentJobWireCodec
import de.littletrotter.shared.documentjob.PdfCheckResult
import de.littletrotter.shared.NatsStreamSetup
import io.nats.client.Connection
import io.nats.client.ConsumeOptions
import io.nats.client.ConsumerContext
import io.nats.client.JetStream
import io.nats.client.JetStreamApiException
import io.nats.client.JetStreamManagement
import io.nats.client.MessageConsumer
import io.nats.client.MessageHandler
import io.nats.client.StreamContext
import io.nats.client.api.ConsumerConfiguration
import io.nats.client.api.ConsumerInfo
import io.nats.client.Message
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
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

class PdfCheckResultNatsAdapterTest {

    private val useCase: RecordPdfCheckResultUseCase = mock()
    private val jetStream: JetStream = mock()
    private val adapter = PdfCheckResultNatsAdapter(durableConsumerName = "pdfcheck", consumerEnabled = true)
    private var contexts = 0
    private var closedContexts = 0

    @BeforeEach
    fun wire() {
        inject("recordResult", useCase)
        inject("jetStream", jetStream)
        adapter.requestContextFactory = {
            contexts++
            AutoCloseable { closedContexts++ }
        }
    }

    private fun inject(name: String, value: Any) {
        PdfCheckResultNatsAdapter::class.java.getDeclaredField(name).apply { isAccessible = true }.set(adapter, value)
    }

    private fun message(body: String): Message {
        val message: Message = mock()
        whenever(message.data).thenReturn(body.toByteArray())
        return message
    }

    private fun result(outcome: String, jobId: String = "7", findings: List<ValidationMessage> = emptyList(), engineError: String? = null) =
        DocumentJobWireCodec.encodePdfCheckResult(
            PdfCheckResult(
                checkId = "verapdf-7", jobId = jobId, objectKey = "inv/7.pdf", sha256 = "ab", outcome = outcome,
                profile = "PDF/A-3B", validator = "veraPDF 1.30.2", findings = findings, engineError = engineError,
            ),
        )

    @Test
    fun `a verdict is recorded before the message is acknowledged`() {
        val finding = ValidationMessage("ERROR", "rule", ruleId = "6.3.5-2")
        val msg = message(result("INVALID", findings = listOf(finding)))

        adapter.receive(msg)

        val verdict = argumentCaptor<PdfCheckVerdict>()
        val order = org.mockito.kotlin.inOrder(useCase, msg)
        order.verify(useCase).record(verdict.capture())
        order.verify(msg).ack()
        assertEquals(PdfCheckVerdict(7L, KositOutcome.INVALID, "veraPDF 1.30.2", listOf(finding), null), verdict.firstValue)
        assertEquals(contexts, closedContexts, "the request context is always closed")
    }

    @Test
    fun `an engine error keeps its reason`() {
        adapter.receive(message(result("ERROR", engineError = "timeout")))

        val verdict = argumentCaptor<PdfCheckVerdict>()
        verify(useCase).record(verdict.capture())
        assertEquals(KositOutcome.ERROR, verdict.firstValue.outcome)
        assertEquals("timeout", verdict.firstValue.engineError)
    }

    @Test
    fun `an outcome this version does not know counts as a check that could not be evaluated`() {
        adapter.receive(message(result("MAYBE")))

        val verdict = argumentCaptor<PdfCheckVerdict>()
        verify(useCase).record(verdict.capture())
        assertEquals(KositOutcome.ERROR, verdict.firstValue.outcome)
        assertEquals("unbekanntes Ergebnis 'MAYBE'", verdict.firstValue.engineError)
    }

    @Test
    fun `a failure while recording leaves the message for redelivery`() {
        doThrow(IllegalStateException("db down")).whenever(useCase).record(any())
        val msg = message(result("VALID"))

        adapter.receive(msg)

        verify(msg).nakWithDelay(any<Duration>())
        verify(msg, never()).ack()
        assertEquals(contexts, closedContexts)
    }

    @Test
    fun `an unreadable or invalid message goes to the dead-letter subject`() {
        val garbage = message("not json {{{")
        val badId = message(result("VALID", jobId = "seven"))

        val garbageBytes = garbage.data
        val badIdBytes = badId.data

        adapter.receive(garbage)
        adapter.receive(badId)

        verify(jetStream).publish(eq(DocumentJobSubjects.PDF_CHECKS_DLQ), eq(garbageBytes))
        verify(jetStream).publish(eq(DocumentJobSubjects.PDF_CHECKS_DLQ), eq(badIdBytes))
        verify(garbage).ack()
        verify(badId).ack()
        verify(useCase, never()).record(any())
    }

    @Test
    fun `a dead-letter publish that fails leaves the message for redelivery`() {
        doThrow(IOException("broker down")).whenever(jetStream).publish(any<String>(), any<ByteArray>())
        val msg = message("not json {{{")

        adapter.receive(msg)

        verify(msg).nakWithDelay(any<Duration>())
        verify(msg, never()).ack()
    }

    @Test
    fun `a dead-letter publish rejected by the stream leaves the message for redelivery`() {
        doThrow(mock<JetStreamApiException>()).whenever(jetStream).publish(any<String>(), any<ByteArray>())
        val msg = message("not json {{{")

        adapter.receive(msg)

        verify(msg).nakWithDelay(any<Duration>())
        verify(msg, never()).ack()
    }

    @Test
    fun `the pull batch is large enough for jnats to pull again`() {
        assertEquals(PdfCheckResultNatsAdapter.PULL_BATCH_SIZE, adapter.consumeOptions().batchSize)
        assertTrue(adapter.consumeOptions().batchSize >= 2, "a batch of one never crosses the re-pull threshold")
    }

    @Test
    fun `subscribing creates the durable on the result subject and hands received verdicts to the adapter`() {
        val management: JetStreamManagement = mock()
        val connection: Connection = mock()
        whenever(connection.jetStreamManagement()).thenReturn(management)
        whenever(management.addOrUpdateConsumer(any(), any())).thenReturn(mock<ConsumerInfo>())
        inject("connection", connection)
        val consumerContext: ConsumerContext = mock()
        val streamContext: StreamContext = mock()
        whenever(streamContext.getConsumerContext("pdfcheck")).thenReturn(consumerContext)
        whenever(jetStream.getStreamContext(NatsStreamSetup.STREAM_NAME)).thenReturn(streamContext)
        whenever(consumerContext.consume(any<ConsumeOptions>(), any<MessageHandler>())).thenReturn(mock<MessageConsumer>())

        adapter.subscribe()

        val config = argumentCaptor<ConsumerConfiguration>()
        verify(management).addOrUpdateConsumer(eq(NatsStreamSetup.STREAM_NAME), config.capture())
        assertEquals("pdfcheck", config.firstValue.durable)
        assertEquals(DocumentJobSubjects.PDF_CHECK_RESULTS, config.firstValue.filterSubject)
        val handler = argumentCaptor<MessageHandler>()
        verify(consumerContext).consume(any<ConsumeOptions>(), handler.capture())
        val msg = message(result("VALID"))
        handler.firstValue.onMessage(msg)
        verify(msg).ack()
    }

    @Test
    fun `a disabled result consumer does not subscribe`() {
        val disabled = PdfCheckResultNatsAdapter(durableConsumerName = "pdfcheck", consumerEnabled = false)

        disabled.subscribe()

        verify(jetStream, never()).getStreamContext(any())
    }
}
