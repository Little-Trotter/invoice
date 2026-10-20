package de.littletrotter.infrastructure.persistence

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * N4 — the journal tested directly against real PostgreSQL, without the Quarkus stack. Covers
 * N4-07 through N4-10: append-only is enforced by the database itself (not just the repository
 * interface), and a journal entry shares the counter's rollback fate when both are written in
 * the same transaction.
 */
@Testcontainers
class NumberJournalIT {

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
        setup.createStatement().execute("DROP TABLE IF EXISTS number_journal")
        setup.createStatement().execute("DROP SEQUENCE IF EXISTS number_journal_seq")
        setup.createStatement().execute("CREATE SEQUENCE number_journal_seq")
        setup.createStatement().execute(
            """
            CREATE TABLE number_journal (
                seq              BIGINT PRIMARY KEY DEFAULT nextval('number_journal_seq'),
                number_range_id  BIGINT       NOT NULL,
                event            VARCHAR(20)  NOT NULL,
                period_key       VARCHAR(20)  NOT NULL,
                number_prefix    VARCHAR(30),
                number_sequence  BIGINT,
                number           VARCHAR(80),
                entity_type      VARCHAR(40),
                entity_id        BIGINT,
                reason           TEXT,
                actor            VARCHAR(255) NOT NULL,
                occurred_at      TIMESTAMPTZ  NOT NULL,
                prev_hash        VARCHAR(64)  NOT NULL,
                entry_hash       VARCHAR(64)  NOT NULL
            )
            """,
        )
        // mirrors V1__baseline_schema.sql's reject_append_only_mutation()/
        // trg_number_journal_append_only exactly (the CREATE RULE ... DO INSTEAD NOTHING pair
        // this hand-rolled schema used to carry no longer exists in the real baseline).
        setup.createStatement().execute(
            """
            CREATE OR REPLACE FUNCTION reject_append_only_mutation() RETURNS TRIGGER AS $$
            BEGIN
                RAISE EXCEPTION '% is append-only; % is not permitted', TG_TABLE_NAME, TG_OP
                    USING ERRCODE = '23001';
            END;
            $$ LANGUAGE plpgsql
            """,
        )
        setup.createStatement().execute(
            """
            CREATE TRIGGER trg_number_journal_append_only
                BEFORE UPDATE OR DELETE ON number_journal
                FOR EACH ROW
                EXECUTE FUNCTION reject_append_only_mutation()
            """,
        )
        setup.createStatement().execute("TRUNCATE number_counter, number_journal RESTART IDENTITY")
    }

    @AfterEach
    fun close() = setup.close()

    private fun newConnection(autoCommit: Boolean): Connection =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).apply { this.autoCommit = autoCommit }

    private fun insertJournalEntry(conn: Connection, prevHash: String, entryHash: String, number: String): Long {
        val seq = conn.createStatement().executeQuery("SELECT nextval('number_journal_seq')")
            .also { it.next() }.getLong(1)
        conn.prepareStatement(
            """INSERT INTO number_journal
                 (seq, number_range_id, event, period_key, number, actor, occurred_at, prev_hash, entry_hash)
               VALUES (?, 1, 'ALLOCATED', '2026', ?, 'system', now(), ?, ?)""",
        ).use { stmt ->
            stmt.setLong(1, seq); stmt.setString(2, number); stmt.setString(3, prevHash); stmt.setString(4, entryHash)
            stmt.executeUpdate()
        }
        return seq
    }

    private fun rowCount(conn: Connection, table: String): Long =
        conn.createStatement().executeQuery("SELECT COUNT(*) FROM $table").also { it.next() }.getLong(1)

    // ─── N4-09/10 — the database enforces append-only, not just the repository ──────

    @Test
    fun `RS-11 - N4-09 - UPDATE on number_journal raises loudly instead of being a silent no-op`() {
        // Pre- this table's RULEs made the UPDATE succeed with zero visible effect;
        // replaces that with a trigger that RAISEs instead — the whole point being that a caller
        // can no longer be misled into thinking their write went through.
        newConnection(true).use { conn ->
            insertJournalEntry(conn, "genesis", "hash-1", "P-2026-00001")
            val ex = assertThrows(SQLException::class.java) {
                conn.createStatement().executeUpdate("UPDATE number_journal SET reason = 'nachtraeglich' WHERE seq = 1")
            }
            assertEquals(true, ex.message?.contains("append-only") == true, "expected the guard's own message, got: ${ex.message}")

            val reason = conn.createStatement().executeQuery("SELECT reason FROM number_journal WHERE seq = 1")
                .let { it.next(); it.getString(1) }
            assertEquals(null, reason, "the row must be entirely untouched after the rejected UPDATE")
        }
    }

    @Test
    fun `RS-11 - N4-10 - DELETE on number_journal raises loudly instead of being a silent no-op`() {
        newConnection(true).use { conn ->
            insertJournalEntry(conn, "genesis", "hash-1", "P-2026-00001")
            val ex = assertThrows(SQLException::class.java) {
                conn.createStatement().executeUpdate("DELETE FROM number_journal WHERE seq = 1")
            }
            assertEquals(true, ex.message?.contains("append-only") == true, "expected the guard's own message, got: ${ex.message}")

            assertEquals(1L, rowCount(conn, "number_journal"), "the row must still be present after the rejected DELETE")
        }
    }

    // ─── N4-01 (continued against a real sequence) ───────────────────────────

    @Test
    fun `seq comes from a dedicated sequence, independent of insertion order`() {
        newConnection(true).use { conn ->
            val first = insertJournalEntry(conn, "genesis", "hash-1", "P-2026-00001")
            val second = insertJournalEntry(conn, "hash-1", "hash-2", "P-2026-00002")
            assertEquals(first + 1, second)
        }
    }

    // ─── N4-07 — GAPLESS: counter and journal entry share the same rollback fate ──

    @Test
    fun `N4-07 - a rolled-back GAPLESS allocation leaves neither the counter nor the journal entry behind`() {
        val conn = newConnection(false)
        try {
            // Increment the counter (same statement as PostgresNumberCounterRepository.upsert)
            conn.prepareStatement(
                """INSERT INTO number_counter (number_range_id, scope_key, period_key, last_value)
                   VALUES (1, '*', '2026', 1)
                   ON CONFLICT (number_range_id, scope_key, period_key)
                   DO UPDATE SET last_value = number_counter.last_value + 1
                   RETURNING last_value""",
            ).executeQuery().also { it.next() }

            // Journal entry in the same transaction
            insertJournalEntry(conn, "genesis", "hash-1", "P-2026-00001")

            conn.rollback()
        } finally {
            conn.close()
        }

        newConnection(true).use { conn ->
            assertEquals(0L, rowCount(conn, "number_journal"), "kein Journaleintrag ueberlebt den Rollback")
            assertEquals(0L, rowCount(conn, "number_counter"), "auch der Zaehler bleibt unveraendert")
        }
    }

    @Test
    fun `N4-07 - a committed GAPLESS allocation keeps both the counter and the journal entry`() {
        val conn = newConnection(false)
        try {
            conn.prepareStatement(
                """INSERT INTO number_counter (number_range_id, scope_key, period_key, last_value)
                   VALUES (1, '*', '2026', 1)
                   ON CONFLICT (number_range_id, scope_key, period_key)
                   DO UPDATE SET last_value = number_counter.last_value + 1
                   RETURNING last_value""",
            ).executeQuery().also { it.next() }
            insertJournalEntry(conn, "genesis", "hash-1", "P-2026-00001")
            conn.commit()
        } finally {
            conn.close()
        }

        newConnection(true).use { conn ->
            assertEquals(1L, rowCount(conn, "number_journal"))
            assertEquals(1L, rowCount(conn, "number_counter"))
        }
    }
}
