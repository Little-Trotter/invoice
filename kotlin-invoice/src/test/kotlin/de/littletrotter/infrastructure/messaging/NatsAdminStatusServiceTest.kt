package de.littletrotter.infrastructure.messaging

import de.littletrotter.application.invoice.DocumentJobPublisher
import de.littletrotter.shared.NatsStreamSetup
import io.nats.client.Connection
import io.nats.client.JetStreamApiException
import io.nats.client.JetStreamManagement
import io.nats.client.api.ConsumerInfo
import io.nats.client.api.Error as NatsError
import io.nats.client.api.StreamInfo
import io.nats.client.api.StreamState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * AP-M3 §5 — no test existed for this class before this
 * AP either; it was only reached indirectly via `@QuarkusTest`s that boot the whole app. These
 * tests focus on the new DLQ visibility behavior (message count + byte size from the DLQ stream's
 * own `StreamInfo`) added here, plus the pre-existing happy/error paths it sits alongside.
 */
class NatsAdminStatusServiceTest {

    private fun service(
        connection: Connection,
        publisher: DocumentJobPublisher = mock(),
        subjectConsumers: String = "document-jobs=kotlin-xpdf-group,document-job-results=little-trotter-invoice-result-consumer",
    ) = NatsAdminStatusService(
        connection = connection,
        publisher = publisher,
        natsUrl = "nats://nats:4222",
        subjectConsumers = subjectConsumers,
    )

    @Test
    fun `currentStatus reports the DLQ stream's message count and byte size alongside the main stream status`() {
        val connection = mock<Connection>()
        val jsm = mock<JetStreamManagement>()
        whenever(connection.jetStreamManagement()).thenReturn(jsm)

        val mainStreamInfo = mock<StreamInfo>()
        val mainState = mock<StreamState>()
        whenever(mainState.subjectMap).thenReturn(mapOf("document-jobs" to 3L, "document-job-results" to 2L))
        whenever(mainStreamInfo.streamState).thenReturn(mainState)
        whenever(jsm.getStreamInfo(eq(NatsStreamSetup.STREAM_NAME), any())).thenReturn(mainStreamInfo)

        val consumerInfo = mock<ConsumerInfo>()
        whenever(consumerInfo.numPending).thenReturn(1L)
        whenever(jsm.getConsumerInfo(NatsStreamSetup.STREAM_NAME, "little-trotter-invoice-result-consumer"))
            .thenReturn(consumerInfo)

        // The worker consumer's own numPending, now the document-jobs subject's real lag.
        val workerConsumerInfo = mock<ConsumerInfo>()
        whenever(workerConsumerInfo.numPending).thenReturn(5L)
        whenever(jsm.getConsumerInfo(NatsStreamSetup.STREAM_NAME, "kotlin-xpdf-group"))
            .thenReturn(workerConsumerInfo)

        val dlqStreamInfo = mock<StreamInfo>()
        val dlqState = mock<StreamState>()
        whenever(dlqState.msgCount).thenReturn(4L)
        whenever(dlqState.byteCount).thenReturn(2048L)
        whenever(dlqStreamInfo.streamState).thenReturn(dlqState)
        whenever(jsm.getStreamInfo(NatsStreamSetup.DLQ_STREAM_NAME)).thenReturn(dlqStreamInfo)

        val status = service(connection).currentStatus()

        assertTrue(status.brokerUp)
        assertEquals(4L, status.dlqMessages)
        assertEquals(2048L, status.dlqBytes)
        assertEquals(2, status.subjects.size)
    }

    // The document-jobs subject grid row used to hard-code 0 lag; it now shows the
    // worker consumer's own numPending, the real backlog an operator cares about.
    @Test
    fun `currentStatus reports the worker consumer's numPending as the document-jobs subject's lag`() {
        val connection = mock<Connection>()
        val jsm = mock<JetStreamManagement>()
        whenever(connection.jetStreamManagement()).thenReturn(jsm)

        val mainStreamInfo = mock<StreamInfo>()
        val mainState = mock<StreamState>()
        whenever(mainState.subjectMap).thenReturn(mapOf("document-jobs" to 10L, "document-job-results" to 2L))
        whenever(mainStreamInfo.streamState).thenReturn(mainState)
        whenever(jsm.getStreamInfo(eq(NatsStreamSetup.STREAM_NAME), any())).thenReturn(mainStreamInfo)

        val resultConsumerInfo = mock<ConsumerInfo>()
        whenever(resultConsumerInfo.numPending).thenReturn(0L)
        whenever(jsm.getConsumerInfo(NatsStreamSetup.STREAM_NAME, "little-trotter-invoice-result-consumer"))
            .thenReturn(resultConsumerInfo)

        val workerConsumerInfo = mock<ConsumerInfo>()
        whenever(workerConsumerInfo.numPending).thenReturn(631L)
        whenever(jsm.getConsumerInfo(NatsStreamSetup.STREAM_NAME, "kotlin-xpdf-group"))
            .thenReturn(workerConsumerInfo)

        whenever(jsm.getStreamInfo(NatsStreamSetup.DLQ_STREAM_NAME)).thenThrow(java.io.IOException("no dlq"))

        val status = service(connection).currentStatus()

        val jobsSubject = status.subjects.single { it.name == "document-jobs" }
        assertEquals(631L, jobsSubject.consumerLag)
        val resultsSubject = status.subjects.single { it.name == "document-job-results" }
        assertEquals(0L, resultsSubject.consumerLag)
    }

    @Test
    fun `currentStatus defaults the DLQ count and size to zero when the DLQ stream query fails`() {
        val connection = mock<Connection>()
        val jsm = mock<JetStreamManagement>()
        whenever(connection.jetStreamManagement()).thenReturn(jsm)

        val mainStreamInfo = mock<StreamInfo>()
        val mainState = mock<StreamState>()
        whenever(mainState.subjectMap).thenReturn(emptyMap())
        whenever(mainStreamInfo.streamState).thenReturn(mainState)
        whenever(jsm.getStreamInfo(eq(NatsStreamSetup.STREAM_NAME), any())).thenReturn(mainStreamInfo)
        // AP-EXC-1: NatsAdminStatusService now narrows these two inner catches to the exception
        // types getConsumerInfo/getStreamInfo actually declare (IOException, JetStreamApiException)
        // instead of a blanket Exception — a bare RuntimeException would no longer be swallowed
        // here and would incorrectly flip the whole status to brokerUp=false via the outer catch.
        whenever(jsm.getConsumerInfo(any(), any())).thenThrow(java.io.IOException("no consumer yet"))
        whenever(jsm.getStreamInfo(NatsStreamSetup.DLQ_STREAM_NAME)).thenThrow(java.io.IOException("DLQ stream not found"))

        val status = service(connection).currentStatus()

        assertTrue(status.brokerUp, "the main stream still answered — overall status must stay up")
        assertEquals(0L, status.dlqMessages)
        assertEquals(0L, status.dlqBytes)
    }

    // AP-EXC-1 — consumerPending's catch is narrowed to JetStreamApiException/IOException; the
    // existing "defaults DLQ to zero" test only ever threw IOException from getConsumerInfo, so
    // the JetStreamApiException branch was never exercised.
    @Test
    fun `boundary - a JetStreamApiException reading a consumer's pending count degrades its lag to 0, not the whole status`() {
        val connection = mock<Connection>()
        val jsm = mock<JetStreamManagement>()
        whenever(connection.jetStreamManagement()).thenReturn(jsm)

        val mainStreamInfo = mock<StreamInfo>()
        val mainState = mock<StreamState>()
        whenever(mainState.subjectMap).thenReturn(mapOf("document-jobs" to 3L, "document-job-results" to 2L))
        whenever(mainStreamInfo.streamState).thenReturn(mainState)
        whenever(jsm.getStreamInfo(eq(NatsStreamSetup.STREAM_NAME), any())).thenReturn(mainStreamInfo)
        whenever(jsm.getConsumerInfo(any(), any()))
            .thenThrow(JetStreamApiException(NatsError.JsNoMessageFoundErr))
        whenever(jsm.getStreamInfo(NatsStreamSetup.DLQ_STREAM_NAME)).thenThrow(java.io.IOException("no dlq"))

        val status = service(connection).currentStatus()

        assertTrue(status.brokerUp, "the main stream still answered")
        assertTrue(status.subjects.all { it.consumerLag == 0L })
    }

    // Same reasoning as above for the DLQ stream's own narrowed catch.
    @Test
    fun `boundary - a JetStreamApiException reading the DLQ stream degrades its count and size to 0, not the whole status`() {
        val connection = mock<Connection>()
        val jsm = mock<JetStreamManagement>()
        whenever(connection.jetStreamManagement()).thenReturn(jsm)

        val mainStreamInfo = mock<StreamInfo>()
        val mainState = mock<StreamState>()
        whenever(mainState.subjectMap).thenReturn(emptyMap())
        whenever(mainStreamInfo.streamState).thenReturn(mainState)
        whenever(jsm.getStreamInfo(eq(NatsStreamSetup.STREAM_NAME), any())).thenReturn(mainStreamInfo)
        whenever(jsm.getConsumerInfo(any(), any())).thenThrow(java.io.IOException("no consumer yet"))
        whenever(jsm.getStreamInfo(NatsStreamSetup.DLQ_STREAM_NAME))
            .thenThrow(JetStreamApiException(NatsError.JsNoMessageFoundErr))

        val status = service(connection).currentStatus()

        assertTrue(status.brokerUp, "the main stream still answered")
        assertEquals(0L, status.dlqMessages)
        assertEquals(0L, status.dlqBytes)
    }

    // Each lane has its own durable consumer: the bulk and the interactive row must show their own
    // backlog, not one shared number. Entries without a consumer are dropped.
    @Test
    fun `each job lane reports the pending count of its own consumer, malformed entries are dropped`() {
        val connection = mock<Connection>()
        val jsm = mock<JetStreamManagement>()
        whenever(connection.jetStreamManagement()).thenReturn(jsm)

        val mainStreamInfo = mock<StreamInfo>()
        val mainState = mock<StreamState>()
        whenever(mainState.subjectMap).thenReturn(
            mapOf("document-jobs" to 500L, "document-jobs-interactive" to 1L, "document-job-results" to 2L),
        )
        whenever(mainStreamInfo.streamState).thenReturn(mainState)
        whenever(jsm.getStreamInfo(eq(NatsStreamSetup.STREAM_NAME), any())).thenReturn(mainStreamInfo)

        fun consumerWithPending(name: String, pending: Long) {
            val info = mock<ConsumerInfo>()
            whenever(info.numPending).thenReturn(pending)
            whenever(jsm.getConsumerInfo(NatsStreamSetup.STREAM_NAME, name)).thenReturn(info)
        }
        consumerWithPending("kotlin-xpdf-group", 499L)
        consumerWithPending("kotlin-xpdf-interactive", 1L)
        consumerWithPending("little-trotter-invoice-result-consumer", 0L)
        whenever(jsm.getStreamInfo(NatsStreamSetup.DLQ_STREAM_NAME)).thenThrow(java.io.IOException("no dlq"))

        val status = service(
            connection,
            subjectConsumers = "document-jobs=kotlin-xpdf-group, ,no-consumer," +
                "document-jobs-interactive=kotlin-xpdf-interactive," +
                "document-job-results=little-trotter-invoice-result-consumer",
        ).currentStatus()

        assertTrue(status.brokerUp)
        assertEquals(listOf("document-jobs", "document-jobs-interactive", "document-job-results"), status.subjects.map { it.name })
        assertEquals(499L, status.subjects.single { it.name == "document-jobs" }.consumerLag)
        assertEquals(1L, status.subjects.single { it.name == "document-jobs-interactive" }.consumerLag)
        assertEquals(0L, status.subjects.single { it.name == "document-job-results" }.consumerLag)
        assertEquals(500L, status.totalLag)
    }

    @Test
    fun `currentStatus reports broker down with zeroed subjects when the main stream query fails`() {
        val connection = mock<Connection>()
        val jsm = mock<JetStreamManagement>()
        whenever(connection.jetStreamManagement()).thenReturn(jsm)
        whenever(jsm.getStreamInfo(eq(NatsStreamSetup.STREAM_NAME), any())).thenThrow(RuntimeException("connection refused"))

        val status = service(connection).currentStatus()

        assertFalse(status.brokerUp)
        assertEquals("connection refused", status.error)
        assertEquals(0L, status.dlqMessages)
        assertEquals(0L, status.dlqBytes)
        assertTrue(status.subjects.all { it.retainedMessages == 0L && it.consumerLag == 0L })
    }

    // `ex.message ?: "NATS status unavailable"`'s own null-message arm: the test
    // above only ever threw a RuntimeException carrying a real message.
    @Test
    fun `boundary - currentStatus falls back to a generic error text when the failing exception carries no message`() {
        val connection = mock<Connection>()
        val jsm = mock<JetStreamManagement>()
        whenever(connection.jetStreamManagement()).thenReturn(jsm)
        whenever(jsm.getStreamInfo(eq(NatsStreamSetup.STREAM_NAME), any())).thenThrow(RuntimeException())

        val status = service(connection).currentStatus()

        assertFalse(status.brokerUp)
        assertEquals("NATS status unavailable", status.error)
    }
}
