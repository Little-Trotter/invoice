package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.message.SystemMessageContent
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.message.SystemMessage
import de.littletrotter.domain.message.SystemMessageReceipt
import de.littletrotter.domain.message.SystemMessageSeverity
import de.littletrotter.domain.message.SystemMessageSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * Verifies `system_message` + `system_message_receipt` (`V1__baseline_schema.sql`)
 * end to end against a real Postgres (Testcontainers, full Flyway chain via
 * [PostgresTestResource]), same pattern as [B2bProfileMigrationIT]: table/index shape, the
 * receipt uniqueness constraint, and the `ON DELETE CASCADE` the retention cleanup
 * ([de.littletrotter.application.message.MessageCenterService.cleanup]) relies on instead of
 * loading and cascading receipts itself.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class SystemMessageMigrationIT {

    @Inject
    lateinit var em: EntityManager

    private fun rowCount(sql: String): Long = (em.createNativeQuery(sql).singleResult as Number).toLong()

    private fun columnExists(table: String, column: String): Boolean =
        rowCount(
            "SELECT COUNT(*) FROM information_schema.columns " +
                "WHERE table_name = '$table' AND column_name = '$column'"
        ) == 1L

    private fun indexExists(index: String): Boolean =
        rowCount("SELECT COUNT(*) FROM pg_indexes WHERE indexname = '$index'") == 1L

    private fun aMessage(recipientUserId: String? = null): SystemMessage = SystemMessage.of(
        SystemMessageContent(
            severity = SystemMessageSeverity.INFO,
            source = SystemMessageSource.SYSTEM,
            messageKey = "message.system.startup",
            args = listOf("v1"),
            resolvedText = "System gestartet.",
            refType = "INVOICE",
            refId = "34",
        ),
        actor = "system",
        occurredAt = OffsetDateTime.now(),
        recipientUserId = recipientUserId,
    )

    // ── table/column/index shape ────────────────────────────────────────────────────────────

    @Test
    @Transactional
    fun `system_message carries every column the MC-1 data model specifies`() {
        listOf(
            "occurred_at", "severity", "source", "message_key", "message_args", "resolved_text",
            "ref_type", "ref_id", "actor", "recipient_user_id", "version",
        ).forEach {
            assertTrue(columnExists("system_message", it), "system_message.$it should exist")
        }
    }

    @Test
    @Transactional
    fun `system_message_receipt carries message_id, user_id and read_at`() {
        listOf("message_id", "user_id", "read_at").forEach {
            assertTrue(columnExists("system_message_receipt", it), "system_message_receipt.$it should exist")
        }
    }

    @Test
    @Transactional
    fun `the occurred_at and recipient-scoped indexes exist`() {
        assertTrue(indexExists("idx_system_message_occurred_at"))
        assertTrue(indexExists("idx_system_message_recipient_occurred"))
    }

    // ── entity round trip (JPA mapping, @Enumerated(STRING), message_args) ─────────────────────

    @Test
    @Transactional
    fun `a SystemMessage persists and reloads with its severity-source enums and args intact`() {
        val message = aMessage(recipientUserId = "alice")
        em.persist(message)
        em.flush()
        em.clear()

        val reloaded = em.find(SystemMessage::class.java, message.id)
        assertEquals(SystemMessageSeverity.INFO, reloaded.severity)
        assertEquals(SystemMessageSource.SYSTEM, reloaded.source)
        assertEquals(listOf("v1"), reloaded.argsList())
        assertEquals("alice", reloaded.recipientUserId)

        // Persisted by ordinal ( conversion) — the raw column value is a smallint
        val rawSeverity = (em.createNativeQuery(
            "SELECT severity FROM system_message WHERE id = ${message.id}"
        ).singleResult as Number).toInt()
        assertEquals(0, rawSeverity)  // SystemMessageSeverity.INFO = 0
    }

    @Test
    @Transactional
    fun `recipient_user_id is nullable - a broadcast message persists with it NULL`() {
        val message = aMessage(recipientUserId = null)
        em.persist(message)
        em.flush()
        em.clear()

        assertNull(em.find(SystemMessage::class.java, message.id).recipientUserId)
    }

    // ── CHECK constraints on severity/source ────────────────────────────────────────────────

    @Test
    @Transactional
    fun `an out-of-range severity ordinal is rejected at the DB`() {
        // 99 is a valid smallint but outside chk_system_message_severity's IN-list (0,1,2) —
        // exercises the CHECK constraint itself, not just the column's smallint type.
        assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                "INSERT INTO system_message (occurred_at, severity, source, message_key, resolved_text, actor, version) " +
                    "VALUES (now(), 99, 4, 'k', 't', 'system', 0)"
            ).executeUpdate()
            em.flush()
        }
    }

    @Test
    @Transactional
    fun `an out-of-range source ordinal is rejected at the DB`() {
        // 99 is a valid smallint but outside chk_system_message_source's IN-list (0..5).
        assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                "INSERT INTO system_message (occurred_at, severity, source, message_key, resolved_text, actor, version) " +
                    "VALUES (now(), 0, 99, 'k', 't', 'system', 0)"
            ).executeUpdate()
            em.flush()
        }
    }

    // ── receipt uniqueness ───────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    fun `a message can carry at most one receipt per user`() {
        val message = aMessage()
        em.persist(message)
        em.flush()

        em.persist(SystemMessageReceipt.of(message.id!!, "alice", OffsetDateTime.now()))
        em.flush()

        assertThrows(PersistenceException::class.java) {
            em.persist(SystemMessageReceipt.of(message.id!!, "alice", OffsetDateTime.now()))
            em.flush()
        }
    }

    @Test
    @Transactional
    fun `the same message can carry receipts for two different users`() {
        val message = aMessage()
        em.persist(message)
        em.flush()

        em.persist(SystemMessageReceipt.of(message.id!!, "alice", OffsetDateTime.now()))
        em.persist(SystemMessageReceipt.of(message.id!!, "bob", OffsetDateTime.now()))
        em.flush()

        val count = rowCount("SELECT COUNT(*) FROM system_message_receipt WHERE message_id = ${message.id}")
        assertEquals(2L, count)
    }

    // ── ON DELETE CASCADE ────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    fun `deleting a message cascades to its receipts - MessageCenterService cleanup relies on this`() {
        val message = aMessage()
        em.persist(message)
        em.flush()
        em.persist(SystemMessageReceipt.of(message.id!!, "alice", OffsetDateTime.now()))
        em.flush()

        // Plain bulk DELETE at the SQL level, same shape as
        // PanacheSystemMessageRepository.deleteOlderThan's JPQL bulk delete — no JPA-level
        // cascade annotation is involved, the DB does the cascading.
        em.createNativeQuery("DELETE FROM system_message WHERE id = ${message.id}").executeUpdate()

        val remainingReceipts = rowCount("SELECT COUNT(*) FROM system_message_receipt WHERE message_id = ${message.id}")
        assertEquals(0L, remainingReceipts)
    }
}
