package de.littletrotter.infrastructure.messaging

import de.littletrotter.shared.NatsStreamSetup
import io.nats.client.Connection
import io.nats.client.JetStream
import io.nats.client.JetStreamManagement
import io.nats.client.Nats
import io.nats.client.Options
import io.nats.client.api.StreamInfo
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException

/**
 * AP-M10 — mirrors kotlin-xpdf's own
 * `NatsConnectionProducerTest` (same shared-contract class, `Nats.connect` intercepted via
 * `mockStatic` the same way). Before this AP, this class was only reached indirectly through
 * `@QuarkusTest`s that happen to boot the whole app (e.g. `SystemWorkflowIT`), which never
 * exercised the "stream already exists" branch (every such boot creates the stream fresh) or the
 * `close()` no-op-before-connect path.
 *
 * AP-M3 — connect() now ensures both the main stream and the DLQ stream (both via
 * [NatsStreamSetup], which owns the actual getStreamInfo/addStream/updateStream sequencing); these
 * tests only need to prove both are reached, not re-verify NatsStreamSetup's own branches.
 */
class NatsConnectionProducerTest {

    private val reconnects = NatsReconnectListener()

    private fun newProducer(quoteEnabled: Boolean = false): NatsConnectionProducer =
        NatsConnectionProducer(
            natsUrl = "nats://localhost:4222",
            jobsSubject = "document-jobs",
            resultsSubject = "document-job-results",
            jobsDlqSubject = "document-jobs-dlq",
            resultsDlqSubject = "document-job-results-dlq",
            quoteEnabled = quoteEnabled,
            reconnects = reconnects,
        )

    @Test
    fun `connect opens a NATS connection and skips stream creation when both streams already exist`() {
        mockStatic(Nats::class.java).use { staticNats ->
            val connection = mock<Connection>()
            val jsm = mock<JetStreamManagement>()
            whenever(connection.jetStreamManagement()).thenReturn(jsm)
            // Configuration matches NatsStreamSetup's own desired configs exactly — no drift, so
            // updateStream must not be called either (this test's own assertions only check
            // addStream, but a matching config is also what makes this test's premise honest).
            val mainInfo = mock<StreamInfo>()
            whenever(mainInfo.configuration).thenReturn(
                NatsStreamSetup.configuration(listOf("document-jobs", "document-jobs-interactive", "document-job-results", NatsStreamSetup.OFFER_RESULTS_SUBJECT)),
            )
            val dlqInfo = mock<StreamInfo>()
            whenever(dlqInfo.configuration).thenReturn(
                NatsStreamSetup.dlqConfiguration(listOf("document-jobs-dlq", "document-job-results-dlq")),
            )
            whenever(jsm.getStreamInfo(NatsStreamSetup.STREAM_NAME)).thenReturn(mainInfo)
            whenever(jsm.getStreamInfo(NatsStreamSetup.DLQ_STREAM_NAME)).thenReturn(dlqInfo)
            staticNats.`when`<Connection> { Nats.connect(any<Options>()) }.thenReturn(connection)

            val producer = newProducer()
            producer.connect()

            verify(jsm).getStreamInfo(NatsStreamSetup.STREAM_NAME)
            verify(jsm).getStreamInfo(NatsStreamSetup.DLQ_STREAM_NAME)
            verify(jsm, never()).addStream(any())
            verify(jsm, never()).updateStream(any())
            assertSame(connection, producer.produceConnection())
        }
    }

    @Test
    fun `connect creates both streams when neither exists yet, tolerating a lost race`() {
        mockStatic(Nats::class.java).use { staticNats ->
            val connection = mock<Connection>()
            val jsm = mock<JetStreamManagement>()
            whenever(connection.jetStreamManagement()).thenReturn(jsm)
            whenever(jsm.getStreamInfo(NatsStreamSetup.STREAM_NAME))
                .thenThrow(IOException("not found"))
                .thenReturn(mock<StreamInfo>())
            whenever(jsm.getStreamInfo(NatsStreamSetup.DLQ_STREAM_NAME))
                .thenThrow(IOException("not found"))
                .thenReturn(mock<StreamInfo>())
            whenever(jsm.addStream(any())).thenThrow(IOException("already exists (race)"))
            staticNats.`when`<Connection> { Nats.connect(any<Options>()) }.thenReturn(connection)

            val producer = newProducer()
            producer.connect()

            verify(jsm, times(2)).getStreamInfo(NatsStreamSetup.STREAM_NAME)
            verify(jsm, times(2)).getStreamInfo(NatsStreamSetup.DLQ_STREAM_NAME)
            verify(jsm, times(2)).addStream(any())
        }
    }

    @Test
    fun `produceJetStream delegates to the connection`() {
        mockStatic(Nats::class.java).use { staticNats ->
            val connection = mock<Connection>()
            val jetStream = mock<JetStream>()
            val jsm = mock<JetStreamManagement>()
            whenever(connection.jetStreamManagement()).thenReturn(jsm)
            whenever(connection.jetStream()).thenReturn(jetStream)
            whenever(jsm.getStreamInfo(NatsStreamSetup.STREAM_NAME)).thenReturn(mock<StreamInfo>())
            whenever(jsm.getStreamInfo(NatsStreamSetup.DLQ_STREAM_NAME)).thenReturn(mock<StreamInfo>())
            staticNats.`when`<Connection> { Nats.connect(any<Options>()) }.thenReturn(connection)

            val producer = newProducer()
            producer.connect()

            assertSame(jetStream, producer.produceJetStream())
        }
    }

    @Test
    fun `close closes the connection once it has been established`() {
        mockStatic(Nats::class.java).use { staticNats ->
            val connection = mock<Connection>()
            val jsm = mock<JetStreamManagement>()
            whenever(connection.jetStreamManagement()).thenReturn(jsm)
            whenever(jsm.getStreamInfo(NatsStreamSetup.STREAM_NAME)).thenReturn(mock<StreamInfo>())
            whenever(jsm.getStreamInfo(NatsStreamSetup.DLQ_STREAM_NAME)).thenReturn(mock<StreamInfo>())
            staticNats.`when`<Connection> { Nats.connect(any<Options>()) }.thenReturn(connection)

            val producer = newProducer()
            producer.connect()
            producer.close()

            verify(connection).close()
        }
    }

    @Test
    fun `connect creates the master data stream only with the offer module`() {
        mockStatic(Nats::class.java).use { staticNats ->
            val connection = mock<Connection>()
            val jsm = mock<JetStreamManagement>()
            whenever(connection.jetStreamManagement()).thenReturn(jsm)
            whenever(jsm.getStreamInfo(NatsStreamSetup.STREAM_NAME)).thenReturn(mock<StreamInfo>())
            whenever(jsm.getStreamInfo(NatsStreamSetup.DLQ_STREAM_NAME)).thenReturn(mock<StreamInfo>())
            whenever(jsm.streamNames).thenReturn(emptyList())
            staticNats.`when`<Connection> { Nats.connect(any<Options>()) }.thenReturn(connection)

            newProducer(quoteEnabled = true).connect()

            verify(jsm).addStream(org.mockito.kotlin.argThat<io.nats.client.api.StreamConfiguration> { name == "MASTERDATA" })
        }
    }

    @Test
    fun `close is a no-op when connect was never called`() {
        val producer = newProducer()
        producer.close()
    }

    @Test
    fun `a reconnect of the broker connection reaches the registered listener`() {
        mockStatic(Nats::class.java).use { staticNats ->
            val connection = mock<Connection>()
            val jsm = mock<JetStreamManagement>()
            whenever(connection.jetStreamManagement()).thenReturn(jsm)
            whenever(jsm.getStreamInfo(NatsStreamSetup.STREAM_NAME)).thenReturn(mock<StreamInfo>())
            whenever(jsm.getStreamInfo(NatsStreamSetup.DLQ_STREAM_NAME)).thenReturn(mock<StreamInfo>())
            val options = org.mockito.kotlin.argumentCaptor<Options>()
            staticNats.`when`<Connection> { Nats.connect(options.capture()) }.thenReturn(connection)
            var calls = 0
            reconnects.onReconnected = { calls++ }

            newProducer().connect()
            options.firstValue.connectionListener.connectionEvent(connection, io.nats.client.ConnectionListener.Events.RECONNECTED)

            org.junit.jupiter.api.Assertions.assertEquals(1, calls)
        }
    }
}
