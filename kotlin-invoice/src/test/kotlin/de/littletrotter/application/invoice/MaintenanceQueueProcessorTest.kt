package de.littletrotter.application.invoice

import de.littletrotter.application.system.port.outbound.MaintenanceDrainLease
import de.littletrotter.application.system.port.outbound.MaintenanceExecutionFence
import de.littletrotter.application.system.port.outbound.MaintenanceExecutionFencedException
import de.littletrotter.application.system.port.outbound.MaintenanceOperationOutcome
import de.littletrotter.application.system.port.outbound.MaintenanceOperations
import de.littletrotter.application.system.port.outbound.MaintenanceQueuePort
import de.littletrotter.domain.system.DatabaseMaintenanceType
import io.quarkus.arc.Arc
import io.quarkus.arc.ArcContainer
import io.quarkus.arc.ManagedContext
import io.quarkus.runtime.Startup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.ArrayDeque
import java.util.concurrent.Executor

class MaintenanceQueueProcessorTest {

    @Test
    fun `requestDrain returns after scheduling and coalesces concurrent hints`() {
        val executor = QueuedExecutor()
        val queue = FakeQueue()
        val processor = processor(queue, succeedingOperations(), executor)

        processor.requestDrain()
        processor.requestDrain()

        assertEquals(1, executor.size)
        assertEquals(0, queue.leaseAttempts)
        executor.runNext()
        assertEquals(0, executor.size)
        assertEquals(1, queue.leaseAttempts)
    }

    @Test
    fun `runtime scheduler guard ignores wakeups in the application process`() {
        val executor = QueuedExecutor()
        val processor = processor(FakeQueue(), succeedingOperations(), executor, schedulerEnabled = false)

        processor.requestDrain()
        processor.pollPendingMaintenanceJobs()
        processor.onStart()

        assertEquals(0, executor.size)
    }

    @Test
    fun `startup schedules immediate durable recovery in a worker`() {
        val executor = QueuedExecutor()
        val queue = FakeQueue()
        val processor = processor(queue, succeedingOperations(), executor)

        processor.onStart()
        executor.runNext()

        assertEquals(1, queue.recoveryCalls)
    }

    @Test
    fun `onStart is wired as a no-arg Quarkus startup callback`() {
        val method = MaintenanceQueueProcessor::class.java.getMethod("onStart")

        assertTrue(method.isAnnotationPresent(Startup::class.java))
    }

    @Test
    fun `successful claim is completed explicitly on its original durable row`() {
        val job = job(42, DatabaseMaintenanceType.RECALCULATE_TOTALS)
        val queue = FakeQueue(ArrayDeque(listOf(job)))
        val processor = processor(queue, succeedingOperations(), QueuedExecutor())

        processor.requestDrain()
        processor.executor().runNext()

        assertEquals(listOf(42L), queue.completed.map { it.first.eventId })
        assertTrue(queue.completed.single().second is MaintenanceOperationOutcome.Succeeded)
    }

    @Test
    fun `failed recalculation is durably completed and stops this drain`() {
        val first = job(7, DatabaseMaintenanceType.RECALCULATE_TOTALS)
        val second = job(8, DatabaseMaintenanceType.QUEUE_ISSUED_GENERATION_JOBS)
        val queue = FakeQueue(ArrayDeque(listOf(first, second)))
        val operations = object : MaintenanceOperations {
            override fun execute(type: DatabaseMaintenanceType, fence: MaintenanceExecutionFence) =
                MaintenanceOperationOutcome.Failed("bad totals")
        }
        val executor = QueuedExecutor()
        val processor = processor(queue, operations, executor)

        processor.requestDrain()
        executor.runNext()

        assertEquals(listOf(7L), queue.completed.map { it.first.eventId })
        assertEquals(1, queue.remainingClaims)
        assertEquals(0, executor.size)
    }

    @Test
    fun `handler exception becomes an explicit failed completion`() {
        val queue = FakeQueue(ArrayDeque(listOf(job(9, DatabaseMaintenanceType.RECALCULATE_TOTALS))))
        val operations = object : MaintenanceOperations {
            override fun execute(type: DatabaseMaintenanceType, fence: MaintenanceExecutionFence): MaintenanceOperationOutcome =
                error("db unavailable")
        }
        val executor = QueuedExecutor()

        processor(queue, operations, executor).requestDrain()
        executor.runNext()

        val outcome = queue.completed.single().second as MaintenanceOperationOutcome.Failed
        assertEquals("Maintenance handler crashed: db unavailable", outcome.summary)
    }

    @Test
    fun `boundary - a handler exception without its own message falls back to the exception's class name`() {
        val queue = FakeQueue(ArrayDeque(listOf(job(9, DatabaseMaintenanceType.RECALCULATE_TOTALS))))
        // check()/error() always require a message, so they cannot produce the null-message
        // exception this test needs; built as a value first (not `throw IllegalStateException()`
        // at the call site) so IllegalStateException stays the caught type without re-triggering
        // UseCheckOrError, which only matches a constructor call right at the throw site.
        val messageless: Throwable = IllegalStateException()
        val operations = object : MaintenanceOperations {
            override fun execute(type: DatabaseMaintenanceType, fence: MaintenanceExecutionFence): MaintenanceOperationOutcome =
                throw messageless
        }
        val executor = QueuedExecutor()

        processor(queue, operations, executor).requestDrain()
        executor.runNext()

        val outcome = queue.completed.single().second as MaintenanceOperationOutcome.Failed
        assertEquals("Maintenance handler crashed: IllegalStateException", outcome.summary)
    }

    @Test
    fun `boundary - a request that arrives while a drain is in flight schedules a follow-up after it finishes`() {
        val queue = FakeQueue(ArrayDeque(listOf(job(1, DatabaseMaintenanceType.RECALCULATE_TOTALS))))
        val executor = QueuedExecutor()
        lateinit var processor: TestProcessor
        val operations = object : MaintenanceOperations {
            override fun execute(type: DatabaseMaintenanceType, fence: MaintenanceExecutionFence): MaintenanceOperationOutcome {
                // A wakeup that arrives while this drain still owns the in-process guard must not
                // be lost -- the finally block re-checks drainRequested after releasing the guard.
                processor.requestDrain()
                return MaintenanceOperationOutcome.Succeeded("done")
            }
        }
        processor = processor(queue, operations, executor)

        processor.requestDrain()
        executor.runNext()

        assertEquals(1, executor.size)
    }

    @Test
    fun `reaching batch bound schedules another bounded drain`() {
        val queue = FakeQueue(
            ArrayDeque(
                listOf(
                    job(1, DatabaseMaintenanceType.RECALCULATE_TOTALS),
                    job(2, DatabaseMaintenanceType.RECALCULATE_TOTALS),
                ),
            ),
        )
        val executor = QueuedExecutor()
        val processor = processor(queue, succeedingOperations(), executor, batchSize = 1)

        processor.requestDrain()
        executor.runNext()
        assertEquals(listOf(1L), queue.completed.map { it.first.eventId })
        assertEquals(1, executor.size)
        executor.runNext()

        assertEquals(listOf(1L, 2L), queue.completed.map { it.first.eventId })
        assertEquals(1, executor.size) // exact-boundary follow-up; it will observe Empty
        executor.runNext()
        assertEquals(0, executor.size)
    }

    @Test
    fun `happy path - requestDrain schedules a batch that claims and completes a pending job`() {
        val queue = FakeQueue(ArrayDeque(listOf(job(1, DatabaseMaintenanceType.RECALCULATE_TOTALS))))
        val executor = QueuedExecutor()
        val processor = MaintenanceQueueProcessor(queue, succeedingOperations(), executor).also {
            it.requestContextFactory = { AutoCloseable {} }
        }

        processor.requestDrain()
        executor.runNext()

        assertEquals(listOf(1L), queue.completed.map { it.first.eventId })
        assertTrue(queue.completed.single().second is MaintenanceOperationOutcome.Succeeded)
    }

    @Test
    fun `boundary - constructing with a non-positive drain-batch-size fails fast`() {
        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            MaintenanceQueueProcessor(FakeQueue(), succeedingOperations(), QueuedExecutor(), batchSize = 0)
        }

        assertTrue(exception.message?.contains("drain-batch-size") == true, exception.message)
    }

    @Test
    fun `boundary - a rejected drain task is caught and the in-process guard still allows a later retry`() {
        val queue = FakeQueue()
        val executor = FlakyExecutor()
        val processor = MaintenanceQueueProcessor(queue, succeedingOperations(), executor).also {
            it.requestContextFactory = { AutoCloseable {} }
        }

        processor.requestDrain()
        assertEquals(1, executor.calls)
        assertEquals(0, executor.tasks.size, "the first scheduling attempt was rejected, no task queued")

        processor.requestDrain()
        assertEquals(2, executor.calls)
        assertEquals(1, executor.tasks.size, "the guard was released after the failure, so a later hint schedules again")
    }

    @Test
    fun `boundary - a crash acquiring the lease is caught, still releasing the in-process scheduling guard`() {
        val queue = ThrowingQueue()
        val executor = QueuedExecutor()
        val processor = MaintenanceQueueProcessor(queue, succeedingOperations(), executor).also {
            it.requestContextFactory = { AutoCloseable {} }
        }

        processor.requestDrain()
        executor.runNext()

        assertEquals(1, queue.leaseAttempts)
        assertEquals(0, executor.size, "the crashed batch must not reschedule itself immediately")

        processor.requestDrain()
        assertEquals(1, executor.size, "the in-process guard was released after the crash, a later hint schedules again")
    }

    @Test
    fun `boundary - recovering interrupted claims logs both the versioned and historical counts`() {
        val queue = FakeQueue(recovery = MaintenanceDrainLease.RecoveryResult(terminalizedVersionedClaims = 2, terminalizedHistoricalClaims = 1))
        val executor = QueuedExecutor()
        val processor = MaintenanceQueueProcessor(queue, succeedingOperations(), executor).also {
            it.requestContextFactory = { AutoCloseable {} }
        }

        processor.requestDrain()
        executor.runNext()

        assertEquals(1, queue.recoveryCalls)
    }

    @Test
    fun `boundary - a blocked claim stops the drain without throwing and without completing anything`() {
        val queue = FakeQueue(blockedDetails = "blocked by an unresolved earlier recalculation failure")
        val executor = QueuedExecutor()
        val processor = MaintenanceQueueProcessor(queue, succeedingOperations(), executor).also {
            it.requestContextFactory = { AutoCloseable {} }
        }

        processor.requestDrain()
        executor.runNext()

        assertTrue(queue.completed.isEmpty(), "a blocked claim must complete nothing")
        assertEquals(0, executor.size, "a blocked drain must not immediately reschedule itself")
    }

    @Test
    fun `boundary - a fenced execution exception from the handler is rethrown unchanged, not swallowed as a failure`() {
        val queue = FakeQueue(ArrayDeque(listOf(job(5, DatabaseMaintenanceType.RECALCULATE_TOTALS))))
        val fencedOperations = object : MaintenanceOperations {
            override fun execute(type: DatabaseMaintenanceType, fence: MaintenanceExecutionFence): MaintenanceOperationOutcome =
                throw MaintenanceExecutionFencedException("claim token no longer valid")
        }
        val executor = QueuedExecutor()
        val processor = MaintenanceQueueProcessor(queue, fencedOperations, executor).also {
            it.requestContextFactory = { AutoCloseable {} }
        }

        processor.requestDrain()
        // The fenced exception propagates out of the lease block unchanged instead of being
        // downgraded to a Failed outcome; runScheduledDrain's own outer catch(Exception) still
        // absorbs it, so running the queued task itself must not throw out to the caller.
        executor.runNext()

        assertTrue(queue.completed.isEmpty(), "a fenced claim must never be completed as if it succeeded or failed normally")
    }

    @Test
    fun `busy cross-process lease leaves durable work to owner or recovery tick`() {
        val executor = QueuedExecutor()
        val queue = FakeQueue(leaseBusy = true)
        val processor = processor(queue, succeedingOperations(), executor)

        processor.requestDrain()
        executor.runNext()

        assertEquals(1, queue.leaseAttempts)
        assertTrue(queue.completed.isEmpty())
        assertEquals(0, executor.size)
    }

    @Test
    fun `boundary - the default requestContextFactory activates and terminates a real Arc request context`() {
        val container = mock<ArcContainer>()
        val context = mock<ManagedContext>()
        whenever(container.requestContext()).thenReturn(context)

        mockStatic(Arc::class.java).use { staticArc ->
            staticArc.`when`<ArcContainer> { Arc.container() }.thenReturn(container)

            val processor = MaintenanceQueueProcessor(FakeQueue(), succeedingOperations(), QueuedExecutor())

            processor.requestContextFactory().close()
        }

        verify(context).activate()
        verify(context).terminate()
    }

    private fun processor(
        queue: FakeQueue,
        operations: MaintenanceOperations,
        executor: QueuedExecutor,
        batchSize: Int = 10,
        schedulerEnabled: Boolean = true,
    ): TestProcessor = TestProcessor(
        MaintenanceQueueProcessor(queue, operations, executor, batchSize, schedulerEnabled).also {
            it.requestContextFactory = { AutoCloseable {} }
        },
        executor,
    )

    private fun succeedingOperations() = object : MaintenanceOperations {
        override fun execute(type: DatabaseMaintenanceType, fence: MaintenanceExecutionFence): MaintenanceOperationOutcome {
            fence.verifyInCurrentTransaction()
            return MaintenanceOperationOutcome.Succeeded("done")
        }
    }

    private fun job(id: Long, type: DatabaseMaintenanceType) =
        MaintenanceDrainLease.ClaimedMaintenanceJob(id, type, claimToken = id * 10)

    private data class TestProcessor(
        val delegate: MaintenanceQueueProcessor,
        val queuedExecutor: QueuedExecutor,
    ) {
        fun requestDrain() = delegate.requestDrain()
        fun pollPendingMaintenanceJobs() = delegate.pollPendingMaintenanceJobs()
        fun onStart() = delegate.onStart()
        fun executor() = queuedExecutor
    }

    private class QueuedExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        val size: Int get() = tasks.size
        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }
        fun runNext() = tasks.removeFirst().run()
    }

    /** Rejects the first scheduling attempt, then accepts every later one -- proves
     *  scheduleDrainIfNeeded's catch(RejectedExecutionException) branch releases the in-process
     *  guard. RejectedExecutionException, not a bare RuntimeException: the real contract
     *  [java.util.concurrent.Executor.execute] declares for a task it cannot accept. */
    private class FlakyExecutor : Executor {
        var calls = 0
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            calls++
            if (calls == 1) throw java.util.concurrent.RejectedExecutionException("executor rejected the drain task")
            tasks.addLast(command)
        }
    }

    /** Crashes acquiring the lease itself (not a Busy result) -- proves runScheduledDrain's own
     *  catch(Exception) branch releases the in-process guard instead of leaving it stuck. */
    private class ThrowingQueue : MaintenanceQueuePort {
        var leaseAttempts = 0
        override fun <T> withExclusiveDrainLease(block: MaintenanceDrainLease.() -> T): MaintenanceQueuePort.LeaseAttempt<T> {
            leaseAttempts++
            throw RuntimeException("advisory lock connection lost")
        }
    }

    private class FakeQueue(
        private val claims: ArrayDeque<MaintenanceDrainLease.ClaimedMaintenanceJob> = ArrayDeque(),
        private val leaseBusy: Boolean = false,
        private val recovery: MaintenanceDrainLease.RecoveryResult = MaintenanceDrainLease.RecoveryResult(0, 0),
        private val blockedDetails: String? = null,
    ) : MaintenanceQueuePort, MaintenanceDrainLease {
        var leaseAttempts = 0
        var recoveryCalls = 0
        val completed = mutableListOf<Pair<MaintenanceDrainLease.ClaimedMaintenanceJob, MaintenanceOperationOutcome>>()
        val remainingClaims: Int get() = claims.size

        override fun <T> withExclusiveDrainLease(block: MaintenanceDrainLease.() -> T): MaintenanceQueuePort.LeaseAttempt<T> {
            leaseAttempts++
            if (leaseBusy) return MaintenanceQueuePort.LeaseAttempt.Busy
            return MaintenanceQueuePort.LeaseAttempt.Acquired(block(this))
        }

        override fun verifyOwnership() = Unit
        override fun verifyInCurrentTransaction() = Unit

        override fun recoverInterruptedClaims(): MaintenanceDrainLease.RecoveryResult {
            recoveryCalls++
            return recovery
        }

        override fun claimNext(): MaintenanceDrainLease.ClaimResult = when {
            blockedDetails != null -> MaintenanceDrainLease.ClaimResult.Blocked(blockedDetails)
            claims.isEmpty() -> MaintenanceDrainLease.ClaimResult.Empty
            else -> MaintenanceDrainLease.ClaimResult.Claimed(claims.removeFirst())
        }

        override fun complete(
            claim: MaintenanceDrainLease.ClaimedMaintenanceJob,
            outcome: MaintenanceOperationOutcome,
        ) {
            completed += claim to outcome
        }
    }
}
