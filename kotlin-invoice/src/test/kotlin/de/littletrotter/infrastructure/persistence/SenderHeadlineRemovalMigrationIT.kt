package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * P3-T01 — verifies the V021 migration end to end against a real Postgres (Testcontainers, full
 * Flyway chain via [PostgresTestResource]/`quarkus.flyway.migrate-at-start`): `header_note` is
 * gone from `sender_profile`, and the table's other columns (and rows created against the
 * pre-V021 shape via V901/dev fixtures) are otherwise unaffected.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class SenderHeadlineRemovalMigrationIT {

    @Inject
    lateinit var em: EntityManager

    @Test
    @Transactional
    fun `header_note no longer exists on sender_profile`() {
        val columnExists = (em.createNativeQuery(
            """SELECT COUNT(*) FROM information_schema.columns
               WHERE table_name = 'sender_profile' AND column_name = 'header_note'"""
        ).singleResult as Number).toLong()
        assertEquals(0, columnExists, "V021 must have dropped sender_profile.header_note")
    }

    @Test
    @Transactional
    fun `footer_note is gone, replaced by invoice_note_group_id`() {
        // footer_note (VARCHAR(200), one language) was replaced in place by
        // invoice_note_group_id (a localized_text_group reference, any number of languages).
        // Greenfield: the old column does not linger beside the new one.
        val footerNoteExists = (em.createNativeQuery(
            """SELECT COUNT(*) FROM information_schema.columns
               WHERE table_name = 'sender_profile' AND column_name = 'footer_note'"""
        ).singleResult as Number).toLong()
        assertEquals(0, footerNoteExists, "footer_note must be gone (IN-2 replaced it with invoice_note_group_id)")

        val invoiceNoteGroupIdExists = (em.createNativeQuery(
            """SELECT COUNT(*) FROM information_schema.columns
               WHERE table_name = 'sender_profile' AND column_name = 'invoice_note_group_id'"""
        ).singleResult as Number).toLong()
        assertEquals(1, invoiceNoteGroupIdExists, "invoice_note_group_id must exist (IN-2)")
    }

    @Test
    @Transactional
    fun `a fresh sender_profile row inserts and reads back without header_note`() {
        val id = (em.createNativeQuery(
            "INSERT INTO sender_profile (name, invoice_prefix, created_at, version) VALUES ('P3 Sender', 'P3-', now(), 0) RETURNING id"
        ).singleResult as Number).toLong()
        em.flush()
        em.clear()

        val name = em.createNativeQuery("SELECT name FROM sender_profile WHERE id = :id")
            .setParameter("id", id).singleResult as String
        assertTrue(name == "P3 Sender")
    }
}
