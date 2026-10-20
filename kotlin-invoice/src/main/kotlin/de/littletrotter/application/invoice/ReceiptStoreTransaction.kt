package de.littletrotter.application.invoice

import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped

/**
 * Runs a block in one short, independent transaction.
 *
 * A lambda handed to the transaction API cannot be skipped by container interception, unlike an
 * `internal` method whose mangled name is not intercepted when called on `this`: the checksum lock
 * of the receipt upload only serialises against a delete while a transaction is really active.
 */
interface ReceiptStoreTransaction {
    fun <T> inNewTransaction(block: () -> T): T
}

/** Production implementation: a new JTA transaction, committed before the call returns. */
@ApplicationScoped
class JtaReceiptStoreTransaction : ReceiptStoreTransaction {
    override fun <T> inNewTransaction(block: () -> T): T = QuarkusTransaction.requiringNew().call(block)
}
