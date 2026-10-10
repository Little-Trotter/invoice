package de.littletrotter.application.invoice

import de.littletrotter.domain.event.DomainEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Event
import jakarta.inject.Inject

/**
 * Extracted from `GenerationJobService.fireAsyncAfterCommit` (used by
 * [GenerationJobResultHandler], [GenerationJobLlmResultHandler] and [GenerationJobRecoveryService]).
 *
 * fix-einvoice-status-stale-after-completion (a regression) — every `@Transactional` method
 * that fires a [DomainEvent] as a consequence of a GenerationJob state change must call this
 * instead of `domainEventBus.fireAsync(...)` directly. Async CDI observers (the UI push handler,
 * `DocumentGeneratedReactionHandler`, `MessageCenterEventHandler`) do not wait for the firing
 * method's commit before they re-read job/invoice state from the DB — firing from inside an open
 * transaction lets an observer run and read the pre-commit row, which is exactly the "chip stays
 * stale" bug this fix addresses.
 *
 * With an active JTA transaction the event is deferred to an interposed
 * [jakarta.transaction.Synchronization] and only actually fired once that transaction reports
 * [jakarta.transaction.Status.STATUS_COMMITTED] in `afterCompletion`; on rollback it is simply
 * dropped, which is correct — the persisted transition it would announce never happened.
 * Without a registry, or without an active transaction (every existing unit test, and any
 * future non-transactional caller), this fires immediately.
 */
@ApplicationScoped
class GenerationJobEventDispatcher @Inject constructor(
    private val domainEventBus: Event<DomainEvent>,
    @param:Optional
    private val transactionSynchronizationRegistry: jakarta.transaction.TransactionSynchronizationRegistry?,
) {
    fun fireAsyncAfterCommit(event: DomainEvent) {
        val tsr = transactionSynchronizationRegistry
        if (tsr != null && tsr.transactionStatus == jakarta.transaction.Status.STATUS_ACTIVE) {
            tsr.registerInterposedSynchronization(object : jakarta.transaction.Synchronization {
                override fun beforeCompletion() {
                    // Nothing to do before commit: the event only fires from afterCompletion,
                    // once STATUS_COMMITTED is known.
                }
                override fun afterCompletion(status: Int) {
                    if (status == jakarta.transaction.Status.STATUS_COMMITTED) {
                        domainEventBus.fireAsync(event)
                    }
                }
            })
        } else {
            domainEventBus.fireAsync(event)
        }
    }
}
