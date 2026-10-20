package de.littletrotter.xpdf.infrastructure.messaging

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
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException

/**
 * AP-M10 — no test existed for this class
 * at all before this AP. `Nats.connect` is a static factory method on a third-party client, so it
 * is intercepted via `mockStatic` (same documented pattern as `EnvConfig.readEnv` elsewhere in
 * this module) rather than requiring a live broker for every branch; the branches that genuinely
 * need one (stream-already-exists vs. race-lost-then-found) are exercised against a real
 * [JetStreamManagement] mock instead, matching [de.littletrotter.shared.NatsStreamSetup]'s own
 * unit tests in kotlin-shared.
 *
 * AP-M3 — connect() now ensures both the main stream and the DLQ stream (both via
 * [NatsStreamSetup], which owns the actual getStreamInfo/addStream/updateStream sequencing); these
 * tests only need to prove both are reached, not re-verify NatsStreamSetup's own branches.
 */
class NatsConnectionProducerTest {

    private fun newProducer(): NatsConnectionProducer =
        NatsConnectionProducer(
            natsUrl = "nats://localhost:4222",
            jobsSubject = "document-jobs",
            interactiveJobsSubject = "document-jobs-interactive",
            resultsSubject = "document-job-results",
            jobsDlqSubject = "document-jobs-dlq",
            resultsDlqSubject = "document-job-results-dlq",
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
                NatsStreamSetup.configuration(listOf("document-jobs", "document-jobs-interactive", "document-job-results", NatsStreamSetup.OFFER_RESULTS_SUBJECT, NatsStreamSetup.PDF_CHECK_SUBJECT, NatsStreamSetup.PDF_CHECK_RESULT_SUBJECT)),
            )
            val dlqInfo = mock<StreamInfo>()
            whenever(dlqInfo.configuration).thenReturn(
                NatsStreamSetup.dlqConfiguration(listOf("document-jobs-dlq", "document-job-results-dlq", NatsStreamSetup.PDF_CHECK_DLQ_SUBJECT)),
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

            verify(jsm, org.mockito.kotlin.times(2)).getStreamInfo(NatsStreamSetup.STREAM_NAME)
            verify(jsm, org.mockito.kotlin.times(2)).getStreamInfo(NatsStreamSetup.DLQ_STREAM_NAME)
            verify(jsm, org.mockito.kotlin.times(2)).addStream(any())
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
    fun `close is a no-op when connect was never called`() {
        val producer = newProducer()
        // Must not throw UninitializedPropertyAccessException / NPE — connect() was never
        // invoked, so the lateinit connection field was never assigned.
        producer.close()
    }
}
