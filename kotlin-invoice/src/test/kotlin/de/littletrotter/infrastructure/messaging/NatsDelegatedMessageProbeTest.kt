package de.littletrotter.infrastructure.messaging

import de.littletrotter.shared.NatsStreamSetup
import io.nats.client.Connection
import io.nats.client.JetStreamApiException
import io.nats.client.JetStreamManagement
import io.nats.client.api.ConsumerInfo
import io.nats.client.api.Error
import io.nats.client.api.MessageInfo
import io.nats.client.api.SequenceInfo
import io.nats.client.api.StreamInfo
import io.nats.client.api.StreamState
import io.nats.client.support.NatsJetStreamConstants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.IOException

/**
 * Unit coverage for [NatsDelegatedMessageProbe] against a mocked [Connection]/
 * [JetStreamManagement]: the "no such consumer yet" default, and [JetStreamApiException]'s
 * `apiErrorCode` branch in [NatsDelegatedMessageProbe.exists]. The full real-broker round trip
 * (including a genuinely deleted message and a purged/recreated stream) is covered by
 * `StaleDelegatedQueuedBacklogIT`.
 */
class NatsDelegatedMessageProbeTest {

    private fun probe(jsm: JetStreamManagement): NatsDelegatedMessageProbe {
        val connection: Connection = mock()
        whenever(connection.jetStreamManagement()).thenReturn(jsm)
        return NatsDelegatedMessageProbe(connection, "kotlin-xpdf-group", "kotlin-xpdf-interactive")
    }

    private fun streamInfo(lastSeq: Long): StreamInfo {
        val info: StreamInfo = mock()
        val state: StreamState = mock()
        whenever(state.lastSequence).thenReturn(lastSeq)
        whenever(info.streamState).thenReturn(state)
        return info
    }

    private fun consumerInfo(deliveredStreamSeq: Long): ConsumerInfo {
        val info: ConsumerInfo = mock()
        val delivered: SequenceInfo = mock()
        whenever(delivered.streamSequence).thenReturn(deliveredStreamSeq)
        whenever(info.delivered).thenReturn(delivered)
        return info
    }

    @Test
    fun `snapshot reports delivered zero when no durable worker consumer has ever been created`() {
        val jsm: JetStreamManagement = mock()
        // The nested stream/consumer mocks are built into local vals *before* stubbing jsm's own
        // methods: passing a mock-building call directly as a thenReturn(...) argument would
        // start a second, nested stub while jsm's own stub is still open, which Mockito rejects
        // with UnfinishedStubbingException.
        val stream = streamInfo(42L)
        whenever(jsm.getStreamInfo(NatsStreamSetup.STREAM_NAME)).thenReturn(stream)
        val noConsumer = mock<JetStreamApiException>()
        whenever(noConsumer.apiErrorCode).thenReturn(NatsJetStreamConstants.JS_CONSUMER_NOT_FOUND_ERR)
        whenever(jsm.getConsumerInfo(NatsStreamSetup.STREAM_NAME, "kotlin-xpdf-group"))
            .thenThrow(noConsumer)
        whenever(jsm.getConsumerInfo(NatsStreamSetup.STREAM_NAME, "kotlin-xpdf-interactive"))
            .thenThrow(noConsumer)

        val snapshot = probe(jsm).snapshot()

        assertEquals(0L, snapshot.deliveredStreamSeq)
        assertEquals(0L, snapshot.interactiveDeliveredStreamSeq)
        assertEquals(42L, snapshot.lastSeq)
    }

    @Test
    fun `snapshot propagates a JetStream consumer lookup failure other than consumer not found`() {
        val jsm: JetStreamManagement = mock()
        val stream = streamInfo(42L)
        whenever(jsm.getStreamInfo(NatsStreamSetup.STREAM_NAME)).thenReturn(stream)
        whenever(jsm.getConsumerInfo(NatsStreamSetup.STREAM_NAME, "kotlin-xpdf-group"))
            .thenThrow(JetStreamApiException(Error.JsBadRequestErr))

        assertThrows(JetStreamApiException::class.java) { probe(jsm).snapshot() }
    }

    @Test
    fun `snapshot reports the real delivered cursor once a durable worker consumer exists`() {
        val jsm: JetStreamManagement = mock()
        val stream = streamInfo(100L)
        val consumer = consumerInfo(37L)
        whenever(jsm.getStreamInfo(NatsStreamSetup.STREAM_NAME)).thenReturn(stream)
        whenever(jsm.getConsumerInfo(NatsStreamSetup.STREAM_NAME, "kotlin-xpdf-group")).thenReturn(consumer)
        whenever(jsm.getConsumerInfo(NatsStreamSetup.STREAM_NAME, "kotlin-xpdf-interactive")).thenReturn(consumer)

        val snapshot = probe(jsm).snapshot()

        assertEquals(37L, snapshot.deliveredStreamSeq)
        assertEquals(100L, snapshot.lastSeq)
    }

    @Test
    fun `snapshot propagates a genuine stream lookup failure instead of defaulting`() {
        val jsm: JetStreamManagement = mock()
        whenever(jsm.getStreamInfo(NatsStreamSetup.STREAM_NAME)).thenThrow(IOException("broker unreachable"))

        assertThrows(IOException::class.java) { probe(jsm).snapshot() }
    }

    @Test
    fun `exists is true when the broker returns a message`() {
        val jsm: JetStreamManagement = mock()
        whenever(jsm.getMessage(NatsStreamSetup.STREAM_NAME, 55L)).thenReturn(mock<MessageInfo>())

        assertTrue(probe(jsm).exists(55L))
    }

    @Test
    fun `exists is false only for the documented no-message-found error code`() {
        val jsm: JetStreamManagement = mock()
        whenever(jsm.getMessage(NatsStreamSetup.STREAM_NAME, 55L)).thenThrow(JetStreamApiException(Error.JsNoMessageFoundErr))

        assertFalse(probe(jsm).exists(55L))
    }

    @Test
    fun `exists rethrows any other JetStream API error instead of treating it as gone`() {
        val jsm: JetStreamManagement = mock()
        whenever(jsm.getMessage(NatsStreamSetup.STREAM_NAME, 55L)).thenThrow(JetStreamApiException(Error.JsBadRequestErr))

        val ex = assertThrows(JetStreamApiException::class.java) { probe(jsm).exists(55L) }
        assertEquals(10003, ex.apiErrorCode)
    }

    @Test
    fun `snapshot reads each lane's delivered cursor from its own durable consumer`() {
        val jsm: JetStreamManagement = mock()
        val stream = streamInfo(100L)
        val bulk = consumerInfo(10L)
        val interactive = consumerInfo(90L)
        whenever(jsm.getStreamInfo(NatsStreamSetup.STREAM_NAME)).thenReturn(stream)
        whenever(jsm.getConsumerInfo(NatsStreamSetup.STREAM_NAME, "kotlin-xpdf-group")).thenReturn(bulk)
        whenever(jsm.getConsumerInfo(NatsStreamSetup.STREAM_NAME, "kotlin-xpdf-interactive")).thenReturn(interactive)

        val snapshot = probe(jsm).snapshot()

        assertEquals(10L, snapshot.deliveredStreamSeq)
        assertEquals(90L, snapshot.interactiveDeliveredStreamSeq)
    }

    @Test
    fun `snapshot reports interactive delivered zero when only the bulk consumer exists`() {
        val jsm: JetStreamManagement = mock()
        val stream = streamInfo(100L)
        val bulk = consumerInfo(10L)
        whenever(jsm.getStreamInfo(NatsStreamSetup.STREAM_NAME)).thenReturn(stream)
        whenever(jsm.getConsumerInfo(NatsStreamSetup.STREAM_NAME, "kotlin-xpdf-group")).thenReturn(bulk)
        val noConsumer = mock<JetStreamApiException>()
        whenever(noConsumer.apiErrorCode).thenReturn(NatsJetStreamConstants.JS_CONSUMER_NOT_FOUND_ERR)
        whenever(jsm.getConsumerInfo(NatsStreamSetup.STREAM_NAME, "kotlin-xpdf-interactive")).thenThrow(noConsumer)

        val snapshot = probe(jsm).snapshot()

        assertEquals(10L, snapshot.deliveredStreamSeq)
        assertEquals(0L, snapshot.interactiveDeliveredStreamSeq)
    }
}
