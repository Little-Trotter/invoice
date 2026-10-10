package de.littletrotter.application.invoice

/**
 * Fix U (generation-job-deadlock-demo-burst) — walks the full [Throwable.cause] chain for
 * Postgres' own deadlock SQLSTATE (`40P01`), the same cause-chain-walking idiom
 * [DocumentJobPublisher]'s own `isOptimisticLockFailure` already uses for
 * `OptimisticLockException`/`StaleObjectStateException`. Checked against the plain JDK
 * [java.sql.SQLException.getSQLState] rather than `org.postgresql.util.PSQLException` directly, so
 * this has no compile-time dependency on the JDBC driver package and still recognizes the same
 * failure however deep a JPA/Hibernate/Narayana wrapper buries it.
 *
 * A deadlock caught here is, by construction, already self-healing (the caller reverts the job's
 * claim, or leaves it to the next recovery-scan tick) — so a caller that still hits this after
 * de.littletrotter.infrastructure.persistence.PanacheGenerationJobRepository.resetStaleDelegated's
 * id-ordered `SKIP LOCKED` fix should log it at `WARN` with the job id, not `ERROR` with a full
 * JDBC stack trace: a rare, already-recovered race, not an operational fault.
 */
private const val POSTGRES_DEADLOCK_SQLSTATE = "40P01"

internal fun isPostgresDeadlock(ex: Throwable): Boolean {
    var cause: Throwable? = ex
    while (cause != null) {
        if (cause is java.sql.SQLException && cause.sqlState == POSTGRES_DEADLOCK_SQLSTATE) {
            return true
        }
        cause = cause.cause
    }
    return false
}
