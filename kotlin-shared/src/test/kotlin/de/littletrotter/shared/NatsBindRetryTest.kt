package de.littletrotter.shared

import io.nats.client.JetStreamApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException
import java.time.Duration
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class NatsBindRetryTest {

    private val scheduler = mock<ScheduledExecutorService>()
    private val retry = NatsBindRetry("test consumer", Duration.ofSeconds(30)) { scheduler }
    private val givenUp = mutableListOf<Exception>()
    private var attempts = 0

    private fun apiError(code: Int): JetStreamApiException = mock<JetStreamApiException>().also { whenever(it.apiErrorCode).thenReturn(code) }

    private fun nextRun(times: Int = 1): Runnable {
        val captor = argumentCaptor<Runnable>()
        verify(scheduler, times(times)).schedule(captor.capture(), eq(30_000L), eq(TimeUnit.MILLISECONDS))
        return captor.lastValue
    }

    @Test
    fun `a successful first bind schedules nothing`() {
        retry.bind(givenUp::add) { attempts++ }

        assertEquals(1, attempts)
        verify(scheduler, never()).schedule(any<Runnable>(), any(), any())
    }

    @Test
    fun `a missing stream is retried until the bind works and then stops`() {
        retry.bind(givenUp::add) {
            attempts++
            if (attempts == 1) throw apiError(NatsBindRetry.STREAM_NOT_FOUND)
        }
        assertEquals(1, attempts)

        nextRun().run()

        assertEquals(2, attempts)
        verify(scheduler, times(1)).schedule(any<Runnable>(), any(), any())
        assertTrue(givenUp.isEmpty())
    }

    @Test
    fun `an unreachable broker keeps being retried`() {
        retry.bind(givenUp::add) {
            attempts++
            if (attempts < 3) throw IOException("down")
        }
        nextRun().run()
        nextRun(times = 2).run()

        assertEquals(3, attempts)
        verify(scheduler, times(2)).schedule(any<Runnable>(), any(), any())
    }

    @Test
    fun `another JetStream error is handed over without a retry`() {
        val failure = apiError(OTHER_ERROR)

        retry.bind(givenUp::add) { throw failure }

        assertEquals(listOf<Exception>(failure), givenUp)
        verify(scheduler, never()).schedule(any<Runnable>(), any(), any())
    }

    @Test
    fun `closing cancels the pending retry and suppresses further ones`() {
        retry.bind(givenUp::add) {
            attempts++
            throw IOException("down")
        }
        val pending = nextRun()

        retry.close()
        pending.run()

        verify(scheduler).shutdownNow()
        assertEquals(2, attempts)
        verify(scheduler, times(1)).schedule(any<Runnable>(), any(), any())
    }

    @Test
    fun `closing without any failure has nothing to stop`() {
        retry.bind(givenUp::add) { attempts++ }

        retry.close()

        verify(scheduler, never()).shutdownNow()
    }

    @Test
    fun `the default scheduler is a daemon thread that runs the retry`() {
        val real = NatsBindRetry("real", Duration.ofMillis(10))
        val done = java.util.concurrent.CountDownLatch(1)
        var thread: Thread? = null

        real.bind(givenUp::add) {
            attempts++
            if (attempts == 1) throw IOException("down")
            thread = Thread.currentThread()
            done.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        real.close()

        assertTrue(thread!!.isDaemon)
    }

    private companion object {
        const val OTHER_ERROR = 10014
    }
}
