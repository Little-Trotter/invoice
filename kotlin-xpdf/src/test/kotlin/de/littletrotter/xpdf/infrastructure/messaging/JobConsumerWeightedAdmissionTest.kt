package de.littletrotter.xpdf.infrastructure.messaging

import de.littletrotter.shared.InvoiceDto
import io.nats.client.Message
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.after
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.timeout
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** A job that waits for the byte budget keeps its lease: heartbeat stays scheduled, no redelivery, no early ack. */
class JobConsumerWeightedAdmissionTest {

    private val pool = Executors.newFixedThreadPool(2)

    @AfterEach
    fun shutDown() {
        pool.shutdownNow()
    }

    private fun message(jobId: String): Message {
        val msg: Message = mock()
        val json = """
            {"id":"$jobId","type":"GENERATE","payload":{"invoice":{"invoiceNumber":{"value":"INV-1"},"items":[],
            "receipts":[{"fileName":"a.pdf","objectKey":"k/$jobId/a"},{"fileName":"b.pdf","objectKey":"k/$jobId/b"}]}}}
        """.trimIndent()
        whenever(msg.data).thenReturn(json.toByteArray(Charsets.UTF_8))
        whenever(msg.subject).thenReturn("document-jobs")
        return msg
    }

    @Test
    fun `a waiting job keeps its heartbeat and is acked only after it ran`() {
        // Two receipts weigh 880 KiB; the budget fits exactly one such job.
        val dispatcher: JobDispatcher = mock()
        val firstRunning = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        doAnswer {
            firstRunning.countDown()
            releaseFirst.await(10, TimeUnit.SECONDS)
            Unit
        }.doAnswer { Unit }.whenever(dispatcher).dispatch(any(), any<InvoiceDto>())
        val consumer = JobConsumer(mock(), mock(), mock(), dispatcher, mock(), JobLane.bulk(), JobWeightBudget(1000))
        consumer.executor = pool
        val heartbeats: ScheduledExecutorService = mock()
        val firstBeat: ScheduledFuture<*> = mock()
        val secondBeat: ScheduledFuture<*> = mock()
        whenever(heartbeats.scheduleAtFixedRate(any(), any(), any(), any())).thenReturn(firstBeat, secondBeat)
        consumer.heartbeatExecutor = heartbeats
        val first = message("job-1")
        val second = message("job-2")

        consumer.receive(first)
        firstRunning.await(5, TimeUnit.SECONDS)
        consumer.receive(second)

        // The second job is blocked on the budget: not dispatched, not acked, heartbeat still alive.
        verify(dispatcher, after(300).times(1)).dispatch(any(), any<InvoiceDto>())
        verify(second, never()).ack()
        verify(second, never()).nak()
        verify(secondBeat, never()).cancel(any())

        releaseFirst.countDown()

        verify(second, timeout(5_000)).ack()
        verify(dispatcher, times(2)).dispatch(any(), any<InvoiceDto>())
        verify(firstBeat, timeout(5_000)).cancel(false)
        verify(secondBeat, timeout(5_000)).cancel(false)
        assertEquals(2, org.mockito.Mockito.mockingDetails(heartbeats).invocations.count { it.method.name == "scheduleAtFixedRate" })
    }
}
