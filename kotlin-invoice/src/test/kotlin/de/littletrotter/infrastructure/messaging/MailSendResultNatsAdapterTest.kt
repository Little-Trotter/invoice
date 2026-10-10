package de.littletrotter.infrastructure.messaging

import de.littletrotter.application.mail.MailProjectionOutcome
import de.littletrotter.shared.mail.MailContractViolationException
import de.littletrotter.shared.mail.MailStreams
import io.nats.client.Connection
import io.nats.client.ConsumerContext
import io.nats.client.JetStream
import io.nats.client.JetStreamApiException
import io.nats.client.JetStreamManagement
import io.nats.client.Message
import io.nats.client.MessageHandler
import io.nats.client.StreamContext
import io.nats.client.api.ConsumerConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import de.littletrotter.shared.NatsBindRetry
import java.util.concurrent.ScheduledExecutorService
import org.mockito.kotlin.times
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The transport half.
 *
 * The decisive test is [subscribeDoesNothingWhenMailIsDisabled]: with `mail.enabled=false` the
 * adapter must not touch JetStream at all, because the streams belong to a component that is not
 * installed, and a consumer bound to a stream nobody creates would reconnect and log-spam forever
 * (concept §5, point 4). Asserting "no interaction with the connection" is what makes that a
 * conditional registration rather than a defensive one.
 */
@DisplayName("MailSendResultNatsAdapter (conditional subscription + per-message handling)")
class MailSendResultNatsAdapterTest {

    private lateinit var consumer: MailSendResultConsumer
    private lateinit var connection: Connection
    private lateinit var jetStream: JetStream

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
        consumer = mock()
        connection = mock()
        jetStream = mock()
        requestContext = FakeRequestContext()
    }

    private fun adapter(mailEnabled: Boolean): MailSendResultNatsAdapter =
        MailSendResultNatsAdapter(
            mailEnabled = mailEnabled,
            resultSubject = "mail.send.result",
            durableConsumerName = "little-trotter-invoice-mail-result-consumer",
        ).also {
            injectField(it, "resultConsumer", consumer)
            injectField(it, "connection", connection)
            injectField(it, "jetStream", jetStream)
            injectField(it, "requestContextFactory", requestContext.factory)
        }

    // ── Conditional registration ───────────────────────────────────────────────

    @Test
    @DisplayName("mail.enabled=false binds nothing at all — no JetStream call, no consumer")
    fun subscribeDoesNothingWhenMailIsDisabled() {
        adapter(mailEnabled = false).subscribe()

        verify(connection, never()).jetStreamManagement()
        verify(jetStream, never()).getStreamContext(any())
    }

    @Test
    @DisplayName("mail.enabled=true creates the durable consumer on the component's stream and starts consuming")
    fun bindsTheDurableConsumerWhenEnabled() {
        val jsm: JetStreamManagement = mock()
        val streamContext: StreamContext = mock()
        val consumerContext: ConsumerContext = mock()
        whenever(connection.jetStreamManagement()).thenReturn(jsm)
        whenever(jsm.addOrUpdateConsumer(any(), any())).thenReturn(mock())
        whenever(jetStream.getStreamContext(MailStreams.RESULT)).thenReturn(streamContext)
        whenever(streamContext.getConsumerContext("little-trotter-invoice-mail-result-consumer"))
            .thenReturn(consumerContext)

        adapter(mailEnabled = true).subscribe()

        // The consumer is created explicitly, on the stream the COMPONENT owns — this adapter
        // never creates that stream itself (concept §5, point 3).
        val config = argumentCaptor<ConsumerConfiguration>()
        verify(jsm).addOrUpdateConsumer(eq(MailStreams.RESULT), config.capture())
        assertEquals("little-trotter-invoice-mail-result-consumer", config.firstValue.durable)
        assertEquals("mail.send.result", config.firstValue.filterSubject)
        verify(consumerContext).consume(any<MessageHandler>())
    }

    @Test
    @DisplayName("a missing stream does not crash the host and is retried until the consumer binds")
    fun missingStreamIsRetried() {
        // The component owns the streams and is deployed separately, so it may not be up yet;
        // failing @PostConstruct would take the whole invoicing application down.
        val jsm: JetStreamManagement = mock()
        val streamContext: StreamContext = mock()
        val consumerContext: ConsumerContext = mock()
        val notFound = mock<JetStreamApiException>().also { whenever(it.apiErrorCode).thenReturn(NatsBindRetry.STREAM_NOT_FOUND) }
        whenever(connection.jetStreamManagement()).thenReturn(jsm)
        whenever(jsm.addOrUpdateConsumer(any(), any())).thenAnswer { throw notFound }.thenReturn(mock())
        whenever(jetStream.getStreamContext(MailStreams.RESULT)).thenReturn(streamContext)
        whenever(streamContext.getConsumerContext("little-trotter-invoice-mail-result-consumer")).thenReturn(consumerContext)
        val scheduler: ScheduledExecutorService = mock()
        val adapter = adapter(mailEnabled = true).also { it.bindRetry = NatsBindRetry("mail") { scheduler } }

        adapter.subscribe()
        val retry = argumentCaptor<Runnable>().also { verify(scheduler).schedule(it.capture(), any(), any()) }.firstValue
        verify(consumerContext, never()).consume(any<MessageHandler>())
        retry.run()

        verify(consumerContext).consume(any<MessageHandler>())
        verify(scheduler, times(1)).schedule(any<Runnable>(), any(), any())
    }

    @Test
    @DisplayName("an unreachable broker does not crash the host either")
    fun unreachableBrokerDoesNotCrashTheHost() {
        whenever(connection.jetStreamManagement()).thenThrow(java.io.IOException("no connection"))
        val scheduler: ScheduledExecutorService = mock()

        adapter(mailEnabled = true).also { it.bindRetry = NatsBindRetry("mail") { scheduler } }.subscribe()

        verify(scheduler).schedule(any<Runnable>(), any(), any())
    }

    @Test
    @DisplayName("another JetStream error is logged and not retried")
    fun otherJetStreamErrorIsNotRetried() {
        val other = mock<JetStreamApiException>().also { whenever(it.apiErrorCode).thenReturn(10014) }
        whenever(connection.jetStreamManagement()).thenAnswer { throw other }
        val scheduler: ScheduledExecutorService = mock()

        adapter(mailEnabled = true).also { it.bindRetry = NatsBindRetry("mail") { scheduler } }.subscribe()

        verify(scheduler, never()).schedule(any<Runnable>(), any(), any())
    }

    @Test
    @DisplayName("shutdown stops the bind retry")
    fun shutdownStopsTheRetry() {
        val retry: NatsBindRetry = mock()

        adapter(mailEnabled = true).also { it.bindRetry = retry }.shutdown()

        verify(retry).close()
    }

    // ── Per-message handling ───────────────────────────────────────────────────

    @Test
    @DisplayName("a projected message is acked and its request context is torn down")
    fun acksAProjectedMessage() {
        whenever(consumer.consume(any())).thenReturn(MailProjectionOutcome.APPLIED)
        val tracking = message("""{"referenceKey":"RE-1"}""")

        adapter(mailEnabled = true).receive(tracking.msg)

        verify(consumer).consume("""{"referenceKey":"RE-1"}""")
        assertTrue(tracking.ackCalled, "a processed message must be acked")
        assertFalse(tracking.termCalled)
        assertEquals(1, requestContext.activations)
        assertEquals(1, requestContext.terminations)
    }

    @Test
    @DisplayName("an unreadable payload is terminated, not redelivered forever and not acked as processed")
    fun terminatesAnUnreadablePayload() {
        whenever(consumer.consume(any())).thenThrow(MailContractViolationException("MailSendResultEvent: field 'referenceKey' is missing"))
        val tracking = message("{}")

        adapter(mailEnabled = true).receive(tracking.msg)

        assertTrue(tracking.termCalled, "a poison payload must be terminated")
        assertFalse(tracking.ackCalled)
        // The context is still torn down — nothing may leak into the next message on this thread.
        assertEquals(1, requestContext.terminations)
    }

    @Test
    @DisplayName("a projection failure terminates too, rather than killing the consume thread")
    fun terminatesOnProjectionFailure() {
        whenever(consumer.consume(any())).thenThrow(RuntimeException("database down"))
        val tracking = message("""{"referenceKey":"RE-1"}""")

        adapter(mailEnabled = true).receive(tracking.msg)

        assertTrue(tracking.termCalled)
        assertEquals(1, requestContext.terminations)
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private class TrackingMessage(val msg: Message) {
        var ackCalled = false
        var termCalled = false
    }

    private fun message(payload: String): TrackingMessage {
        val msg: Message = mock()
        whenever(msg.data).thenReturn(payload.toByteArray(Charsets.UTF_8))
        val tracking = TrackingMessage(msg)
        org.mockito.kotlin.doAnswer { tracking.ackCalled = true; null }.whenever(msg).ack()
        org.mockito.kotlin.doAnswer { tracking.termCalled = true; null }.whenever(msg).term()
        return tracking
    }

    private fun injectField(target: Any, name: String, value: Any) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }
}
