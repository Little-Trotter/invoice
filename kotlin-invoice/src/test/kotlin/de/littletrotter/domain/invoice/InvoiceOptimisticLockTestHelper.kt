package de.littletrotter.domain.invoice

import de.littletrotter.shared.Money
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * CDI helper bean for [InvoiceOptimisticLockingTest].
 *
 * Must be a top-level class — non-static inner classes cannot be CDI beans because
 * they lack a zero-arg constructor visible to the CDI container.
 */
@ApplicationScoped
class InvoiceOptimisticLockTestHelper {

    @Inject
    lateinit var em: EntityManager

    /** Persists a minimal draft [Invoice] and returns its database id. */
    @Transactional
    fun createDraftInvoice(): Long {
        val invoice = Invoice().apply {
            // InvoiceNumber.UNASSIGNED ("") is the correct state for a draft before numbering
            invoiceNumber = InvoiceNumber.UNASSIGNED
            invoiceDate = LocalDate.now()
            addItem(InvoiceItem().apply {
                positionId = "1"
                description = "Optimistic Lock Test Position"
                quantity = BigDecimal.ONE
                unitPriceNet = Money(BigDecimal("100.00"))
            })
        }
        em.persist(invoice)
        em.flush()
        return invoice.id!!
    }

    /**
     * Loads [Invoice] by [invoiceId], signals [loadedLatch], waits on [startSignal],
     * then transitions to [target] and merges — simulating one side of a concurrent update.
     *
     * [Transactional.TxType.REQUIRES_NEW] ensures that each call from a different thread
     * starts a completely independent JTA transaction (no sharing with the calling context).
     * The transaction commits when this method returns, triggering Hibernate's version check.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    fun loadAndTransition(
        invoiceId: Long,
        target: InvoiceStatus,
        loadedLatch: CountDownLatch,
        startSignal: CountDownLatch,
    ) {
        val inv = em.find(Invoice::class.java, invoiceId)
        // Signal that this transaction has loaded the entity (version is now fixed in this tx)
        loadedLatch.countDown()
        // Wait until both transactions have loaded before either commits
        startSignal.await(10, TimeUnit.SECONDS)
        inv.transitionTo(target)
        em.merge(inv)
        // JTA transaction commits here; Hibernate version-WHERE check runs on flush
    }
}
