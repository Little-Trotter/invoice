package de.littletrotter.application.shared

import de.littletrotter.application.invoice.Optional
import de.littletrotter.domain.event.DomainEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Event
import jakarta.inject.Inject

/**
 * The single place where application services hand a [DomainEvent] to asynchronous CDI observers
 * (UI push handler, overview cache evictor, message centre, document reaction handlers).
 *
 * Observers re-read state from the database. Firing from inside an open transaction lets them run
 * against the pre-commit row (stale status chip) or announce a change that is later rolled back.
 * With an active JTA transaction the event is therefore deferred to an interposed
 * [jakarta.transaction.Synchronization] and fired only once the transaction reports
 * [jakarta.transaction.Status.STATUS_COMMITTED] in `afterCompletion`; on rollback it is dropped,
 * because the change it would announce never happened. Without a registry or without an active
 * transaction (unit tests, non-transactional callers) it is fired immediately.
 */
@ApplicationScoped
class DomainEventDispatcher @Inject constructor(
    private val domainEventBus: Event<DomainEvent>,
    @param:Optional
    private val transactionSynchronizationRegistry: jakarta.transaction.TransactionSynchronizationRegistry?,
) {
    fun publishAfterCommit(event: DomainEvent) {
        val action = { domainEventBus.fireAsync(event) }
        val tsr = transactionSynchronizationRegistry
        if (tsr != null && tsr.transactionStatus == jakarta.transaction.Status.STATUS_ACTIVE) {
            tsr.registerInterposedSynchronization(object : jakarta.transaction.Synchronization {
                override fun beforeCompletion() {
                    // Nothing to do before commit: the action only runs from afterCompletion,
                    // once STATUS_COMMITTED is known.
                }
                override fun afterCompletion(status: Int) {
                    if (status == jakarta.transaction.Status.STATUS_COMMITTED) {
                        action()
                    }
                }
            })
        } else {
            action()
        }
    }
}
