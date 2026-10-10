package de.littletrotter.infrastructure.messaging

import de.littletrotter.application.system.WorkerPresenceWindow
import de.littletrotter.domain.repository.GenerationJobPipelineCounts
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.shared.NatsStreamSetup
import io.nats.client.Connection
import io.nats.client.JetStreamApiException
import io.nats.client.JetStreamManagement
import io.nats.client.api.ConsumerInfo
import io.nats.client.api.Error as NatsError
import io.nats.client.api.StreamInfo
import io.nats.client.api.StreamState
import io.nats.client.support.NatsJetStreamConstants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * [NatsJobPipelineStatusService] is the infra adapter wiring
 * [GenerationJobRepository.pipelineCounts] and NATS `ConsumerInfo`/`StreamInfo` into
 * [de.littletrotter.application.system.JobPipelineStatusMapper]. Everything NATS/DB-shaped is
 * mocked here — the mapping itself is covered by `JobPipelineStatusMapperTest`, and the real
 * round trip by `NatsJobPipelineStatusServiceIT`.
 */
class NatsJobPipelineStatusServiceTest {

    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-09-18T12:00:00Z"), ZoneOffset.UTC)

    private fun defaultRepository(): GenerationJobRepository {
        val repository: GenerationJobRepository = mock()
        whenever(repository.pipelineCounts(any(), any())).thenReturn(GenerationJobPipelineCounts())
        return repository
    }

    private fun service(
        connection: Connection,
        repository: GenerationJobRepository = defaultRepository(),
        clock: Clock = fixedClock,
    ) = NatsJobPipelineStatusService(
        connection = connection,
        generationJobRepository = repository,
        workerPresenceWindow = WorkerPresenceWindow(de.littletrotter.shared.time.ClockTimeSource(clock)),
        time = de.littletrotter.shared.time.ClockTimeSource(clock),
        workerConsumerName = "kotlin-xpdf-group",
        interactiveConsumerName = "kotlin-xpdf-interactive",
        resultConsumerName = "little-trotter-invoice-result-consumer",
    )

    @Test
    fun `currentStatus reads job counts for the caller's clock window and forwards them into the pipeline`() {
        val connection = mock<Connection>()
        val jsm = mock<JetStreamManagement>()
        whenever(connection.jetStreamManagement()).thenReturn(jsm)
        stubHealthyTransport(jsm)

        val repository: GenerationJobRepository = mock()
        whenever(repository.pipelineCounts(any(), any())).thenReturn(GenerationJobPipelineCounts(openCount = 6, failedCount = 1))

        val status = service(connection, repository).currentStatus()

        assertEquals(6, status.created.count)
        assertEquals(0, status.inProgress.ackPending, "the transport stub reports 0 ackPending")
        assertEquals(1, status.failed.failedCount)

        val expectedNow = OffsetDateTime.now(fixedClock)
        verify(repository).pipelineCounts(expectedNow.minusHours(24), expectedNow.minusMinutes(5))
    }

    @Test
    fun `currentStatus reports the transport line reachable and worker connected when the worker consumer has in-flight messages`() {
        val connection = mock<Connection>()
        val jsm = mock<JetStreamManagement>()
        whenever(connection.jetStreamManagement()).thenReturn(jsm)

        val worker = mock<ConsumerInfo>()
        whenever(worker.numPending).thenReturn(7L)
        whenever(worker.numAckPending).thenReturn(3L)
        whenever(worker.numWaiting).thenReturn(0L)
        whenever(worker.redelivered).thenReturn(1L)
        whenever(jsm.getConsumerInfo(NatsStreamSetup.STREAM_NAME, "kotlin-xpdf-group")).thenReturn(worker)

        val results = mock<ConsumerInfo>()
        whenever(results.numPending).thenReturn(2L)
        whenever(jsm.getConsumerInfo(NatsStreamSetup.STREAM_NAME, "little-trotter-invoice-result-consumer")).thenReturn(results)

        val dlqInfo = mock<StreamInfo>()
        val dlqState = mock<StreamState>()
        whenever(dlqState.msgCount).thenReturn(0L)
        whenever(dlqState.byteCount).thenReturn(0L)
        whenever(dlqInfo.streamState).thenReturn(dlqState)
        whenever(jsm.getStreamInfo(NatsStreamSetup.DLQ_STREAM_NAME)).thenReturn(dlqInfo)

        val status = service(connection).currentStatus()

        assertTrue(status.transport.reachable)
        assertEquals(7L, status.transport.queueDepth)
        assertEquals(3L, status.transport.ackPending)
        assertEquals(2L, status.transport.unprocessedResults)
        assertEquals(1L, status.transport.redelivered)
        assertTrue(status.inProgress.workerConnected, "numAckPending > 0 alone must mark the worker connected")
    }

    @Test
    fun `currentStatus degrades the whole transport line when the broker is unreachable, but keeps the DB job stages`() {
        val connection = mock<Connection>()
        val jsm = mock<JetStreamManagement>()
        whenever(connection.jetStreamManagement()).thenReturn(jsm)
        // IOException, not a bare RuntimeException: the real contract JetStreamManagement's own
        // getConsumerInfo declares ( fetchTransport's catch is now narrowed to it and
        // JetStreamApiException, the same two types the inner helpers below already narrow to).
        whenever(jsm.getConsumerInfo(eq(NatsStreamSetup.STREAM_NAME), any())).thenThrow(java.io.IOException("connection refused"))

        val repository: GenerationJobRepository = mock()
        whenever(repository.pipelineCounts(any(), any())).thenReturn(GenerationJobPipelineCounts(openCount = 9))

        val status = service(connection, repository).currentStatus()

        assertFalse(status.transport.reachable)
        assertEquals(9, status.created.count, "DB job stages must still render when the broker is unreachable")
        assertFalse(status.inProgress.workerConnected)
        assertEquals(null, status.inProgress.ackPending)
    }

    // resultConsumerPending narrows its catch to JetStreamApiException/IOException;
    // every other test in this file reaches the outer fetchTransport catch instead (an IOException
    // from the worker lookup), which never exercises this inner one.
    @Test
    fun `boundary - a JetStreamApiException reading the result consumer degrades unprocessedResults to 0, not the whole transport line`() {
        val connection = mock<Connection>()
        val jsm = mock<JetStreamManagement>()
        whenever(connection.jetStreamManagement()).thenReturn(jsm)
        stubHealthyTransport(jsm)
        whenever(jsm.getConsumerInfo(NatsStreamSetup.STREAM_NAME, "little-trotter-invoice-result-consumer"))
            .thenThrow(JetStreamApiException(NatsError.JsNoMessageFoundErr))

        val status = service(connection).currentStatus()

        assertTrue(status.transport.reachable, "the worker/DLQ lookups still succeeded")
        assertEquals(0L, status.transport.unprocessedResults)
    }

    // Same reasoning as above for dlqStreamState's own narrowed catch.
    @Test
    fun `boundary - a JetStreamApiException reading the DLQ stream degrades dlq counts to 0, not the whole transport line`() {
        val connection = mock<Connection>()
        val jsm = mock<JetStreamManagement>()
        whenever(connection.jetStreamManagement()).thenReturn(jsm)
        stubHealthyTransport(jsm)
        whenever(jsm.getStreamInfo(NatsStreamSetup.DLQ_STREAM_NAME))
            .thenThrow(JetStreamApiException(NatsError.JsNoMessageFoundErr))

        val status = service(connection).currentStatus()

        assertTrue(status.transport.reachable, "the worker/result lookups still succeeded")
        assertEquals(0L, status.failed.dlqMessages)
        assertEquals(0L, status.failed.dlqBytes)
    }

    // AP-EXC-1 — resultConsumerPending also narrows to IOException, a separate catch clause from
    // the JetStreamApiException one above; neither is reached by the other's stub.
    @Test
    fun `boundary - an IOException reading the result consumer degrades unprocessedResults to 0, not the whole transport line`() {
        val connection = mock<Connection>()
        val jsm = mock<JetStreamManagement>()
        whenever(connection.jetStreamManagement()).thenReturn(jsm)
        stubHealthyTransport(jsm)
        whenever(jsm.getConsumerInfo(NatsStreamSetup.STREAM_NAME, "little-trotter-invoice-result-consumer"))
            .thenThrow(java.io.IOException("broker socket closed"))

        val status = service(connection).currentStatus()

        assertTrue(status.transport.reachable, "the worker/DLQ lookups still succeeded")
        assertEquals(0L, status.transport.unprocessedResults)
    }

    // Same reasoning as above for dlqStreamState's own narrowed IOException catch.
    @Test
    fun `boundary - an IOException reading the DLQ stream degrades dlq counts to 0, not the whole transport line`() {
        val connection = mock<Connection>()
        val jsm = mock<JetStreamManagement>()
        whenever(connection.jetStreamManagement()).thenReturn(jsm)
        stubHealthyTransport(jsm)
        whenever(jsm.getStreamInfo(NatsStreamSetup.DLQ_STREAM_NAME))
            .thenThrow(java.io.IOException("broker socket closed"))

        val status = service(connection).currentStatus()

        assertTrue(status.transport.reachable, "the worker/result lookups still succeeded")
        assertEquals(0L, status.failed.dlqMessages)
        assertEquals(0L, status.failed.dlqBytes)
    }

    // rawActive is `numWaiting > 0 || numAckPending > 0`; every other test leaves numWaiting at 0
    // and only exercises the numAckPending operand, so the numWaiting-alone branch stays uncovered.
    @Test
    fun `boundary - a worker with messages waiting but no ack-pending still marks the worker connected`() {
        val connection = mock<Connection>()
        val jsm = mock<JetStreamManagement>()
        whenever(connection.jetStreamManagement()).thenReturn(jsm)

        val worker = mock<ConsumerInfo>()
        whenever(worker.numPending).thenReturn(4L)
        whenever(worker.numAckPending).thenReturn(0L)
        whenever(worker.numWaiting).thenReturn(2L)
        whenever(worker.redelivered).thenReturn(0L)
        whenever(jsm.getConsumerInfo(NatsStreamSetup.STREAM_NAME, "kotlin-xpdf-group")).thenReturn(worker)

        val results = mock<ConsumerInfo>()
        whenever(results.numPending).thenReturn(0L)
        whenever(jsm.getConsumerInfo(NatsStreamSetup.STREAM_NAME, "little-trotter-invoice-result-consumer")).thenReturn(results)

        val dlqInfo = mock<StreamInfo>()
        val dlqState = mock<StreamState>()
        whenever(dlqState.msgCount).thenReturn(0L)
        whenever(dlqState.byteCount).thenReturn(0L)
        whenever(dlqInfo.streamState).thenReturn(dlqState)
        whenever(jsm.getStreamInfo(NatsStreamSetup.DLQ_STREAM_NAME)).thenReturn(dlqInfo)

        val status = service(connection).currentStatus()

        assertTrue(status.inProgress.workerConnected, "numWaiting > 0 alone must mark the worker connected")
    }

    private fun stubHealthyTransport(jsm: JetStreamManagement) {
        val worker = mock<ConsumerInfo>()
        whenever(worker.numPending).thenReturn(0L)
        whenever(worker.numAckPending).thenReturn(0L)
        whenever(worker.numWaiting).thenReturn(0L)
        whenever(worker.redelivered).thenReturn(0L)
        whenever(jsm.getConsumerInfo(NatsStreamSetup.STREAM_NAME, "kotlin-xpdf-group")).thenReturn(worker)

        val results = mock<ConsumerInfo>()
        whenever(results.numPending).thenReturn(0L)
        whenever(jsm.getConsumerInfo(NatsStreamSetup.STREAM_NAME, "little-trotter-invoice-result-consumer")).thenReturn(results)

        val dlqInfo = mock<StreamInfo>()
        val dlqState = mock<StreamState>()
        whenever(dlqState.msgCount).thenReturn(0L)
        whenever(dlqState.byteCount).thenReturn(0L)
        whenever(dlqInfo.streamState).thenReturn(dlqState)
        whenever(jsm.getStreamInfo(NatsStreamSetup.DLQ_STREAM_NAME)).thenReturn(dlqInfo)
    }

    @Test
    fun `currentStatus sums the bulk and interactive consumers into one transport line`() {
        val connection = mock<Connection>()
        val jsm = mock<JetStreamManagement>()
        whenever(connection.jetStreamManagement()).thenReturn(jsm)
        stubHealthyTransport(jsm)

        val interactive = mock<ConsumerInfo>()
        whenever(interactive.numPending).thenReturn(4L)
        whenever(interactive.numAckPending).thenReturn(1L)
        whenever(interactive.numWaiting).thenReturn(0L)
        whenever(interactive.redelivered).thenReturn(2L)
        whenever(jsm.getConsumerInfo(NatsStreamSetup.STREAM_NAME, "kotlin-xpdf-interactive")).thenReturn(interactive)

        val status = service(connection).currentStatus()

        assertTrue(status.transport.reachable)
        assertEquals(4L, status.transport.queueDepth)
        assertEquals(1L, status.transport.ackPending)
        assertEquals(2L, status.transport.redelivered)
        assertTrue(status.inProgress.workerConnected, "interactive in-flight work alone marks the worker connected")
    }

    @Test
    fun `currentStatus tolerates a missing interactive consumer`() {
        val connection = mock<Connection>()
        val jsm = mock<JetStreamManagement>()
        whenever(connection.jetStreamManagement()).thenReturn(jsm)
        stubHealthyTransport(jsm)
        val noConsumer = mock<JetStreamApiException>()
        whenever(noConsumer.apiErrorCode).thenReturn(NatsJetStreamConstants.JS_CONSUMER_NOT_FOUND_ERR)
        whenever(jsm.getConsumerInfo(NatsStreamSetup.STREAM_NAME, "kotlin-xpdf-interactive")).thenThrow(noConsumer)

        val status = service(connection).currentStatus()

        assertTrue(status.transport.reachable)
        assertEquals(0L, status.transport.queueDepth)
    }
}
