package de.littletrotter.application.invoice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * [DelegatingManagedExecutor] exists only so [GenerationJobProcessor]'s non-CDI
 * constructors can supply a trivial [ManagedExecutor] without a real managed pool (see that
 * class's own KDoc). Every member below is a thin, deterministic stand-in for the delegate
 * executor's own behavior, so these tests pin exactly that: every overload really does delegate,
 * a rejecting/failing delegate propagates instead of being swallowed, and the never-really-
 * shutting-down/never-really-terminating lifecycle members stay fixed no matter what the delegate
 * does.
 */
class DelegatingManagedExecutorTest {

    @Test
    fun `happy path - execute hands the command straight to the delegate`() {
        val received = mutableListOf<Runnable>()
        val executor = DelegatingManagedExecutor(Executor { command -> received.add(command) })
        val task = Runnable {}

        executor.execute(task)

        assertEquals(listOf(task), received)
    }

    @Test
    fun `boundary - execute propagates a rejection from the delegate instead of swallowing it`() {
        val delegate = Executor { throw RejectedExecutionException("pool exhausted") }
        val executor = DelegatingManagedExecutor(delegate)

        assertThrows(RejectedExecutionException::class.java) { executor.execute(Runnable {}) }
    }

    @Test
    fun `boundary - shutdown lifecycle members report a permanently running, non-terminating executor`() {
        val executor = DelegatingManagedExecutor(Executor { it.run() })

        // shutdown()/shutdownNow() are no-ops on purpose -- this adapter never owns the delegate's
        // lifecycle, so callers must never observe it as shut down or terminated.
        executor.shutdown()
        assertTrue(executor.shutdownNow().isEmpty())
        assertFalse(executor.isShutdown)
        assertFalse(executor.isTerminated)
        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS))
    }

    @Test
    fun `boundary - runAsync and supplyAsync run through this executor, preserving the delegate wrapper for every overload`() {
        var executed = false
        val executor = DelegatingManagedExecutor(Executor { command -> executed = true; command.run() })

        val ran = executor.runAsync(Runnable { }).also { it.join() }
        assertTrue(executed)
        assertEquals(null, ran.join())

        executed = false
        val supplied = executor.supplyAsync { 42 }
        assertEquals(42, supplied.join())
        assertTrue(executed, "supplyAsync must route through this executor's own execute(), not a foreign pool")
    }

    @Test
    fun `boundary - completedFuture, completedStage, failedFuture, failedStage and newIncompleteFuture carry the exact value or cause`() {
        val executor = DelegatingManagedExecutor(Executor { it.run() })

        assertEquals("ok", executor.completedFuture("ok").join())
        assertEquals("ok", (executor.completedStage("ok") as CompletableFuture<String>).join())

        val cause = RuntimeException("boom")
        val failedFuture = executor.failedFuture<String>(cause)
        assertSame(cause, assertThrows(java.util.concurrent.ExecutionException::class.java) { failedFuture.get() }.cause)

        val failedStage = executor.failedStage<String>(cause) as CompletionStage<String>
        val failedCf = failedStage as CompletableFuture<String>
        assertSame(cause, assertThrows(java.util.concurrent.ExecutionException::class.java) { failedCf.get() }.cause)

        val incomplete = executor.newIncompleteFuture<String>()
        assertFalse(incomplete.isDone)
    }

    @Test
    fun `boundary - copy returns the exact same stage instance, not a real copy`() {
        val executor = DelegatingManagedExecutor(Executor { it.run() })
        val future = CompletableFuture.completedFuture("x")

        assertSame(future, executor.copy(future))
        assertSame(future, executor.copy(future as CompletionStage<String>))
    }

    @Test
    fun `boundary - getThreadContext throws because this adapter carries no ThreadContext`() {
        val executor = DelegatingManagedExecutor(Executor { it.run() })

        assertThrows(UnsupportedOperationException::class.java) { executor.threadContext }
    }
}
