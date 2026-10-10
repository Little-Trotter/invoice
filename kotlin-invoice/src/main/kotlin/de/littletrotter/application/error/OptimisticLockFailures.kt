package de.littletrotter.application.error

/**
 * True when [this] or any cause in its chain is an optimistic-lock failure. A version conflict
 * detected at commit time reaches the caller wrapped by Narayana (`RollbackException` /
 * `ARJUNA016053: Could not commit transaction.`), not as a bare
 * [jakarta.persistence.OptimisticLockException] — callers mapping conflicts to a readable
 * message must check the chain, not only the top-level type (ADR-028).
 */
fun Throwable.isOptimisticLockFailure(): Boolean =
    generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH).any {
        it is jakarta.persistence.OptimisticLockException || it is org.hibernate.StaleObjectStateException
    }

private const val MAX_CAUSE_DEPTH = 32
