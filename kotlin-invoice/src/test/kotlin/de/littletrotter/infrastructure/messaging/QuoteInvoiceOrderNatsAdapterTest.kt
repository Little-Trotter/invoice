package de.littletrotter.infrastructure.messaging

import de.littletrotter.application.dto.InvoiceFromQuoteResult
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.shared.quote.QuoteContractViolationException
import de.littletrotter.shared.quote.QuoteStreams
import io.nats.client.Connection
import io.nats.client.ConsumerContext
import io.nats.client.JetStream
import io.nats.client.JetStreamApiException
import io.nats.client.JetStreamManagement
import io.nats.client.Message
import io.nats.client.MessageHandler
import io.nats.client.StreamContext
import io.nats.client.api.ConsumerConfiguration
import io.nats.client.impl.NatsJetStreamMetaData
import io.nats.client.impl.NatsMessage
import jakarta.persistence.PersistenceException
import jakarta.transaction.TransactionalException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException
import java.sql.SQLException
import java.time.Duration

class QuoteInvoiceOrderNatsAdapterTest {

    private val consumer: QuoteInvoiceOrderConsumer = mock()
    private val connection: Connection = mock()
    private val jetStream: JetStream = mock()
    private var contexts = 0
    private var closed = 0

    @BeforeEach
    fun setUp() {
        contexts = 0
        closed = 0
    }

    private fun adapter(enabled: Boolean = true): QuoteInvoiceOrderNatsAdapter =
        QuoteInvoiceOrderNatsAdapter(enabled, "durable-1", "dlq-subject").also {
            inject(it, "consumer", consumer)
            inject(it, "connection", connection)
            inject(it, "jetStream", jetStream)
            it.requestContextFactory = {
                contexts++
                AutoCloseable { closed++ }
            }
        }

    private fun inject(target: Any, name: String, value: Any) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun message(delivered: Long?): Message {
        val msg: Message = mock()
        whenever(msg.data).thenReturn("{}".toByteArray())
        if (delivered == null) {
            whenever(msg.metaData()).thenThrow(IllegalStateException("not a JetStream message"))
        } else {
            val meta: NatsJetStreamMetaData = mock()
            whenever(meta.deliveredCount()).thenReturn(delivered)
            whenever(msg.metaData()).thenReturn(meta)
        }
        return msg
    }

    @Test
    fun `a disabled module binds nothing`() {
        adapter(enabled = false).subscribe()

        verify(connection, never()).jetStreamManagement()
        verify(jetStream, never()).getStreamContext(any())
    }

    @Test
    fun `an enabled module creates the strictly serial durable consumer and starts consuming`() {
        val jsm: JetStreamManagement = mock()
        val streamContext: StreamContext = mock()
        val consumerContext: ConsumerContext = mock()
        whenever(connection.jetStreamManagement()).thenReturn(jsm)
        whenever(jsm.addOrUpdateConsumer(any(), any())).thenReturn(mock())
        whenever(jetStream.getStreamContext(QuoteStreams.INVOICE_ORDER)).thenReturn(streamContext)
        whenever(streamContext.getConsumerContext("durable-1")).thenReturn(consumerContext)

        adapter().subscribe()

        val config = argumentCaptor<ConsumerConfiguration>()
        verify(jsm).addOrUpdateConsumer(eq(QuoteStreams.INVOICE_ORDER), config.capture())
        assertEquals("durable-1", config.firstValue.durable)
        assertEquals("quote.invoice-order.requested", config.firstValue.filterSubject)
        assertEquals(1L, config.firstValue.maxAckPending)
        assertEquals(5L, config.firstValue.maxDeliver)
        assertEquals(Duration.ofSeconds(60), config.firstValue.ackWait)
        verify(consumerContext).consume(any<MessageHandler>())
    }

    @Test
    fun `a missing stream is logged and does not stop the host`() {
        whenever(connection.jetStreamManagement()).thenThrow(IOException("no connection"))

        adapter().subscribe()

        val jsm: JetStreamManagement = mock()
        doReturn(jsm).whenever(connection).jetStreamManagement()
        doThrow(mock<JetStreamApiException>()).whenever(jsm).addOrUpdateConsumer(any(), any())
        adapter().subscribe()
    }

    @Test
    fun `a processed message is acknowledged and its request context closed`() {
        whenever(consumer.consume(any())).thenReturn(InvoiceFromQuoteResult.Rejected("x"))
        val msg = message(1)

        adapter().receive(msg)

        verify(msg).ack()
        assertEquals(1, contexts)
        assertEquals(1, closed)
    }

    @Test
    fun `an unreadable payload is terminated`() {
        whenever(consumer.consume(any())).thenThrow(QuoteContractViolationException("QuoteInvoiceOrderRequested: bad"))
        val msg = message(1)

        adapter().receive(msg)

        verify(msg).term()
        verify(msg, never()).ack()
        assertEquals(1, closed)
    }

    @Test
    fun `every transient failure kind is redelivered with a delay while attempts remain`() {
        val failures = listOf<Exception>(
            QuoteServiceException("offer service down"),
            PersistenceException("db"),
            TransactionalException("tx", RuntimeException()),
            SQLException("outbox"),
            IllegalStateException("inconsistent"),
        )
        failures.forEach { failure ->
            whenever(consumer.consume(any())).thenAnswer { throw failure }
            val msg = message(2)

            adapter().receive(msg)

            verify(msg).nakWithDelay(Duration.ofSeconds(30))
            verify(msg, never()).term()
        }
    }

    @Test
    fun `a message without JetStream metadata counts as a first delivery`() {
        whenever(consumer.consume(any())).thenThrow(IllegalStateException("boom"))
        val msg = message(null)

        adapter().receive(msg)

        verify(msg).nakWithDelay(Duration.ofSeconds(30))
    }

    @Test
    fun `the last delivery is archived in the dead letter stream, reported and terminated`() {
        whenever(consumer.consume(any())).thenThrow(IllegalStateException("boom"))
        val msg = message(5)

        adapter().receive(msg)

        val archived = argumentCaptor<NatsMessage>()
        verify(jetStream).publish(archived.capture())
        assertEquals("dlq-subject", archived.firstValue.subject)
        assertEquals("quote.invoice-order.requested", archived.firstValue.headers.getFirst("Origin-Subject"))
        verify(consumer).reportParked(msg.data)
        verify(msg).term()
    }

    @Test
    fun `a failing dead letter publish keeps the message in the queue`() {
        whenever(consumer.consume(any())).thenThrow(IllegalStateException("boom"))
        doThrow(IOException("dlq down")).whenever(jetStream).publish(any<NatsMessage>())
        val ioMsg = message(5)

        adapter().receive(ioMsg)

        verify(ioMsg).nakWithDelay(Duration.ofSeconds(30))
        verify(ioMsg, never()).term()

        doThrow(mock<JetStreamApiException>()).whenever(jetStream).publish(any<NatsMessage>())
        val apiMsg = message(6)
        adapter().receive(apiMsg)
        verify(apiMsg).nakWithDelay(Duration.ofSeconds(30))
    }
}
