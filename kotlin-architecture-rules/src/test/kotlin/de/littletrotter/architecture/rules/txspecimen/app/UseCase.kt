package de.littletrotter.architecture.rules.txspecimen.app

import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.transaction.Transactional
import jakarta.transaction.TxType

/** The application layer owns the boundary, so none of the placement rules applies here. */
class UseCase {
    @Transactional(TxType.REQUIRES_NEW)
    fun run(): Int = QuarkusTransaction.requiringNew()
}
