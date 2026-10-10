package de.littletrotter.infrastructure.messaging

import io.nats.client.ConsumerContext
import io.nats.client.Connection
import io.nats.client.JetStream
import io.nats.client.JetStreamManagement
import io.nats.client.Message
import io.nats.client.MessageHandler
import io.nats.client.StreamContext
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException

/**
 * Unit tests for [GenerationJobNatsAdapter].
 *
 * Verifies the adapter's contract independently of the NATS connection/dispatcher:
 *  - happy path: payload is delegated to [GenerationJobResultConsumer] and the message is ack'd
 *  - error path: when the consumer throws, the payload is routed to the DLQ subject and the
 *    message is still ack'd once the DLQ accepts it
 *  - DLQ publish failures nack the original message for redelivery
 */
class GenerationJobNatsAdapterTest {

    private lateinit var adapter: GenerationJobNatsAdapter
    private lateinit var resultConsumer: GenerationJobResultConsumer
    private lateinit var jetStream: JetStream

    /** Counts activate/terminate calls of the [requestContextFactory][GenerationJobNatsAdapter
     *  .requestContextFactory] seam so tests can prove `receive()` always tears its request
     *  context back down, even on the DLQ/nack paths — without touching a real ArC container
     *  (see that field's own KDoc for why the real default can never run in this plain
     *  JUnit test). */
    private class FakeRequestContext {
        var activations = 0
        var terminations = 0
        val factory: () -> AutoCloseable = {
            activations++
            AutoCloseable { terminations++ }
        }
    }

    private lateinit var requestContext: FakeRequestContext

    @BeforeEach
    fun setUp() {
        resultConsumer = mock()
        jetStream = mock()
        requestContext = FakeRequestContext()
        // PO-Grundsatz 29.08.2026 — the four @ConfigProperty fields moved to constructor
        // injection ( pattern); named here directly instead of via injectField.
        // connection/jetStream/resultConsumer/requestContextFactory stay plain field injection
        // and are still reached via injectField below, same as before this refactor.
        adapter = GenerationJobNatsAdapter(
            resultsSubject = "document-job-results",
            dlqSubject = "document-job-results-dlq",
            durableConsumerName = "little-trotter-invoice-result-consumer",
            resultConsumerEnabled = true,
        )
        injectField(adapter, "resultConsumer", resultConsumer)
        injectField(adapter, "jetStream", jetStream)
        injectField(adapter, "requestContextFactory", requestContext.factory)
    }

    @Test
    fun `receive delegates payload to resultConsumer and acks the message`() {
        val payload = """{"id":"1","status":"COMPLETED","resultRefs":{"pdf":"s3://bucket/1.pdf"}}"""
        val tracking = message(payload)

        adapter.receive(tracking.msg)

        verify(resultConsumer).consume(payload)
        assertTrue(tracking.ackCalled, "msg.ack() must be called on success")
        verify(jetStream, never()).publish(any<String>(), any())
        assertTrue(requestContext.activations == 1 && requestContext.terminations == 1, "the request context must be activated and torn down exactly once for a successful message")
    }

    @Test
    fun `receive routes to DLQ and still acks when resultConsumer throws`() {
        val payload = """{"id":"99","status":"COMPLETED"}"""
        val tracking = message(payload)

        whenever(resultConsumer.consume(any())).thenThrow(RuntimeException("simulated DB failure"))

        adapter.receive(tracking.msg)

        verify(jetStream).publish("document-job-results-dlq", payload.toByteArray(Charsets.UTF_8))
        assertTrue(tracking.ackCalled, "msg.ack() must be called after DLQ routing so the message is not re-delivered")
    }

    @Test
    fun `receive nacks when DLQ send fails`() {
        val payload = """{"id":"100","status":"COMPLETED"}"""
        val tracking = message(payload)

        whenever(resultConsumer.consume(any())).thenThrow(RuntimeException("simulated DB failure"))
        whenever(jetStream.publish("document-job-results-dlq", payload.toByteArray(Charsets.UTF_8)))
            .thenThrow(IOException("dlq down"))

        adapter.receive(tracking.msg)

        assertTrue(tracking.nackCalled, "msg.nak() must be called when the DLQ publish fails")
        assertTrue(requestContext.terminations == 1, "the request context must still be torn down even when the DLQ send itself fails")
    }

    @Test
    fun `receive tears down its request context per message, never leaking one into the next call`() {
        // Bug production 2026-08-26 regression guard: three messages on the same adapter
        // instance (simulating the same reused NATS dispatch thread) must each get their own
        // activate/terminate pair — never fewer terminations than activations, which is exactly
        // what `@ActivateRequestContext`'s "no-op if already active" behavior used to risk.
        adapter.receive(message("""{"id":"10","status":"COMPLETED"}""").msg)
        whenever(resultConsumer.consume(any())).thenThrow(RuntimeException("simulated DB failure"))
        adapter.receive(message("""{"id":"11","status":"COMPLETED"}""").msg)
        adapter.receive(message("""{"id":"12","status":"COMPLETED"}""").msg)

        assertTrue(requestContext.activations == 3, "expected 3 activations, got ${requestContext.activations}")
        assertTrue(requestContext.terminations == 3, "expected 3 terminations, got ${requestContext.terminations}")
    }

    @Test
    fun `subscribe does not bind when result consumer is disabled`() {
        val connection = mock<io.nats.client.Connection>()
        adapter = GenerationJobNatsAdapter(
            resultsSubject = "document-job-results",
            dlqSubject = "document-job-results-dlq",
            durableConsumerName = "little-trotter-invoice-result-consumer",
            resultConsumerEnabled = false,
        )
        injectField(adapter, "connection", connection)
        injectField(adapter, "jetStream", jetStream)
        injectField(adapter, "resultConsumer", resultConsumer)

        adapter.subscribe()

        verify(connection, never()).jetStreamManagement()
    }

    @Test
    fun `receive does not send to DLQ when processing succeeds`() {
        val payload = """{"id":"2","status":"FAILED"}"""
        val tracking = message(payload)

        adapter.receive(tracking.msg)

        verify(jetStream, never()).publish(any<String>(), any())
    }

    // ── AP-M4 — pull-consumer subscribe(): StreamContext -> ConsumerContext -> consume() ────────

    @Test
    fun `subscribe ensures the durable consumer via JetStreamManagement and wires consume() to receive when enabled`() {
        val connection: Connection = mock()
        val jetStreamForSubscribe: JetStream = mock()
        val jsm: JetStreamManagement = mock()
        val streamContext: StreamContext = mock()
        val consumerContext: ConsumerContext = mock()
        whenever(connection.jetStreamManagement()).thenReturn(jsm)
        whenever(jsm.addOrUpdateConsumer(any(), any())).thenReturn(mock())
        whenever(jetStreamForSubscribe.getStreamContext(de.littletrotter.shared.NatsStreamSetup.STREAM_NAME)).thenReturn(streamContext)
        whenever(streamContext.getConsumerContext("little-trotter-invoice-result-consumer")).thenReturn(consumerContext)
        val enabledAdapter = GenerationJobNatsAdapter(
            resultsSubject = "document-job-results",
            dlqSubject = "document-job-results-dlq",
            durableConsumerName = "little-trotter-invoice-result-consumer",
            resultConsumerEnabled = true,
        )
        injectField(enabledAdapter, "connection", connection)
        injectField(enabledAdapter, "jetStream", jetStreamForSubscribe)
        injectField(enabledAdapter, "resultConsumer", resultConsumer)
        injectField(enabledAdapter, "requestContextFactory", requestContext.factory)

        enabledAdapter.subscribe()

        // durable consumer creation/drift-update goes through the shared NatsStreamSetup helper,
        // filtered to this consumer's own subject — never the stream's other (jobs) subject.
        val configCaptor = argumentCaptor<io.nats.client.api.ConsumerConfiguration>()
        verify(jsm).addOrUpdateConsumer(eq(de.littletrotter.shared.NatsStreamSetup.STREAM_NAME), configCaptor.capture())
        assertTrue(configCaptor.firstValue.durable == "little-trotter-invoice-result-consumer")
        assertTrue(configCaptor.firstValue.filterSubject == "document-job-results")
        assertTrue(configCaptor.firstValue.maxAckPending == 16L)

        val handlerCaptor = argumentCaptor<MessageHandler>()
        verify(consumerContext).consume(handlerCaptor.capture())

        // exercises the lambda itself, proving the consumer context is actually wired to receive()
        val payload = """{"id":"3","status":"COMPLETED"}"""
        val tracking = message(payload)
        handlerCaptor.firstValue.onMessage(tracking.msg)
        verify(resultConsumer).consume(payload)
    }

    @Test
    fun `receive restores the interrupt flag when the DLQ publish itself is interrupted`() {
        val payload = """{"id":"101","status":"COMPLETED"}"""
        val tracking = message(payload)
        whenever(resultConsumer.consume(any())).thenThrow(RuntimeException("simulated DB failure"))
        // InterruptedException is not among Publish's declared checked exceptions, so Mockito's
        // thenThrow(...) refuses it outright ("Checked exception is invalid for this method!").
        // thenAnswer bypasses that validation — it may throw anything at runtime, same as real
        // Kotlin code, which does not enforce checked exceptions in the first place.
        whenever(jetStream.publish("document-job-results-dlq", payload.toByteArray(Charsets.UTF_8)))
            .thenAnswer { throw InterruptedException("dlq send interrupted") }

        try {
            adapter.receive(tracking.msg)
            assertTrue(Thread.interrupted(), "the interrupt flag must be restored, not swallowed")
        } finally {
            Thread.interrupted() // clear defensively even if the assertion above already did
        }
        assertTrue(tracking.nackCalled, "an interrupted DLQ send must still nack for redelivery")
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun message(payload: String): TrackingMessage {
        val msg: Message = mock()
        val tracking = TrackingMessage(msg)
        whenever(msg.data).thenReturn(payload.toByteArray(Charsets.UTF_8))
        whenever(msg.ack()).thenAnswer { tracking.ackCalled = true }
        whenever(msg.nak()).thenAnswer { tracking.nackCalled = true }
        return tracking
    }

    private fun injectField(target: Any, name: String, value: Any) {
        val field = target::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    // plain class, not a data class: ackCalled/nackCalled are mutated in place by the
    // mocked ack()/nak() answers above; nothing compares, copies or destructures an instance.
    private class TrackingMessage(
        val msg: Message,
        var ackCalled: Boolean = false,
        var nackCalled: Boolean = false,
    )
}
