package de.littletrotter.application.system

import de.littletrotter.application.invoice.DelegatingManagedExecutor
import jakarta.enterprise.event.Observes
import jakarta.enterprise.event.TransactionPhase
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.Executor
import java.util.concurrent.CopyOnWriteArrayList

class MaintenanceJobQueuedObserverTest {
    @Test
    fun `queued event is published through deferred executor`() {
        val publisher = mock<MaintenanceWakeupPublisher>()
        val tasks = CopyOnWriteArrayList<Runnable>()
        val observer = MaintenanceJobQueuedObserver(publisher, DelegatingManagedExecutor(Executor { tasks += it }))

        observer.onMaintenanceJobQueued(MaintenanceJobQueued(17L))
        org.mockito.kotlin.verifyNoInteractions(publisher)
        assertEquals(1, tasks.size)
        tasks.single().run()

        verify(publisher).publish(17L)
    }

    @Test
    fun `publisher failure is contained in deferred task`() {
        val publisher = mock<MaintenanceWakeupPublisher>()
        whenever(publisher.publish(17L)).thenThrow(IllegalStateException("broker down"))
        val observer = MaintenanceJobQueuedObserver(publisher, DelegatingManagedExecutor(Executor { it.run() }))

        assertDoesNotThrow { observer.onMaintenanceJobQueued(MaintenanceJobQueued(17L)) }
    }

    @Test
    fun `executor rejection is contained`() {
        val observer = MaintenanceJobQueuedObserver(
            mock(),
            DelegatingManagedExecutor(Executor { throw java.util.concurrent.RejectedExecutionException("closed") }),
        )

        assertDoesNotThrow { observer.onMaintenanceJobQueued(MaintenanceJobQueued(17L)) }
    }

    @Test
    fun `observer uses AFTER_SUCCESS`() {
        val method = MaintenanceJobQueuedObserver::class.java.getMethod(
            "onMaintenanceJobQueued", MaintenanceJobQueued::class.java,
        )
        val observes = method.parameters.single().getAnnotation(Observes::class.java)

        assertEquals(TransactionPhase.AFTER_SUCCESS, observes.during)
    }

    @Test
    fun `request id must be positive`() {
        assertThrows<IllegalArgumentException> { MaintenanceJobQueued(0L) }
    }

    @Test
    fun `an unrelated runtime exception from the publisher is not silently eaten`() {
        val publisher = mock<MaintenanceWakeupPublisher>()
        whenever(publisher.publish(17L)).thenThrow(IllegalArgumentException("programming error"))
        val observer = MaintenanceJobQueuedObserver(publisher, DelegatingManagedExecutor(Executor { it.run() }))

        assertThrows<IllegalArgumentException> { observer.onMaintenanceJobQueued(MaintenanceJobQueued(17L)) }
    }
}
