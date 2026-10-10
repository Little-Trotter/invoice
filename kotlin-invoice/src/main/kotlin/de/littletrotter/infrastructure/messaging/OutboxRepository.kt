package de.littletrotter.infrastructure.messaging

import de.littletrotter.shared.time.TimeSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.sql.Timestamp
import javax.sql.DataSource

/**
 * JDBC access to the `outbox` table.
 *
 * Plain JDBC on the pooled data source instead of a JPA entity: the table is infrastructure, not
 * part of the domain model. [append] takes its connection from the pool, which joins a surrounding
 * JTA transaction, so the row commits or rolls back together with the producer's domain change.
 * Every other method is its own short transaction (autocommit) and never spans a NATS call.
 * Timestamps come from the [TimeSource], the database never reads its own clock.
 */
@ApplicationScoped
class OutboxRepository @Inject constructor(
    private val dataSource: DataSource,
    private val time: TimeSource,
) {

    /** Inserts a PENDING row and returns its id. Call inside the producer's transaction. */
    fun append(aggregateType: String, aggregateId: String, subject: String, payload: String): Long =
        dataSource.connection.use { connection ->
            connection.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS).use { statement ->
                statement.bind(
                    aggregateType, aggregateId, subject, payload,
                    OutboxStatus.PENDING.ordinal, Timestamp.from(time.instant()),
                )
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    check(keys.next()) { "outbox insert returned no id" }
                    keys.getLong("id")
                }
            }
        }

    /** The oldest [limit] unpublished rows, in insertion order. */
    fun findPending(limit: Int): List<OutboxEntry> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(SELECT_PENDING).use { statement ->
                statement.setInt(1, limit)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                OutboxEntry(
                                    id = rs.getLong("id"),
                                    aggregateType = rs.getString("aggregate_type"),
                                    aggregateId = rs.getString("aggregate_id"),
                                    subject = rs.getString("subject"),
                                    payload = rs.getString("payload"),
                                    attempts = rs.getInt("attempts"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    /** Marks the row PUBLISHED; a row that is no longer PENDING is left untouched. */
    fun markPublished(id: Long) {
        dataSource.connection.use { connection ->
            update(connection, MARK_PUBLISHED) {
                it.bind(Timestamp.from(time.instant()), id)
            }
        }
    }

    /**
     * Records a failed publish: the row stays PENDING for the next run until it has used
     * [maxAttempts], then becomes FAILED and is left for an operator.
     */
    fun markFailed(id: Long, error: String, maxAttempts: Int) {
        dataSource.connection.use { connection ->
            update(connection, MARK_FAILED) {
                it.bind(error, maxAttempts, OutboxStatus.FAILED.ordinal, id, OutboxStatus.PENDING.ordinal)
            }
        }
    }

    private fun update(connection: Connection, sql: String, bind: (PreparedStatement) -> Unit) {
        connection.prepareStatement(sql).use {
            bind(it)
            it.executeUpdate()
        }
    }

    private fun PreparedStatement.bind(vararg values: Any) =
        values.forEachIndexed { index, value -> setObject(index + 1, value) }

    private companion object {
        const val INSERT =
            "INSERT INTO outbox (aggregate_type, aggregate_id, subject, payload, status, attempts, created_at) " +
                "VALUES (?, ?, ?, ?, ?, 0, ?)"
        const val SELECT_PENDING =
            "SELECT id, aggregate_type, aggregate_id, subject, payload, attempts FROM outbox " +
                "WHERE status = 0 ORDER BY id LIMIT ?"
        const val MARK_PUBLISHED =
            "UPDATE outbox SET status = 1, published_at = ? WHERE id = ? AND status = 0"
        const val MARK_FAILED =
            "UPDATE outbox SET attempts = attempts + 1, last_error = ?, " +
                "status = CASE WHEN attempts + 1 >= ? THEN ? ELSE status END WHERE id = ? AND status = ?"
    }
}
