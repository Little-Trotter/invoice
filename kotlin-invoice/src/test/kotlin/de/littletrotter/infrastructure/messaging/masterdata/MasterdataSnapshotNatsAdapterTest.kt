package de.littletrotter.infrastructure.messaging.masterdata

import de.littletrotter.shared.masterdata.MasterdataStreams
import de.littletrotter.shared.masterdata.MasterdataWireCodec
import de.littletrotter.shared.masterdata.SnapshotRequested
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
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException

class MasterdataSnapshotNatsAdapterTest {

    private val connection = mock<Connection>()
    private val jetStream = mock<JetStream>()
    private val service = mock<MasterdataSnapshotService>()

    private fun adapter(enabled: Boolean) =
        MasterdataSnapshotNatsAdapter(connection, jetStream, service, enabled, "durable").apply {
            requestContextFactory = { AutoCloseable { } }
        }

    private fun message(payload: String) = mock<Message>().also { whenever(it.data).thenReturn(payload.toByteArray()) }

    @Test
    fun `disabled binds nothing`() {
        adapter(false).subscribe()

        verify(connection, never()).jetStreamManagement()
    }

    @Test
    fun `enabled creates the durable consumer and consumes`() {
        val jsm = mock<JetStreamManagement>()
        val consumer = mock<ConsumerContext>()
        val stream = mock<StreamContext>()
        whenever(connection.jetStreamManagement()).thenReturn(jsm)
        whenever(jsm.addOrUpdateConsumer(any(), any<ConsumerConfiguration>())).thenReturn(mock())
        whenever(jetStream.getStreamContext(MasterdataStreams.MASTERDATA)).thenReturn(stream)
        whenever(stream.getConsumerContext("durable")).thenReturn(consumer)

        adapter(true).subscribe()

        val config = argumentCaptor<ConsumerConfiguration>()
        verify(jsm).addOrUpdateConsumer(eq(MasterdataStreams.MASTERDATA), config.capture())
        assertEquals("masterdata.snapshot.requested", config.firstValue.filterSubject)
        verify(consumer).consume(any<MessageHandler>())
    }

    @Test
    fun `a readable request is answered and acked`() {
        val msg = message(MasterdataWireCodec.encode(SnapshotRequested("r1")))
        whenever(service.answer(any())).thenReturn(3)

        adapter(true).receive(msg)

        verify(msg).ack()
    }

    @Test
    fun `an unreadable request is terminated`() {
        val msg = message("not json")

        adapter(true).receive(msg)

        verify(msg).term()
        verify(service, never()).answer(any())
    }

    @Test
    fun `a failed publish is redelivered`() {
        val msg = message(MasterdataWireCodec.encode(SnapshotRequested("r2")))
        doAnswer { throw IOException("down") }.whenever(service).answer(any())

        adapter(true).receive(msg)

        verify(msg).nak()
        verify(msg, never()).ack()
    }

    @Test
    fun `a rejected publish is redelivered`() {
        val msg = message(MasterdataWireCodec.encode(SnapshotRequested("r3")))
        doAnswer { throw mock<JetStreamApiException>() }.whenever(service).answer(any())

        adapter(true).receive(msg)

        verify(msg).nak()
    }
}
