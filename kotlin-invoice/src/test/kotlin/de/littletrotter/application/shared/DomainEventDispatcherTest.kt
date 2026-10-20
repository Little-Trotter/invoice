package de.littletrotter.application.shared

import de.littletrotter.domain.event.DomainEvent
import de.littletrotter.domain.event.GenerationJobCreated
import de.littletrotter.domain.invoice.DocumentJobLane
import jakarta.enterprise.event.Event
import jakarta.transaction.Status
import jakarta.transaction.Synchronization
import jakarta.transaction.TransactionSynchronizationRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/** Unit tests for the extracted publishAfterCommit behaviour (moved from
 *  GenerationJobServiceTest's own coverage of the same logic). */
class DomainEventDispatcherTest {

    private class FakeRegistry(var status: Int) : TransactionSynchronizationRegistry {
        var registered: Synchronization? = null
        override fun getTransactionKey(): Any? = null
        override fun putResource(key: Any?, value: Any?) {}
        override fun getResource(key: Any?): Any? = null
        override fun registerInterposedSynchronization(sync: Synchronization) {
            registered = sync
        }
        override fun getTransactionStatus(): Int = status
        override fun setRollbackOnly() {}
        override fun getRollbackOnly(): Boolean = false
    }

    private val event: DomainEvent = GenerationJobCreated(1L, DocumentJobLane.BULK)

    @Test
    fun `no registry fires immediately`() {
        val bus: Event<DomainEvent> = mock()
        val dispatcher = DomainEventDispatcher(bus, null)

        dispatcher.publishAfterCommit(event)

        verify(bus).fireAsync(event)
    }

    @Test
    fun `registry present but no active transaction fires immediately`() {
        val bus: Event<DomainEvent> = mock()
        val registry = FakeRegistry(Status.STATUS_NO_TRANSACTION)
        val dispatcher = DomainEventDispatcher(bus, registry)

        dispatcher.publishAfterCommit(event)

        verify(bus).fireAsync(event)
        assertEquals(null, registry.registered)
    }

    @Test
    fun `active transaction defers and fires only after commit`() {
        val bus: Event<DomainEvent> = mock()
        val registry = FakeRegistry(Status.STATUS_ACTIVE)
        val dispatcher = DomainEventDispatcher(bus, registry)

        dispatcher.publishAfterCommit(event)

        verify(bus, never()).fireAsync(event)
        val sync = requireNotNull(registry.registered)
        sync.beforeCompletion()
        sync.afterCompletion(Status.STATUS_COMMITTED)
        verify(bus).fireAsync(event)
    }

    @Test
    fun `active transaction that rolls back never fires`() {
        val bus: Event<DomainEvent> = mock()
        val registry = FakeRegistry(Status.STATUS_ACTIVE)
        val dispatcher = DomainEventDispatcher(bus, registry)

        dispatcher.publishAfterCommit(event)
        requireNotNull(registry.registered).afterCompletion(Status.STATUS_ROLLEDBACK)

        verify(bus, never()).fireAsync(event)
    }
}
