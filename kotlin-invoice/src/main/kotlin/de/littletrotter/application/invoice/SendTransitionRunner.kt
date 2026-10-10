package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.InvoiceStatus
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.narayana.jta.QuarkusTransactionException
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.OptimisticLockException
import jakarta.persistence.PersistenceException

/**
 * Runs the `-> SENT` transition of a completed SEND job in its own short transaction.
 *
 * The caller's transaction has usually read the invoice already (version V). If another
 * transaction commits V+1 meanwhile, the row-locked re-read in the same persistence context fails
 * with a stale-version error and would leave the invoice stuck in review. A fresh transaction
 * starts with an empty persistence context, so the lock is the first read of the invoice, and a
 * single retry covers a bump that lands between that read and the commit.
 */
@ApplicationScoped
class SendTransitionRunner @Inject constructor(
    private val invoiceLifecycle: InvoiceLifecycleService,
) {
    fun transitionToSent(invoiceId: Long) {
        try {
            inNewTransaction(invoiceId)
        } catch (ex: PersistenceException) {
            retryIfStale(invoiceId, ex)
        } catch (ex: QuarkusTransactionException) {
            retryIfStale(invoiceId, ex)
        }
    }

    private fun retryIfStale(invoiceId: Long, failure: RuntimeException) {
        if (!failure.isStaleVersionConflict()) throw failure
        inNewTransaction(invoiceId)
    }

    /** Overridable seam: unit tests run the block without a JTA transaction manager. */
    internal fun inNewTransaction(invoiceId: Long) {
        QuarkusTransaction.requiringNew().run { invoiceLifecycle.transition(invoiceId, InvoiceStatus.SENT) }
    }

    private fun Throwable.isStaleVersionConflict(): Boolean =
        generateSequence(this) { it.cause?.takeIf { cause -> cause !== it } }
            .take(MAX_CAUSE_DEPTH)
            .any { it is OptimisticLockException || it.javaClass.simpleName in STALE_STATE_NAMES }

    private companion object {
        const val MAX_CAUSE_DEPTH = 10
        val STALE_STATE_NAMES = setOf("StaleObjectStateException", "StaleStateException")
    }
}
