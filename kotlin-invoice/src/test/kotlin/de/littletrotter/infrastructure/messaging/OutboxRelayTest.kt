package de.littletrotter.infrastructure.messaging

import io.nats.client.Connection
import io.nats.client.JetStream
import io.nats.client.JetStreamApiException
import io.nats.client.PublishOptions
import io.nats.client.api.PublishAck
import org.junit.jupiter.api.Assertions.assertEquals
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
import java.util.concurrent.Executor

class OutboxRelayTest {

    private val repository = mock<OutboxRepository>()
    private val jetStream = mock<JetStream>()
    private val connection = mock<Connection>().also { whenever(it.status).thenReturn(Connection.Status.CONNECTED) }
    private val ack = mock<PublishAck>()
    private val reconnects = NatsReconnectListener()
    private val retries = mutableListOf<Pair<Long, Runnable>>()
    private val relay = OutboxRelay(repository, connection, jetStream, reconnects, batchSize = 2, maxAttempts = 5).apply {
        executor = Executor { it.run() }
        retryScheduler = { delay, task -> retries += delay to task }
    }

    private fun entry(id: Long) = OutboxEntry(id, "Customer", "42", "masterdata.customer.upserted", "{\"id\":$id}", 0)

    @Test
    fun `publishes each pending row with its id as message id and marks it published after the ack`() {
        whenever(repository.findPending(2)).thenReturn(listOf(entry(7)))
        whenever(jetStream.publish(any<String>(), any<ByteArray>(), any<PublishOptions>())).doReturn(ack)

        assertEquals(1, relay.relayPending())

        val options = argumentCaptor<PublishOptions>()
        val body = argumentCaptor<ByteArray>()
        verify(jetStream).publish(eq("masterdata.customer.upserted"), body.capture(), options.capture())
        assertEquals("7", options.firstValue.messageId)
        assertEquals("{\"id\":7}", String(body.firstValue, Charsets.UTF_8))
        verify(repository).markPublished(7)
    }

    @Test
    fun `a duplicate ack still marks the row published`() {
        whenever(repository.findPending(2)).thenReturn(listOf(entry(7)))
        whenever(ack.isDuplicate).thenReturn(true)
        whenever(jetStream.publish(any<String>(), any<ByteArray>(), any<PublishOptions>())).doReturn(ack)

        assertEquals(1, relay.relayPending())
        verify(repository).markPublished(7)
    }

    @Test
    fun `a full batch is followed by the next one until a short batch ends the run`() {
        whenever(repository.findPending(2)).thenReturn(listOf(entry(1), entry(2))).thenReturn(listOf(entry(3)))
        whenever(jetStream.publish(any<String>(), any<ByteArray>(), any<PublishOptions>())).doReturn(ack)

        assertEquals(3, relay.relayPending())
        verify(repository).markPublished(3)
    }

    @Test
    fun `an IO failure is recorded and does not stop the rows behind it`() {
        whenever(repository.findPending(2)).thenReturn(listOf(entry(1), entry(2)))
        whenever(jetStream.publish(any<String>(), any<ByteArray>(), any<PublishOptions>()))
            .doThrow(IOException("no responders"))
            .doReturn(ack)

        assertEquals(1, relay.relayPending())
        verify(repository).markFailed(1, "no responders", 5)
        verify(repository).markPublished(2)
        verify(repository, never()).markPublished(1)
    }

    @Test
    fun `a stream API failure without message is recorded by its class name`() {
        whenever(repository.findPending(2)).thenReturn(listOf(entry(1)))
        val failure = mock<JetStreamApiException>()
        whenever(jetStream.publish(any<String>(), any<ByteArray>(), any<PublishOptions>())).doThrow(failure)

        assertEquals(0, relay.relayPending())
        verify(repository).markFailed(eq(1), any(), eq(5))
    }

    @Test
    fun `a failed full batch ends the run instead of reading the same rows again`() {
        whenever(repository.findPending(2)).thenReturn(listOf(entry(1), entry(2)))
        whenever(jetStream.publish(any<String>(), any<ByteArray>(), any<PublishOptions>())).doThrow(IOException("down"))

        assertEquals(0, relay.relayPending())
        verify(repository).findPending(2)
    }

    @Test
    fun `an appended event, the startup recovery and trigger all start a run`() {
        whenever(repository.findPending(2)).thenReturn(emptyList())

        relay.onAppended(OutboxAppended("masterdata.customer.upserted"))
        relay.recoverOnStartup()
        relay.trigger()

        verify(repository, org.mockito.kotlin.times(3)).findPending(2)
    }

    @Test
    fun `a database failure inside a triggered run is logged and does not escape`() {
        val sql = mock<OutboxRepository>()
        whenever(sql.findPending(2)).thenAnswer { throw SQLException("db down") }
        val failing = OutboxRelay(sql, connection, jetStream, reconnects, 2, 5).apply {
            executor = Executor { it.run() }
            retryScheduler = { delay, task -> retries += delay to task }
        }

        failing.trigger()

        verify(sql).findPending(2)
    }

    @Test
    fun `shutdown stops the default worker and tolerates a custom executor`() {
        OutboxRelay(repository, connection, jetStream, reconnects, 2, 5).shutdown()
        relay.shutdown()
    }

    @Test
    fun `a row left pending is tried again after a pause until the broker takes it`() {
        whenever(repository.findPending(2)).thenReturn(listOf(entry(1)))
        whenever(jetStream.publish(any<String>(), any<ByteArray>(), any<PublishOptions>()))
            .doThrow(IOException("stream not loaded yet"))
            .doThrow(IOException("stream not loaded yet"))
            .doReturn(ack)

        relay.trigger()
        retries.removeFirst().second.run()
        retries.removeFirst().second.run()

        verify(repository).markPublished(1)
        assertEquals(emptyList<Pair<Long, Runnable>>(), retries)
    }

    @Test
    fun `the pause doubles up to a ceiling and starts small again after a clean run`() {
        whenever(repository.findPending(2)).thenReturn(listOf(entry(1)))
        whenever(jetStream.publish(any<String>(), any<ByteArray>(), any<PublishOptions>())).doThrow(IOException("down"))
        val delays = mutableListOf<Long>()

        relay.trigger()
        repeat(7) {
            val (delay, task) = retries.removeFirst()
            delays += delay
            task.run()
        }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), delays)

        whenever(repository.findPending(2)).thenReturn(emptyList())
        retries.removeFirst().second.run()
        whenever(repository.findPending(2)).thenReturn(listOf(entry(1)))
        relay.trigger()

        assertEquals(1_000L, retries.single().first)
    }

    @Test
    fun `a failed run is retried as well, with only one retry waiting at a time`() {
        whenever(repository.findPending(2)).thenAnswer { throw SQLException("db down") }

        relay.trigger()
        relay.onAppended(OutboxAppended("masterdata.customer.upserted"))

        assertEquals(1, retries.size)
    }

    @Test
    fun `nothing pending schedules no retry`() {
        whenever(repository.findPending(2)).thenReturn(emptyList())

        relay.trigger()

        assertEquals(emptyList<Pair<Long, Runnable>>(), retries)
    }

    @Test
    fun `a row parked as failed by the last attempt is not retried`() {
        whenever(repository.findPending(2)).thenReturn(listOf(OutboxEntry(1, "Customer", "42", "s", "{}", 4)))
        whenever(jetStream.publish(any<String>(), any<ByteArray>(), any<PublishOptions>())).doThrow(IOException("down"))

        relay.trigger()

        verify(repository).markFailed(1, "down", 5)
        assertEquals(emptyList<Pair<Long, Runnable>>(), retries)
    }

    @Test
    fun `the default retry runs on its own daemon thread and stops on shutdown`() {
        val own = OutboxRelay(repository, connection, jetStream, reconnects, 2, 5)
        val ran = java.util.concurrent.CountDownLatch(1)

        own.retryScheduler(1L) { ran.countDown() }

        assertEquals(true, ran.await(5, java.util.concurrent.TimeUnit.SECONDS))
        own.shutdown()
    }

    @Test
    fun `a reconnected broker connection publishes what piled up during the outage`() {
        relay.recoverOnStartup()
        whenever(repository.findPending(2)).thenReturn(listOf(entry(1)))
        whenever(jetStream.publish(any<String>(), any<ByteArray>(), any<PublishOptions>())).doReturn(ack)

        reconnects.reconnected()

        verify(repository).markPublished(1)
    }

    @Test
    fun `while the broker connection is down a run touches no row and schedules no retry`() {
        whenever(connection.status).thenReturn(Connection.Status.DISCONNECTED)
        whenever(repository.findPending(2)).thenReturn(listOf(entry(1)))

        relay.trigger()

        verify(repository, never()).findPending(any())
        verify(jetStream, never()).publish(any<String>(), any<ByteArray>(), any<PublishOptions>())
        verify(repository, never()).markFailed(any(), any(), any())
        assertEquals(emptyList<Pair<Long, Runnable>>(), retries)
    }

    @Test
    fun `the reconnect after an outage publishes the rows that waited`() {
        relay.recoverOnStartup()
        whenever(connection.status).thenReturn(Connection.Status.DISCONNECTED)
        relay.trigger()
        whenever(connection.status).thenReturn(Connection.Status.CONNECTED)
        whenever(repository.findPending(2)).thenReturn(listOf(entry(1)))
        whenever(jetStream.publish(any<String>(), any<ByteArray>(), any<PublishOptions>())).doReturn(ack)

        reconnects.reconnected()

        verify(repository).markPublished(1)
    }
}
