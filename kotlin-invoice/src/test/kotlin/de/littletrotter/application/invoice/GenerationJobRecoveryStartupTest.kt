package de.littletrotter.application.invoice

import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

class GenerationJobRecoveryStartupTest {

    private fun startup(
        executor: Executor = Executor { it.run() },
        contexts: AtomicInteger = AtomicInteger(),
        schedulerEnabled: Boolean = true,
    ): Pair<GenerationJobRecoveryStartup, GenerationJobProcessor> {
        val processor = mock<GenerationJobProcessor>()
        val recovery = GenerationJobRecoveryStartup(processor, DelegatingManagedExecutor(executor), schedulerEnabled)
        recovery.requestContextFactory = {
            contexts.incrementAndGet()
            AutoCloseable { }
        }
        return recovery to processor
    }

    @Test
    fun `startup schedules exactly one scan in a fresh request context`() {
        val contexts = AtomicInteger()
        val (recovery, processor) = startup(contexts = contexts)
        var closed = false
        recovery.requestContextFactory = {
            contexts.incrementAndGet()
            AutoCloseable { closed = true }
        }

        recovery.onStart()

        assertEquals(1, contexts.get())
        assertTrue(closed)
        verify(processor).pollPendingJobs()
    }

    @Test
    fun `boundary executor rejection is contained`() {
        val (recovery, _) = startup(executor = Executor { throw java.util.concurrent.RejectedExecutionException("closed") })

        assertDoesNotThrow { recovery.onStart() }
    }

    @Test
    fun `startup does not scan on the observer thread`() {
        val queued = mutableListOf<Runnable>()
        val (recovery, processor) = startup(executor = Executor { queued.add(it) })

        recovery.onStart()

        assertEquals(1, queued.size)
        verifyNoInteractions(processor)
        queued.single().run()
        verify(processor).pollPendingJobs()
    }

    @Test
    fun `boundary failed scan closes its request context without failing startup`() {
        val (recovery, processor) = startup()
        var closed = false
        recovery.requestContextFactory = { AutoCloseable { closed = true } }
        whenever(processor.pollPendingJobs()).thenThrow(IllegalStateException("scan unavailable"))

        assertDoesNotThrow { recovery.onStart() }

        assertTrue(closed)
        verify(processor).pollPendingJobs()
    }

    @Test
    fun `boundary request context failure is contained without scanning`() {
        val (recovery, processor) = startup()
        recovery.requestContextFactory = { throw IllegalStateException("context unavailable") }

        assertDoesNotThrow { recovery.onStart() }

        verifyNoInteractions(processor)
    }

    @Test
    fun `recovery is worker startup bean and interval is configurable`() {
        val (recovery, _) = startup(schedulerEnabled = false)
        val startup = GenerationJobRecoveryStartup::class.java.getAnnotation(Startup::class.java)
        val scheduled = GenerationJobProcessor::class.java
            .getMethod("pollPendingJobs")
            .getAnnotation(Scheduled::class.java)

        assertTrue(startup != null)
        assertEquals("{invoice.jobs.recovery-interval}", scheduled.every)
        assertDoesNotThrow { recovery.onStart() }
    }

    @Test
    fun `an unrelated runtime exception from the scan is not silently eaten`() {
        val (recovery, processor) = startup()
        whenever(processor.pollPendingJobs()).thenThrow(IllegalArgumentException("programming error"))

        assertThrows<IllegalArgumentException> { recovery.onStart() }
    }

    @Test
    fun `onStart is wired as a no-arg Quarkus startup callback`() {
        val method = GenerationJobRecoveryStartup::class.java.getMethod("onStart")

        assertTrue(method.isAnnotationPresent(Startup::class.java))
    }

    @Test
    fun `boundary disabled scheduler skips startup recovery`() {
        val contexts = AtomicInteger()
        val (recovery, processor) = startup(contexts = contexts, schedulerEnabled = false)

        recovery.onStart()

        assertEquals(0, contexts.get())
        verifyNoInteractions(processor)
    }
}
