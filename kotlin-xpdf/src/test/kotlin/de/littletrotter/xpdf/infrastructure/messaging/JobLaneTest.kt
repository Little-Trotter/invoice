package de.littletrotter.xpdf.infrastructure.messaging

import de.littletrotter.shared.NatsStreamSetup
import io.nats.client.Connection
import io.nats.client.ConsumeOptions
import io.nats.client.JetStream
import io.nats.client.JetStreamManagement
import io.nats.client.MessageHandler
import io.nats.client.ConsumerContext
import io.nats.client.StreamContext
import io.nats.client.api.ConsumerConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class JobLaneTest {

    @AfterEach
    fun cleanup() {
        System.clearProperty("job.worker.threads")
        System.clearProperty("job.interactive.worker.threads")
    }

    @Test
    fun `bulk lane keeps the established subject and durable and honours the worker thread override`() {
        System.setProperty("job.worker.threads", "3")

        val lane = JobLane.bulk()

        assertEquals("document-jobs", lane.subject)
        assertEquals("kotlin-xpdf-group", lane.durableName)
        assertEquals(3, lane.workerThreads)
    }

    @Test
    fun `interactive lane listens on its own subject and durable with one thread by default`() {
        val lane = JobLane.interactive()

        assertEquals("document-jobs-interactive", lane.subject)
        assertEquals("kotlin-xpdf-interactive", lane.durableName)
        assertEquals(1, lane.workerThreads)
    }

    @Test
    fun `interactive worker threads are configured independently of the bulk threads`() {
        System.setProperty("job.worker.threads", "6")
        System.setProperty("job.interactive.worker.threads", "2")

        assertEquals(2, JobLane.interactive().workerThreads)
        assertEquals(6, JobLane.bulk().workerThreads)
    }

    @Test
    fun `the two lanes never share a filter subject or a durable consumer`() {
        val bulk = JobLane.bulk()
        val interactive = JobLane.interactive()

        org.junit.jupiter.api.Assertions.assertNotEquals(bulk.subject, interactive.subject)
        org.junit.jupiter.api.Assertions.assertNotEquals(bulk.durableName, interactive.durableName)
    }

    @Test
    fun `defaultWorkerThreads leaves heap for the interactive lane thread`() {
        val heap = 2_000L * 1024 * 1024
        val withReserve = JobConsumer.defaultWorkerThreads(maxHeapBytes = heap)
        val withoutReserve = (heap - JobConsumer.BOOT_RESERVE_BYTES - JobConsumer.RECEIPT_RESERVE_BYTES) /
            JobConsumer.DEFAULT_PER_THREAD_RENDER_BYTES

        assertEquals(withoutReserve - JobLane.DEFAULT_INTERACTIVE_THREADS, withReserve.toLong())
    }

    @Test
    fun `each lane consumer ensures its own durable with a lane specific filter and maxAckPending`() {
        val connection: Connection = mock()
        val jetStream: JetStream = mock()
        val jsm: JetStreamManagement = mock()
        val streamContext: StreamContext = mock()
        val bulkContext: ConsumerContext = mock()
        val interactiveContext: ConsumerContext = mock()
        whenever(connection.jetStreamManagement()).thenReturn(jsm)
        whenever(connection.jetStream()).thenReturn(jetStream)
        whenever(jsm.addOrUpdateConsumer(any(), any())).thenReturn(mock())
        whenever(jetStream.getStreamContext(NatsStreamSetup.STREAM_NAME)).thenReturn(streamContext)
        whenever(streamContext.getConsumerContext("kotlin-xpdf-group")).thenReturn(bulkContext)
        whenever(streamContext.getConsumerContext("kotlin-xpdf-interactive")).thenReturn(interactiveContext)

        System.setProperty("job.worker.threads", "4")
        JobConsumerLanes(
            connection, mock(), mock(), mock(), JobWeightBudget.unlimited(),
            JobLaneSubjects("document-jobs", "kotlin-xpdf-group", "document-jobs-interactive"),
        ).subscribeAll()

        val configs = argumentCaptor<ConsumerConfiguration>()
        verify(jsm, times(2)).addOrUpdateConsumer(eq(NatsStreamSetup.STREAM_NAME), configs.capture())
        val byDurable = configs.allValues.associateBy { it.durable }
        assertEquals("document-jobs", byDurable.getValue("kotlin-xpdf-group").filterSubject)
        assertEquals(8L, byDurable.getValue("kotlin-xpdf-group").maxAckPending)
        assertEquals("document-jobs-interactive", byDurable.getValue("kotlin-xpdf-interactive").filterSubject)
        assertEquals(2L, byDurable.getValue("kotlin-xpdf-interactive").maxAckPending)

        verify(bulkContext).consume(any<ConsumeOptions>(), any<MessageHandler>())
        verify(interactiveContext).consume(any<ConsumeOptions>(), any<MessageHandler>())
    }
}
