package de.littletrotter.arch.specimen.transaction.application

import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/** Positive: the use case owns the transaction, including an independent one. */
@ApplicationScoped
open class ApplicationTransactionSpecimen {
    @Transactional
    open fun change() = Unit

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    open fun changeIndependently() = Unit
}
