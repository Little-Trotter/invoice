package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.compliance.NumberJournalChain
import de.littletrotter.domain.compliance.NumberJournalEntry
import de.littletrotter.domain.compliance.NumberJournalEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

/**
 * N4 §6/N4-18 — the actual confirmation that `ci/numberjournal-verify` (a second, independently
 * written Go program; see its own package comment) reaches the **same** result as the application
 * itself: this class writes entries via [NumberJournalChain] — the same hash function
 * [de.littletrotter.infrastructure.persistence.PostgresNumberJournalRepository] also uses — into a
 * real Postgres, then runs the Go verifier against exactly that database. A verification report
 * that only the audited application itself can produce is self-testimony (N4 §6) — this test is
 * the proof that it isn't one.
 */
@Testcontainers
class NumberJournalIndependentVerifierIT {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:18")

        private lateinit var verifierBinary: File

        @JvmStatic
        @BeforeAll
        fun buildVerifier() {
            val repoRoot = File(System.getProperty("user.dir")).parentFile
            val verifierDir = File(repoRoot, "ci/numberjournal-verify")
            verifierBinary = File(verifierDir, "numberjournal-verify")
            val build = ProcessBuilder("go", "build", "-o", verifierBinary.absolutePath, ".")
                .directory(verifierDir)
                .redirectErrorStream(true)
                .start()
            val output = build.inputStream.bufferedReader().readText()
            val exited = build.waitFor(120, TimeUnit.SECONDS)
            check(exited && build.exitValue() == 0) { "go build fehlgeschlagen:\n$output" }
        }
    }

    private lateinit var setup: Connection

    @BeforeEach
    fun createSchema() {
        setup = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        setup.autoCommit = true
        setup.createStatement().execute(
            """CREATE TABLE IF NOT EXISTS number_range (
                 id BIGSERIAL PRIMARY KEY, code VARCHAR(40) NOT NULL, start_value BIGINT NOT NULL
               )""",
        )
        setup.createStatement().execute("DROP TABLE IF EXISTS number_journal")
        setup.createStatement().execute("DROP SEQUENCE IF EXISTS number_journal_seq")
        setup.createStatement().execute("CREATE SEQUENCE number_journal_seq")
        setup.createStatement().execute(
            """CREATE TABLE number_journal (
                 seq BIGINT PRIMARY KEY DEFAULT nextval('number_journal_seq'),
                 number_range_id BIGINT NOT NULL, event VARCHAR(20) NOT NULL, period_key VARCHAR(20) NOT NULL,
                 number_prefix VARCHAR(30), number_sequence BIGINT, number VARCHAR(80),
                 entity_type VARCHAR(40), entity_id BIGINT, reason TEXT,
                 actor VARCHAR(255) NOT NULL, occurred_at TIMESTAMPTZ NOT NULL,
                 prev_hash VARCHAR(64) NOT NULL, entry_hash VARCHAR(64) NOT NULL,
                 sealed_start_value BIGINT, sealed_count BIGINT,
                 sealed_first_number VARCHAR(80), sealed_last_number VARCHAR(80)
               )""",
        )
        setup.createStatement().execute("TRUNCATE number_range, number_journal RESTART IDENTITY")
    }

    @AfterEach
    fun close() = setup.close()

    private fun insertRange(code: String, startValue: Long): Long {
        val rs = setup.prepareStatement(
            "INSERT INTO number_range (code, start_value) VALUES (?, ?) RETURNING id",
        ).apply { setString(1, code); setLong(2, startValue) }.executeQuery()
        rs.next()
        return rs.getLong(1)
    }

    private val now: OffsetDateTime = OffsetDateTime.parse("2026-08-21T10:00:00Z")

    /** Inserts an entry — entryHash computation goes through [NumberJournalChain], the exact
     *  same function the application uses when writing. */
    private fun insert(
        rangeId: Long,
        prevHash: String,
        event: NumberJournalEvent = NumberJournalEvent.ALLOCATED,
        periodKey: String = "2026",
        numberPrefix: String? = "P",
        numberSequence: Long? = null,
        number: String? = null,
        sealedStartValue: Long? = null,
        sealedCount: Long? = null,
        sealedFirstNumber: String? = null,
        sealedLastNumber: String? = null,
        overrideEntryHash: String? = null,
        overridePrevHash: String? = null,
    ): Pair<Long, String> {
        val seq = setup.createStatement().executeQuery("SELECT nextval('number_journal_seq')")
            .also { it.next() }.getLong(1)
        val hash = overrideEntryHash ?: NumberJournalChain.computeEntryHash(
            prevHash,
            seq,
            NumberJournalEntry(
                numberRangeId = rangeId, event = event, periodKey = periodKey, numberPrefix = numberPrefix,
                numberSequence = numberSequence, number = number, entityType = "Invoice",
                entityId = if (event == NumberJournalEvent.ALLOCATED) seq else null, reason = null,
                actor = "system", occurredAt = now, prevHash = prevHash, entryHash = "",
                sealedStartValue = sealedStartValue, sealedCount = sealedCount,
                sealedFirstNumber = sealedFirstNumber, sealedLastNumber = sealedLastNumber,
            ),
        )
        setup.prepareStatement(
            """INSERT INTO number_journal
                 (seq, number_range_id, event, period_key, number_prefix, number_sequence, number,
                  entity_type, entity_id, actor, occurred_at, prev_hash, entry_hash,
                  sealed_start_value, sealed_count, sealed_first_number, sealed_last_number)
               VALUES (?, ?, ?, ?, ?, ?, ?, 'Invoice', ?, 'system', ?, ?, ?, ?, ?, ?, ?)""",
        ).apply {
            setLong(1, seq); setLong(2, rangeId); setString(3, event.name); setString(4, periodKey)
            setString(5, numberPrefix); setObject(6, numberSequence); setString(7, number)
            setObject(8, if (event == NumberJournalEvent.ALLOCATED) seq else null)
            setObject(9, now); setString(10, overridePrevHash ?: prevHash); setString(11, hash)
            setObject(12, sealedStartValue); setObject(13, sealedCount)
            setString(14, sealedFirstNumber); setString(15, sealedLastNumber)
        }.executeUpdate()
        return seq to hash
    }

    private fun runVerifier(rangeId: Long, vararg extraArgs: String): Pair<Int, String> {
        val dsn = "postgres://${postgres.username}:${postgres.password}@" +
            "${postgres.host}:${postgres.getMappedPort(5432)}/${postgres.databaseName}"
        val process = ProcessBuilder(
            listOf(verifierBinary.absolutePath, "--dsn", dsn, "--range-id", rangeId.toString()) + extraArgs,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exited = process.waitFor(30, TimeUnit.SECONDS)
        check(exited) { "numberjournal-verify hat nicht rechtzeitig beendet" }
        return process.exitValue() to output
    }

    // ─── N4-18 — same result as the application's own report ─────────────────

    @Test
    fun `N4-18 - a gapless, unmodified chain is reported clean by the independent verifier`() {
        val rangeId = insertRange("INVOICE_GAPLESS", 1L)
        val genesis = NumberJournalChain.genesisHash("INVOICE_GAPLESS", 1L)
        val (_, hash1) = insert(rangeId, genesis, numberSequence = 1L, number = "P-2026-00001")
        insert(rangeId, hash1, numberSequence = 2L, number = "P-2026-00002")

        val (exitCode, output) = runVerifier(rangeId)

        assertEquals(0, exitCode, output)
        assertTrue(output.contains("ohne Beanstandung"), output)
    }

    @Test
    fun `N4-18 - a tampered prevHash is caught as KETTENBRUCH by the independent verifier`() {
        val rangeId = insertRange("INVOICE_GAPLESS", 1L)
        val genesis = NumberJournalChain.genesisHash("INVOICE_GAPLESS", 1L)
        val (_, hash1) = insert(rangeId, genesis, numberSequence = 1L, number = "P-2026-00001")
        // second entry deliberately points at a wrong previous hash
        insert(rangeId, hash1, numberSequence = 2L, number = "P-2026-00002", overridePrevHash = "0".repeat(64))

        val (exitCode, output) = runVerifier(rangeId)

        assertEquals(1, exitCode, output)
        assertTrue(output.contains("KETTENBRUCH"), output)
    }

    @Test
    fun `N4-18 - a period rollover to the reset start value is not flagged, matching the application's report`() {
        val rangeId = insertRange("INVOICE_GAPLESS", 1L)
        val genesis = NumberJournalChain.genesisHash("INVOICE_GAPLESS", 1L)
        val (_, h1) = insert(rangeId, genesis, periodKey = "2026", numberSequence = 1L, number = "P-2026-00001")
        val (_, h2) = insert(rangeId, h1, periodKey = "2026", numberSequence = 2L, number = "P-2026-00002")
        // new year, counter restarts at 1 -- not a gap, not a duplicate (N4-16)
        insert(rangeId, h2, periodKey = "2027", numberSequence = 1L, number = "P-2027-00001")

        val (exitCode, output) = runVerifier(rangeId)

        assertEquals(0, exitCode, output)
        assertTrue(output.contains("ohne Beanstandung"), output)
    }

    // ─── N4 §4/§5 — seal, independently verified ──────────────────────────────

    @Test
    fun `a seal whose recorded head matches the externally supplied one verifies clean`() {
        val rangeId = insertRange("INVOICE_GAPLESS", 1L)
        val genesis = NumberJournalChain.genesisHash("INVOICE_GAPLESS", 1L)
        val (_, h1) = insert(rangeId, genesis, numberSequence = 1L, number = "P-2026-00001")
        val (_, sealHash) = insert(
            rangeId, h1, event = NumberJournalEvent.PERIOD_SEALED, numberPrefix = null,
            sealedStartValue = 1L, sealedCount = 1L, sealedFirstNumber = "P-2026-00001", sealedLastNumber = "P-2026-00001",
        )

        val (exitCode, output) = runVerifier(rangeId, "--seal", "2026=$h1")

        assertEquals(0, exitCode, output)
        assertTrue(output.contains("ohne Beanstandung"), output)
    }

    @Test
    fun `a seal whose recorded head does not match the externally supplied one is a SIEGELABWEICHUNG`() {
        val rangeId = insertRange("INVOICE_GAPLESS", 1L)
        val genesis = NumberJournalChain.genesisHash("INVOICE_GAPLESS", 1L)
        val (_, h1) = insert(rangeId, genesis, numberSequence = 1L, number = "P-2026-00001")
        insert(
            rangeId, h1, event = NumberJournalEvent.PERIOD_SEALED, numberPrefix = null,
            sealedStartValue = 1L, sealedCount = 1L, sealedFirstNumber = "P-2026-00001", sealedLastNumber = "P-2026-00001",
        )

        val wrongExternalHead = "0".repeat(64)
        val (exitCode, output) = runVerifier(rangeId, "--seal", "2026=$wrongExternalHead")

        assertEquals(1, exitCode, output)
        assertTrue(output.contains("SIEGELABWEICHUNG"), output)
    }

    // ─── IMP-4 — control character in a canonicalization field ───────────────

    /**
     * `NumberJournalChain.assertCanonicalizable` rejects control characters at write time — this
     * test reconstructs the row that could bypass the application: a control character in the
     * prefix inserted directly into the table. The hash is computed over exactly this value, so
     * the chain still verifies correctly — proving STEUERZEICHEN is its own check, not a side
     * effect of KETTENBRUCH.
     */
    @Test
    fun `IMP-4 - ein Steuerzeichen im number_prefix wird als STEUERZEICHEN gemeldet, die Kette bleibt trotzdem gueltig`() {
        val rangeId = insertRange("INVOICE_GAPLESS", 1L)
        val genesis = NumberJournalChain.genesisHash("INVOICE_GAPLESS", 1L)
        // A real control character (codepoint 1) in the prefix, inserted directly since
        // assertCanonicalizable would reject it at write time.
        val dirtyPrefix = "\u0001P"
        insert(rangeId, genesis, numberPrefix = dirtyPrefix, numberSequence = 1L, number = "P-2026-00001")

        val (exitCode, output) = runVerifier(rangeId)

        assertEquals(1, exitCode, output)
        assertTrue(output.contains("STEUERZEICHEN"), output)
        assertTrue(output.contains("Kettenprüfung: gültig"), "die Kette selbst ist unversehrt: $output")
    }

    @Test
    fun `IMP-4 - eine gewoehnliche Kette ohne Steuerzeichen loest keinen STEUERZEICHEN-Befund aus`() {
        val rangeId = insertRange("INVOICE_GAPLESS", 1L)
        val genesis = NumberJournalChain.genesisHash("INVOICE_GAPLESS", 1L)
        insert(rangeId, genesis, numberPrefix = "P", numberSequence = 1L, number = "P-2026-00001")

        val (exitCode, output) = runVerifier(rangeId)

        assertEquals(0, exitCode, output)
        assertTrue(!output.contains("STEUERZEICHEN"), output)
    }
}
