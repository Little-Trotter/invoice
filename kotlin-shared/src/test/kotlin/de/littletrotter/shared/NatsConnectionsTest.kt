package de.littletrotter.shared

import io.nats.client.Connection
import io.nats.client.ConnectionListener
import io.nats.client.JetStreamManagement
import io.nats.client.Nats
import io.nats.client.Options
import io.nats.client.api.StreamInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * [NatsConnections.connect] is straight-line code (no branches of its own): the two
 * "stream already exists" vs. "stream missing, race lost" branches it delegates into live in
 * [NatsStreamSetup] and are covered by that class's own tests, as well as by both apps'
 * `NatsConnectionProducerTest` (little-trotter-invoice/kotlin-xpdf), which exercise this method
 * through their own CDI producer. This test only proves the wiring: options built from the given
 * URL, `Nats.connect` invoked, both streams ensured on the resulting connection's
 * `JetStreamManagement`, and the connection itself returned unchanged.
 */
class NatsConnectionsTest {

    private fun streamsReadyConnection(): Connection {
        val connection = mock<Connection>()
        val jsm = mock<JetStreamManagement>()
        whenever(connection.jetStreamManagement()).thenReturn(jsm)
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
        return connection
    }

    @Test
    fun `the reconnect callback fires on a restored connection only`() {
        mockStatic(Nats::class.java).use { staticNats ->
            val options = argumentCaptor<Options>()
            val connection = streamsReadyConnection()
            staticNats.`when`<Connection> { Nats.connect(options.capture()) }.thenReturn(connection)
            var reconnects = 0

            NatsConnections.connect(
                natsUrl = "nats:4222",
                workQueue = NatsJobResultSubjects(jobsSubject = "document-jobs", resultsSubject = "document-job-results"),
                dlq = NatsJobResultSubjects(jobsSubject = "document-jobs-dlq", resultsSubject = "document-job-results-dlq"),
                interactiveJobsSubject = "document-jobs-interactive",
                onReconnected = { reconnects++ },
            )
            val listener = options.firstValue.connectionListener
            listener.connectionEvent(mock<Connection>(), ConnectionListener.Events.DISCONNECTED)
            listener.connectionEvent(mock<Connection>(), ConnectionListener.Events.RECONNECTED)

            assertEquals(1, reconnects)
        }
    }

    @Test
    fun `connect opens a NATS connection and ensures both streams`() {
        mockStatic(Nats::class.java).use { staticNats ->
            val connection = mock<Connection>()
            val jsm = mock<JetStreamManagement>()
            whenever(connection.jetStreamManagement()).thenReturn(jsm)
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

            val result = NatsConnections.connect(
                natsUrl = "nats:4222,localhost:4222",
                workQueue = NatsJobResultSubjects(jobsSubject = "document-jobs", resultsSubject = "document-job-results"),
                dlq = NatsJobResultSubjects(jobsSubject = "document-jobs-dlq", resultsSubject = "document-job-results-dlq"),
            interactiveJobsSubject = "document-jobs-interactive",
            )

            assertSame(connection, result)
            verify(jsm).getStreamInfo(NatsStreamSetup.STREAM_NAME)
            verify(jsm).getStreamInfo(NatsStreamSetup.DLQ_STREAM_NAME)
            staticNats.verify { Nats.connect(any<Options>()) }
        }
    }
}
