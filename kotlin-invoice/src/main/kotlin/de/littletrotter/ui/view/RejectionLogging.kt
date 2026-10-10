package de.littletrotter.ui.view

import com.vaadin.flow.data.binder.ValidationException
import de.littletrotter.application.error.isOptimisticLockFailure
import de.littletrotter.domain.exception.InvoiceDomainException
import de.littletrotter.domain.exception.InvoiceImmutableException
import de.littletrotter.domain.exception.InvoiceStatusTransitionException
import org.jboss.logging.Logger

/**
 * An expected domain rejection ([InvoiceDomainException], the Vaadin binder's
 * [ValidationException], a stale-edit [jakarta.persistence.OptimisticLockException], or a
 * bean-validation [jakarta.validation.ConstraintViolationException] — the `@Size` guards
 * throw exactly this at flush time when a pre-check was skipped) is logged as one INFO line
 * without a stack trace ([de.littletrotter.application.error.ApplicationConflictException]
 * and Hibernate's constraint violation, whose message the UI shows, log at WARN);
 * anything else is still an ERROR with the full trace, so unexpected
 * failures stay discoverable in the log.
 */
internal fun logRejection(log: Logger, action: String, ex: Exception) {
    // Commit-time lock failures arrive wrapped (rollback/transaction exceptions), so the cause
    // chain is checked before the type match.
    val line = "$action: ${ex.javaClass.simpleName}: ${ex.message}"
    if (ex.isOptimisticLockFailure()) {
        log.info(line)
        return
    }
    when (ex) {
        is InvoiceDomainException,
        is ValidationException,
        is jakarta.validation.ConstraintViolationException,
        is de.littletrotter.domain.reference.NumberCollisionException,
        -> log.info(line)
        // A stale-state conflict or a mapped DB constraint: the UI shows a defined message.
        is de.littletrotter.application.error.ApplicationConflictException,
        is org.hibernate.exception.ConstraintViolationException,
        -> log.warn(line)
        else -> log.error(action, ex)
    }
}

/**
 * A business-rule rejection the UI answers with a defined message ([InvoiceDomainException], e.g.
 * `NO_ITEMS` or an invalid number format) is one WARN line without a stack trace; anything else
 * stays an ERROR with the full trace.
 */
internal fun logDomainRejection(log: Logger, message: String, ex: Exception) {
    if (ex is InvoiceDomainException) log.warn("$message: ${ex.javaClass.simpleName}: ${ex.message}")
    else log.error(message, ex)
}

/**
 * True when [ex] means the invoice changed under the user (another session saved, finalized or
 * cancelled it): the editor's copy is stale, so the UI reloads the committed state instead of
 * leaving the user on data that can no longer be saved.
 */
internal fun isConcurrencyConflict(ex: Exception): Boolean =
    ex.isOptimisticLockFailure() || ex is InvoiceImmutableException || ex is InvoiceStatusTransitionException
