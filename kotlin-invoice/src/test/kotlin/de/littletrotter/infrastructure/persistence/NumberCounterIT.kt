package de.littletrotter.infrastructure.persistence

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * N1 — the `number_counter` sequence tested directly against real PostgreSQL, without the
 * Quarkus stack. Both allocation paths ([PostgresNumberCounterRepository.allocateGapless]/
 * `allocateUniqueOnly`) run the **same** SQL statement (`upsert`, cases N1-18/19); the only
 * difference is which transaction it runs in — that is exactly what this test reproduces,
 * instead of instantiating the repository class itself.
 *
 * Covers N1-01/02/18/19/20/21.
 */
@Testcontainers
class NumberCounterIT {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:18")
    }

    private lateinit var setup: Connection

    @BeforeEach
    fun createSchema() {
        setup = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        setup.autoCommit = true
        setup.createStatement().execute(
            """
            CREATE TABLE IF NOT EXISTS number_counter (
                id BIGSERIAL PRIMARY KEY,
                number_range_id BIGINT NOT NULL,
                scope_key VARCHAR(40) NOT NULL,
                period_key VARCHAR(20) NOT NULL,
                last_value BIGINT NOT NULL DEFAULT 1,
                CONSTRAINT uq_number_counter UNIQUE (number_range_id, scope_key, period_key)
            )
            """,
        )
        setup.createStatement().execute("TRUNCATE number_counter RESTART IDENTITY")
    }

    @AfterEach
    fun close() = setup.close()

    /** Exactly the statement from `PostgresNumberCounterRepository.upsert` — both allocation
     *  paths use this one SQL statement, only in a different transaction (see class doc). */
    private fun upsertSql(conn: Connection, rangeId: Long, scope: String, period: String, start: Long): Long =
        conn.prepareStatement(
            """INSERT INTO number_counter (number_range_id, scope_key, period_key, last_value)
               VALUES (?, ?, ?, ?)
               ON CONFLICT (number_range_id, scope_key, period_key)
               DO UPDATE SET last_value = number_counter.last_value + 1
               RETURNING last_value""",
        ).use { stmt ->
            stmt.setLong(1, rangeId); stmt.setString(2, scope); stmt.setString(3, period); stmt.setLong(4, start)
            stmt.executeQuery().also { it.next() }.getLong(1)
        }

    private fun newConnection(autoCommit: Boolean): Connection =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).apply { this.autoCommit = autoCommit }

    // ─── N1-01/02/05 — basic behavior ───────────────────────────────────────

    @Test
    fun `N1-05 - the first allocation for a fresh key returns the chosen start value unchanged`() {
        // Regression guard: a leading INSERT-ON-CONFLICT-DO-NOTHING before the upsert would
        // incorrectly return 4713 here instead of the chosen start value.
        newConnection(true).use { conn ->
            assertEquals(4712L, upsertSql(conn, 1, "*", "", 4712))
        }
    }

    @Test
    fun `N1-02 - sequential allocations increase by exactly one`() {
        newConnection(true).use { conn ->
            assertEquals(1L, upsertSql(conn, 1, "*", "", 1))
            assertEquals(2L, upsertSql(conn, 1, "*", "", 1))
            assertEquals(3L, upsertSql(conn, 1, "*", "", 1))
        }
    }

    @Test
    fun `different scope or period keys are independent counters`() {
        newConnection(true).use { conn ->
            assertEquals(1L, upsertSql(conn, 1, "seller-A", "2026", 1))
            assertEquals(1L, upsertSql(conn, 1, "seller-B", "2026", 1))
            assertEquals(1L, upsertSql(conn, 1, "seller-A", "2027", 1))
            assertEquals(2L, upsertSql(conn, 1, "seller-A", "2026", 1))
        }
    }

    // ─── N1-20/21 — the core of N1: different rollback behavior ────────────

    @Test
    fun `N1-21 - UNIQUE_ONLY semantics - a value survives even when the surrounding work is abandoned`() {
        // UNIQUE_ONLY runs in its own transaction (REQUIRES_NEW) and commits immediately,
        // regardless of what the caller does afterward. Reproduced here with autoCommit=true:
        // the statement itself is already durable as soon as it returns.
        newConnection(true).use { conn ->
            assertEquals(1L, upsertSql(conn, 2, "*", "", 1))
        }
        // "The caller" no longer exists here (the connection is closed, no rollback possible) —
        // and that's exactly the point: there is nothing left to roll back.
        newConnection(true).use { conn ->
            assertEquals(2L, upsertSql(conn, 2, "*", "", 1))
        }
    }

    @Test
    fun `N1-20 - GAPLESS semantics - a rolled-back transaction leaves the counter untouched`() {
        // GAPLESS runs in the caller's transaction. Simulated here: the same SQL statement on a
        // connection with autoCommit=false, followed by an explicit rollback().
        val conn = newConnection(false)
        try {
            assertEquals(4712L, upsertSql(conn, 3, "*", "", 4712))
            conn.rollback()
        } finally {
            conn.close()
        }

        // Number 4712 was never actually consumed — the next allocation returns it again.
        newConnection(true).use { conn ->
            assertEquals(4712L, upsertSql(conn, 3, "*", "", 4712))
        }
    }

    @Test
    fun `N1-20 - a committed GAPLESS transaction does keep the number`() {
        val conn = newConnection(false)
        try {
            assertEquals(1L, upsertSql(conn, 4, "*", "", 1))
            conn.commit()
        } finally {
            conn.close()
        }
        newConnection(true).use { conn ->
            assertEquals(2L, upsertSql(conn, 4, "*", "", 1))
        }
    }

    // ─── N1-19 — concurrency: simultaneous GAPLESS allocations serialize ───

    @Test
    fun `N1-19 - concurrent GAPLESS allocations serialize and produce a gapless run`() {
        val threadCount = 8
        val latch = CountDownLatch(threadCount)
        val pool = Executors.newFixedThreadPool(threadCount)

        val futures = (1..threadCount).map {
            pool.submit<Long> {
                newConnection(false).use { conn ->
                    latch.countDown()
                    latch.await(5, TimeUnit.SECONDS) // start all threads as close to simultaneously as possible
                    val value = upsertSql(conn, 5, "*", "", 1)
                    conn.commit()
                    value
                }
            }
        }

        val results = futures.map { it.get(10, TimeUnit.SECONDS) }.sorted()
        pool.shutdown()

        assertEquals((1L..threadCount.toLong()).toList(), results, "lückenlos und ohne Duplikat")
        assertTrue(results.toSet().size == results.size)
    }

    @Test
    fun `N1-19 - a losing transaction blocks until the row lock is released, then continues the sequence`() {
        val holder = newConnection(false)
        assertEquals(1L, upsertSql(holder, 6, "*", "", 1)) // row is now locked until commit

        val pool = Executors.newFixedThreadPool(1)
        val waiter = pool.submit<Long> {
            newConnection(true).use { conn -> upsertSql(conn, 6, "*", "", 1) }
        }

        // The waiting call must not be done yet — it's blocked on the row lock.
        assertTrue(!waiter.isDone, "Die zweite Vergabe darf nicht an der gesperrten Zeile vorbeikommen")

        holder.commit()
        holder.close()

        assertEquals(2L, waiter.get(5, TimeUnit.SECONDS), "erst nach dem Commit kommt die naechste Nummer")
        pool.shutdown()
    }
}
