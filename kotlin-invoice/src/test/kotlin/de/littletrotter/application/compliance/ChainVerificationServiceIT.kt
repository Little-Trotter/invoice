package de.littletrotter.application.compliance

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.message.MessageCenterService
import de.littletrotter.application.system.DatabaseMaintenanceService
import de.littletrotter.domain.compliance.NumberJournalChain
import de.littletrotter.domain.compliance.NumberJournalEntry
import de.littletrotter.domain.compliance.NumberJournalEvent
import de.littletrotter.domain.reference.NumberRange
import de.littletrotter.domain.repository.InvoiceAuditJournalRepository
import de.littletrotter.domain.repository.NumberJournalRepository
import de.littletrotter.domain.repository.NumberRangeRepository
import de.littletrotter.domain.system.DatabaseMaintenanceStatus
import de.littletrotter.domain.system.DatabaseMaintenanceType
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.OffsetDateTime

/**
 * [ChainVerificationService] against a real Postgres (via [PostgresTestResource], same
 * `lt_app`/`lt_migrator` role separation the production app runs under), covering what the
 * mocked [ChainVerificationServiceTest] cannot: the actual `streamAllBatched`/`streamBatch`
 * cursor pagination against live rows, and a chain corruption applied the way it would really have
 * to happen — an `UPDATE ... entry_hash` issued as the Testcontainers bootstrap superuser with
 * `session_replication_role=replica` (bypassing the append-only trigger, which rejects that
 * statement unconditionally for every role, `lt_migrator` included — see [corruptEntryHashAsMigrator]'s
 * own KDoc) rather than through any application code path.
 *
 * **HARD CONSTRAINT (this program, 2026-09): no Docker/Testcontainers may run in this
 * environment.** [PostgresTestResource] itself provisions its Postgres via Testcontainers, so
 * this class compiles but is deliberately never executed here — same deferred-IT shape as
 * [de.littletrotter.infrastructure.persistence.InvoiceAuditJournalCaptureTriggerIT]/
 * [de.littletrotter.infrastructure.persistence.NumberJournalIT]. Written now so a later run (real
 * CI, or a developer machine with Docker) has it ready.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class ChainVerificationServiceIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var numberRangeRepository: NumberRangeRepository

    @Inject
    lateinit var numberJournalRepository: NumberJournalRepository

    @Inject
    lateinit var invoiceAuditJournalRepository: InvoiceAuditJournalRepository

    @Inject
    lateinit var chainVerificationService: ChainVerificationService

    @Inject
    lateinit var maintenanceService: DatabaseMaintenanceService

    @Inject
    lateinit var messageCenterService: MessageCenterService

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    lateinit var jdbcUrl: String

    private val now = OffsetDateTime.parse("2026-09-04T10:00:00Z")
    private var rangeId: Long = 0

    // Must match the range's own persisted `code` exactly — NumberJournalChain.genesisHash(code,
    // startValue) is part of the hash input, so any mismatch between what a test uses to compute
    // its fixture's genesis and the range's real code makes ChainVerificationService correctly
    // report a break on a chain that was never actually broken. Found via a live-Postgres run of
    // this IT (2026-09-04): the previous version hardcoded the literal "RS12_IT" here while the
    // range itself carried a nanoTime-suffixed code, so every appended entry's prev_hash never
    // matched what verification recomputed from the range it actually looked up.
    private lateinit var rangeCode: String

    @BeforeEach
    fun setUp() {
        rangeCode = "RS12_IT_${System.nanoTime()}"
        val range = numberRangeRepository.save(
            NumberRange().apply {
                code = rangeCode
                displayName = "RS-12 IT range"
                startValue = 1L
            },
        )
        rangeId = requireNotNull(range.id)
    }

    @AfterEach
    fun tearDown() {
        // number_journal is append-only for EVERY role, not just lt_app: the append-only guard trigger
        // (BEFORE UPDATE OR DELETE ... RAISE EXCEPTION) has no role exemption, so even lt_migrator
        // cannot DELETE from it — only the Testcontainers bootstrap superuser can, and only with
        // session_replication_role=replica (bypasses triggers, same technique
        // datagen/generate.py's own demo-seed already relies on for the same reason). Found via a
        // live-Postgres run of this very IT (2026-09-04) — the original version used the app's own
        // lt_app connection, which by design has no privilege on this table at all, let alone a way past
        // the trigger.
        withSuperuserConnection { conn ->
            conn.createStatement().use { it.execute("SET session_replication_role = replica") }
            conn.prepareStatement("DELETE FROM number_journal WHERE number_range_id = ?").use { stmt ->
                stmt.setLong(1, rangeId)
                stmt.executeUpdate()
            }
            conn.createStatement().use { it.execute("SET session_replication_role = DEFAULT") }
        }
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery("DELETE FROM number_range WHERE id = :rangeId")
                .setParameter("rangeId", rangeId).executeUpdate()
        }
    }

    /** The Testcontainers bootstrap superuser — [PostgresTestResource] always provisions it as
     *  `test`/`test`, the only role in this database with `SUPERUSER` and therefore the only one
     *  that can set `session_replication_role`, itself a superuser-only GUC. Test-only: production
     *  never has an equivalent connection available, on purpose. */
    private fun withSuperuserConnection(block: (java.sql.Connection) -> Unit) {
        DriverManager.getConnection(jdbcUrl, "test", "test").use(block)
    }

    private fun appendNumberJournalEntry(seq: Long, prevHash: String, genesis: String): NumberJournalEntry {
        val entry = NumberJournalEntry(
            numberRangeId = rangeId, event = NumberJournalEvent.ALLOCATED, periodKey = "2026",
            numberPrefix = "P", numberSequence = seq, number = "P-2026-0000$seq", entityType = "Invoice",
            entityId = 1L, reason = null, actor = "system", occurredAt = now.plusSeconds(seq),
            prevHash = prevHash, entryHash = "",
        )
        return QuarkusTransaction.requiringNew().call { numberJournalRepository.append(entry, genesis) }
    }

    /** Simulates a chain break the way it would genuinely have to happen against this schema:
     *  The append-only guard trigger rejects `UPDATE`/`DELETE` on `number_journal`/
     *  `invoice_audit_journal` unconditionally, for every role — not merely for `lt_app` — so even
     *  `lt_migrator` (the only role with the table-level `UPDATE` privilege at all, baseline-schema section
     *  14) cannot flip a row through the trigger. Only the bootstrap superuser can, and only with
     *  `session_replication_role=replica` bypassing triggers for the session — see [tearDown]'s own
     *  KDoc for why. Corrected from an earlier `lt_migrator`-only version via a live-Postgres run of
     *  this IT (2026-09-04): that version's own premise didn't survive contact with the trigger. */
    private fun corruptEntryHashAsMigrator(table: String, seq: Long) {
        withSuperuserConnection { conn ->
            conn.createStatement().use { it.execute("SET session_replication_role = replica") }
            conn.createStatement().use { st ->
                st.execute("UPDATE $table SET entry_hash = 'deliberately-corrupted' WHERE seq = $seq")
            }
            conn.createStatement().use { it.execute("SET session_replication_role = DEFAULT") }
        }
    }

    // ─── streamAllBatched / streamBatch — live pagination ────────────────────────────

    @Test
    fun `RS-12 - streamAllBatched pages number_journal rows in seq order via the afterSeq cursor`() {
        val genesis = NumberJournalChain.genesisHash(rangeCode, 1L)
        val first = appendNumberJournalEntry(1, genesis, genesis)
        val second = appendNumberJournalEntry(2, first.entryHash, genesis)
        val third = appendNumberJournalEntry(3, second.entryHash, genesis)

        val page1 = numberJournalRepository.streamAllBatched(0L, batchSize = 2)
        assertEquals(2, page1.size)
        assertEquals(listOf(first.seq, second.seq), page1.map { it.seq })

        val page2 = numberJournalRepository.streamAllBatched(page1.last().seq!!, batchSize = 2)
        assertEquals(listOf(third.seq), page2.map { it.seq })

        val page3 = numberJournalRepository.streamAllBatched(page2.last().seq!!, batchSize = 2)
        assertTrue(page3.isEmpty())
    }

    @Test
    fun `RS-12 - streamBatch pages invoice_audit_journal rows via the afterSeq cursor`() {
        // Fires capture_invoice_audit_event() the same way a real invoice creation does — every
        // NOT NULL column but invoice_date has a DEFAULT (see V1__baseline_schema.sql), so that is
        // the only one this INSERT needs to supply itself to produce one journal row.
        val invoiceId = QuarkusTransaction.requiringNew().call {
            (em.createNativeQuery("INSERT INTO invoice (invoice_date) VALUES ('2026-06-15') RETURNING id").singleResult as Number).toLong()
        }
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery("UPDATE invoice SET free_text = 'rs12-it' WHERE id = :id")
                .setParameter("id", invoiceId).executeUpdate()
        }

        val page1 = invoiceAuditJournalRepository.streamBatch(0L, batchSize = 1)
        assertEquals(1, page1.size)
        val page2 = invoiceAuditJournalRepository.streamBatch(page1.last().seq, batchSize = 1)
        assertEquals(1, page2.size)
        assertTrue(page2.first().seq > page1.first().seq)

        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery("DELETE FROM invoice WHERE id = :id").setParameter("id", invoiceId).executeUpdate()
        }
    }

    // ─── verifyAllChains — clean run ──────────────────────────────────────────────────

    @Test
    fun `RS-12 - a clean number_journal chain verifies with no break and records SUCCESS`() {
        val genesis = NumberJournalChain.genesisHash(rangeCode, 1L)
        val first = appendNumberJournalEntry(1, genesis, genesis)
        appendNumberJournalEntry(2, first.entryHash, genesis)

        val outcome = chainVerificationService.verifyAllChains()

        assertFalse(outcome.hasBreak)
        val event = maintenanceService.latestMaintenanceEvent(DatabaseMaintenanceType.CHAIN_VERIFICATION)
        assertEquals(DatabaseMaintenanceStatus.SUCCESS, event?.status)
    }

    // ─── verifyAllChains — a real, migrator-issued corruption is caught ──────────────

    @Test
    fun `RS-12 - a number_journal row rewritten as lt_migrator is detected and reported`() {
        val genesis = NumberJournalChain.genesisHash(rangeCode, 1L)
        val first = appendNumberJournalEntry(1, genesis, genesis)
        val second = appendNumberJournalEntry(2, first.entryHash, genesis)

        corruptEntryHashAsMigrator("number_journal", second.seq!!)

        val outcome = chainVerificationService.verifyAllChains()

        assertTrue(outcome.hasBreak)
        assertTrue(outcome.numberJournal.breaks.containsKey(rangeId))
        val event = maintenanceService.latestMaintenanceEvent(DatabaseMaintenanceType.CHAIN_VERIFICATION)
        assertEquals(DatabaseMaintenanceStatus.FAILED, event?.status)
    }
}
