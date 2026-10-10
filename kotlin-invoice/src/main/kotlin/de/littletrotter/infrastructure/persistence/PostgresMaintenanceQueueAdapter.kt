package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.system.MaintenanceDrainLease
import de.littletrotter.application.system.MaintenanceDrainLockLostException
import de.littletrotter.application.system.MaintenanceExecutionFencedException
import de.littletrotter.application.system.MaintenanceOperationOutcome
import de.littletrotter.application.system.MaintenanceQueuePort
import de.littletrotter.domain.system.DatabaseMaintenanceStatus
import de.littletrotter.domain.system.DatabaseMaintenanceType
import de.littletrotter.shared.time.TimeSource
import io.agroal.api.AgroalDataSource
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import java.sql.Connection
import java.sql.SQLException

/** PostgreSQL queue adapter backed by short JTA transactions and a session advisory lock.
 *  [dataSource]/[em] are already ordinary constructor-injected, immutable `val` parameters — the
 *  correct shape this inspection asks for; both beans (the default [AgroalDataSource], the
 *  default [EntityManager]) are Quarkus-generated producers with no source-visible `@Produces`
 *  method, which is what the inspection cannot resolve, not an injection design issue here. */
@ApplicationScoped
class PostgresMaintenanceQueueAdapter @Inject constructor(
    private val dataSource: AgroalDataSource,
    private val em: EntityManager,
    private val time: TimeSource,
) : MaintenanceQueuePort {

    override fun <T> withExclusiveDrainLease(block: MaintenanceDrainLease.() -> T): MaintenanceQueuePort.LeaseAttempt<T> {
        dataSource.connection.use { connection ->
            if (!tryAcquire(connection)) return MaintenanceQueuePort.LeaseAttempt.Busy

            // block() runs caller-supplied business code (the whole point of this port), so its
            // failure surface is open-ended and cannot be narrowed to concrete exception types —
            // runCatching observes it (any Throwable, exactly like the try/catch it replaces)
            // without an explicit catch clause, then releaseLease decides how the lock release
            // outcome and the block outcome combine before the original failure (now possibly
            // carrying a suppressed release failure) is rethrown unchanged by getOrThrow().
            val outcome = runCatching { JdbcMaintenanceDrainLease(connection, em, time).block() }
            releaseLease(connection, outcome.exceptionOrNull())
            return MaintenanceQueuePort.LeaseAttempt.Acquired(outcome.getOrThrow())
        }
    }

    /**
     * Releases the advisory lock after block in [withExclusiveDrainLease] ran, whether it
     * succeeded or [blockFailure] captures why it did not. A failed unlock must never return a
     * possibly lock-owning physical session to the pool: JDBC abort invalidates the connection,
     * and PostgreSQL releases every session lock when that backend closes.
     */
    private fun releaseLease(connection: Connection, blockFailure: Throwable?) {
        val releaseFailure = runCatching {
            if (!release(connection)) {
                throw MaintenanceDrainLockLostException(
                    "PostgreSQL maintenance drain advisory lock was no longer owned at release",
                )
            }
        }.exceptionOrNull() ?: return

        runCatching { connection.abort { command -> command.run() } }
            .exceptionOrNull()
            ?.let { abortFailure -> releaseFailure.addSuppressed(abortFailure) }

        if (blockFailure != null) {
            blockFailure.addSuppressed(releaseFailure)
        } else {
            throw releaseFailure
        }
    }

    private fun tryAcquire(connection: Connection): Boolean =
        connection.prepareStatement("SELECT pg_try_advisory_lock(?, ?)").use { statement ->
            statement.setInt(1, LOCK_NAMESPACE)
            statement.setInt(2, LOCK_ID)
            statement.executeQuery().use { result -> result.next() && result.getBoolean(1) }
        }

    private fun release(connection: Connection): Boolean =
        connection.prepareStatement("SELECT pg_advisory_unlock(?, ?)").use { statement ->
            statement.setInt(1, LOCK_NAMESPACE)
            statement.setInt(2, LOCK_ID)
            statement.executeQuery().use { result -> result.next() && result.getBoolean(1) }
        }

    private class JdbcMaintenanceDrainLease(
        private val lockConnection: Connection,
        private val em: EntityManager,
        private val time: TimeSource,
    ) : MaintenanceDrainLease {

        private companion object {
            private const val PARAM_EVENT_ID = "eventId"
            private const val PARAM_EXECUTION_VERSION = "executionVersion"
            private const val PARAM_RECALCULATE = "recalculate"
            private const val PARAM_RUNNING = "running"
            private const val PARAM_UNKNOWN = "unknown"
        }

        override fun verifyOwnership() {
            try {
                val owned = lockConnection.prepareStatement(
                    """
                    SELECT EXISTS (
                        SELECT 1 FROM pg_locks
                        WHERE locktype = 'advisory'
                          AND pid = pg_backend_pid()
                          AND classid = ?::bigint
                          AND objid = ?::bigint
                          AND objsubid = 2
                          AND granted
                    )
                    """.trimIndent(),
                ).use { statement ->
                    statement.setInt(1, LOCK_NAMESPACE)
                    statement.setInt(2, LOCK_ID)
                    statement.executeQuery().use { result -> result.next() && result.getBoolean(1) }
                }
                if (!owned) {
                    throw MaintenanceDrainLockLostException(
                        "PostgreSQL maintenance drain advisory lock is no longer owned",
                    )
                }
            } catch (ex: MaintenanceDrainLockLostException) {
                throw ex
            } catch (ex: SQLException) {
                throw MaintenanceDrainLockLostException(
                    "Could not verify PostgreSQL maintenance drain advisory lock ownership",
                    ex,
                )
            }
        }

        override fun recoverInterruptedClaims(): MaintenanceDrainLease.RecoveryResult {
            verifyOwnership()
            return QuarkusTransaction.requiringNew().call {
                // Even a versioned claim may have committed some per-invoice effects before its
                // process died. Automatic replay would be ambiguous. Terminalize it and require
                // an explicit operator requeue; UNKNOWN recalculation is a durable generation
                // barrier until a later recalculation succeeds.
                val versioned = em.createNativeQuery(
                    """
                    UPDATE database_maintenance_event e
                       SET status = :unknown,
                           details = CONCAT(
                               'Interrupted versioned claim has unknown partial effects and was not replayed. ',
                               COALESCE(e.details, '')
                           )
                      FROM maintenance_queue_execution x
                     WHERE x.event_id = e.id
                       AND e.status = :running
                    """.trimIndent(),
                )
                    .setParameter(PARAM_UNKNOWN, DatabaseMaintenanceStatus.UNKNOWN.ordinal)
                    .setParameter(PARAM_RUNNING, DatabaseMaintenanceStatus.RUNNING.ordinal)
                    .executeUpdate()

                em.createNativeQuery(
                    """
                    DELETE FROM maintenance_queue_execution x
                     WHERE EXISTS (
                           SELECT 1 FROM database_maintenance_event e
                            WHERE e.id = x.event_id AND e.status <> :running
                       )
                    """.trimIndent(),
                )
                    .setParameter(PARAM_RUNNING, DatabaseMaintenanceStatus.RUNNING.ordinal)
                    .executeUpdate()

                // Rows created before the versioned execution ledger have unknown prior effects.
                // Replaying them could duplicate external work, so terminalize them fail-closed.
                val historical = em.createNativeQuery(
                    """
                    UPDATE database_maintenance_event e
                       SET status = :unknown,
                           details = CONCAT(
                               'Historical RUNNING request has unknown effects and was not replayed. ',
                               COALESCE(e.details, '')
                           )
                     WHERE e.status = :running
                       AND e.type IN (:recalculate, :queueIssued)
                       AND NOT EXISTS (
                           SELECT 1 FROM maintenance_queue_execution x WHERE x.event_id = e.id
                       )
                    """.trimIndent(),
                )
                    .setParameter(PARAM_UNKNOWN, DatabaseMaintenanceStatus.UNKNOWN.ordinal)
                    .setParameter(PARAM_RUNNING, DatabaseMaintenanceStatus.RUNNING.ordinal)
                    .setParameter(PARAM_RECALCULATE, DatabaseMaintenanceType.RECALCULATE_TOTALS.ordinal)
                    .setParameter("queueIssued", DatabaseMaintenanceType.QUEUE_ISSUED_GENERATION_JOBS.ordinal)
                    .executeUpdate()

                MaintenanceDrainLease.RecoveryResult(versioned, historical)
            }
        }

        override fun verifyInCurrentTransaction() {
            val current = currentClaim
                ?: throw MaintenanceExecutionFencedException("No maintenance claim is active")
            val rows = em.createNativeQuery(
                """
                SELECT claim_token FROM maintenance_queue_execution
                 WHERE event_id = :eventId AND execution_version = :executionVersion
                 FOR SHARE
                """.trimIndent(),
            )
                .setParameter(PARAM_EVENT_ID, current.eventId)
                .setParameter(PARAM_EXECUTION_VERSION, EXECUTION_VERSION)
                .resultList
            val actualToken = (rows.firstOrNull() as? Number)?.toLong()
            if (actualToken != current.claimToken) {
                throw MaintenanceExecutionFencedException(
                    "Maintenance claim ${current.eventId} was fenced by another drain owner",
                )
            }
        }

        override fun claimNext(): MaintenanceDrainLease.ClaimResult {
            verifyOwnership()
            return QuarkusTransaction.requiringNew().call {
                val oldest = findOldestPending() ?: return@call MaintenanceDrainLease.ClaimResult.Empty
                val candidate = if (
                    oldest.type == DatabaseMaintenanceType.QUEUE_ISSUED_GENERATION_JOBS &&
                    hasUnresolvedRecalculationFailureBefore(oldest)
                ) {
                    // A newly queued recalculation is allowed to pass the blocked generation row:
                    // its success is the explicit operator recovery that clears the durable barrier.
                    findOldestPendingRecalculation()
                        ?: return@call MaintenanceDrainLease.ClaimResult.Blocked(
                            "Issued-generation maintenance is blocked by an unresolved earlier recalculation failure",
                        )
                } else {
                    oldest
                }

                val updated = em.createNativeQuery(
                    """
                    UPDATE database_maintenance_event
                       SET status = :running
                     WHERE id = :id AND status = :pending
                    """.trimIndent(),
                )
                    .setParameter(PARAM_RUNNING, DatabaseMaintenanceStatus.RUNNING.ordinal)
                    .setParameter("pending", DatabaseMaintenanceStatus.PENDING.ordinal)
                    .setParameter("id", candidate.eventId)
                    .executeUpdate()
                check(updated == 1) { "Exclusive maintenance claim unexpectedly lost for event ${candidate.eventId}" }

                val token = em.createNativeQuery(
                    """
                    INSERT INTO maintenance_queue_execution(event_id, execution_version, claim_token, claimed_at)
                    VALUES (
                        :eventId,
                        :executionVersion,
                        nextval('maintenance_queue_claim_token_seq'),
                        :claimedAt
                    )
                    ON CONFLICT (event_id) DO UPDATE
                       SET execution_version = EXCLUDED.execution_version,
                           claim_token = nextval('maintenance_queue_claim_token_seq'),
                           claimed_at = EXCLUDED.claimed_at
                    RETURNING claim_token
                    """.trimIndent(),
                )
                    .setParameter(PARAM_EVENT_ID, candidate.eventId)
                    .setParameter(PARAM_EXECUTION_VERSION, EXECUTION_VERSION)
                    .setParameter("claimedAt", time.nowUtc())
                    .singleResult as Number

                val claimed = candidate.copy(claimToken = token.toLong())
                currentClaim = claimed
                MaintenanceDrainLease.ClaimResult.Claimed(claimed)
            }
        }

        override fun complete(
            claim: MaintenanceDrainLease.ClaimedMaintenanceJob,
            outcome: MaintenanceOperationOutcome,
        ) {
            verifyOwnership()
            QuarkusTransaction.requiringNew().call {
                val terminalStatus = when (outcome) {
                    is MaintenanceOperationOutcome.Succeeded -> DatabaseMaintenanceStatus.SUCCESS
                    is MaintenanceOperationOutcome.Failed -> DatabaseMaintenanceStatus.FAILED
                }
                val updated = em.createNativeQuery(
                    """
                    UPDATE database_maintenance_event e
                       SET status = :status, details = :details
                     WHERE e.id = :id
                       AND e.status = :running
                       AND EXISTS (
                           SELECT 1 FROM maintenance_queue_execution x
                            WHERE x.event_id = e.id AND x.execution_version = :executionVersion
                              AND x.claim_token = :claimToken
                       )
                    """.trimIndent(),
                )
                    .setParameter("status", terminalStatus.ordinal)
                    .setParameter("details", outcome.details)
                    .setParameter("id", claim.eventId)
                    .setParameter(PARAM_RUNNING, DatabaseMaintenanceStatus.RUNNING.ordinal)
                    .setParameter(PARAM_EXECUTION_VERSION, EXECUTION_VERSION)
                    .setParameter("claimToken", claim.claimToken)
                    .executeUpdate()
                check(updated == 1) { "Maintenance completion lost durable claim for event ${claim.eventId}" }
                em.createNativeQuery("DELETE FROM maintenance_queue_execution WHERE event_id = :eventId")
                    .setParameter(PARAM_EVENT_ID, claim.eventId)
                    .executeUpdate()
                currentClaim = null
            }
        }

        private fun findOldestPending(): MaintenanceDrainLease.ClaimedMaintenanceJob? =
            queryCandidate(
                """
                SELECT id, type FROM database_maintenance_event
                 WHERE status = :pending AND type IN (:recalculate, :queueIssued)
                 ORDER BY created_at, id
                 LIMIT 1
                """.trimIndent(),
            )

        private fun findOldestPendingRecalculation(): MaintenanceDrainLease.ClaimedMaintenanceJob? =
            queryCandidate(
                """
                SELECT id, type FROM database_maintenance_event
                 WHERE status = :pending AND type = :recalculate
                 ORDER BY created_at, id
                 LIMIT 1
                """.trimIndent(),
            )

        private fun queryCandidate(sql: String): MaintenanceDrainLease.ClaimedMaintenanceJob? {
            val rows: List<*> = em.createNativeQuery(sql)
                .setParameter("pending", DatabaseMaintenanceStatus.PENDING.ordinal)
                .setParameter(PARAM_RECALCULATE, DatabaseMaintenanceType.RECALCULATE_TOTALS.ordinal)
                .let { query ->
                    if (sql.contains(":queueIssued")) {
                        query.setParameter("queueIssued", DatabaseMaintenanceType.QUEUE_ISSUED_GENERATION_JOBS.ordinal)
                    } else {
                        query
                    }
                }
                .resultList
            val row = rows.firstOrNull() as? Array<*> ?: return null
            return MaintenanceDrainLease.ClaimedMaintenanceJob(
                eventId = (row[0] as Number).toLong(),
                type = DatabaseMaintenanceType.entries[(row[1] as Number).toInt()],
                claimToken = 0,
            )
        }

        private var currentClaim: MaintenanceDrainLease.ClaimedMaintenanceJob? = null

        private fun hasUnresolvedRecalculationFailureBefore(
            generation: MaintenanceDrainLease.ClaimedMaintenanceJob,
        ): Boolean {
            val count = em.createNativeQuery(
                """
                SELECT COUNT(*)
                  FROM database_maintenance_event failed
                  JOIN database_maintenance_event generation ON generation.id = :generationId
                 WHERE failed.type = :recalculate
                   AND failed.status IN (:failed, :unknown)
                   AND (failed.created_at, failed.id) < (generation.created_at, generation.id)
                   AND NOT EXISTS (
                       SELECT 1 FROM database_maintenance_event succeeded
                        WHERE succeeded.type = :recalculate
                          AND succeeded.status = :success
                          AND (succeeded.created_at, succeeded.id) > (failed.created_at, failed.id)
                   )
                """.trimIndent(),
            )
                .setParameter("generationId", generation.eventId)
                .setParameter(PARAM_RECALCULATE, DatabaseMaintenanceType.RECALCULATE_TOTALS.ordinal)
                .setParameter("failed", DatabaseMaintenanceStatus.FAILED.ordinal)
                .setParameter(PARAM_UNKNOWN, DatabaseMaintenanceStatus.UNKNOWN.ordinal)
                .setParameter("success", DatabaseMaintenanceStatus.SUCCESS.ordinal)
                .singleResult as Number
            return count.toLong() > 0
        }
    }

    companion object {
        private const val LOCK_NAMESPACE = 0x4C54494D // "LTIM"
        private const val LOCK_ID = 1
        private const val EXECUTION_VERSION = 1
    }
}
