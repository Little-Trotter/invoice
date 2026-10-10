package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.reference.Language
import de.littletrotter.domain.reference.LocalizedTextGroup
import de.littletrotter.domain.reference.LocalizedTextPurpose
import io.quarkus.test.TestTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * verifies the `language` / `localized_text_group` / `localized_text` schema end to end
 * against a real Postgres (Testcontainers, full Flyway chain via [PostgresTestResource]): the
 * seeded `de`/`en` rows, the partial unique index on `is_fallback`, the not-blank CHECK on
 * `localized_text.value` and the cascade delete from group to texts. A static check cannot see any
 * of these — they only fire against a real constraint evaluation.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class LanguageMigrationIT {

    @Inject
    lateinit var em: EntityManager

    private fun rowCount(sql: String): Long = (em.createNativeQuery(sql).singleResult as Number).toLong()

    // ── seed ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @TestTransaction
    fun `V2 seeds de as the fallback language and en as a non-fallback active language`() {
        assertEquals(
            1L,
            rowCount("SELECT COUNT(*) FROM language WHERE code = 'de' AND is_active AND is_fallback"),
        )
        assertEquals(
            1L,
            rowCount("SELECT COUNT(*) FROM language WHERE code = 'en' AND is_active AND NOT is_fallback"),
        )
    }

    // ── uq_language_single_fallback ───────────────────────────────────────────────────────────

    @Test
    @TestTransaction
    fun `a second is_fallback row is rejected by the partial unique index`() {
        assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                "INSERT INTO language (id, code, is_active, is_fallback, sort_order) VALUES (99, 'fr', TRUE, TRUE, 30)"
            ).executeUpdate()
            em.flush()
        }
    }

    @Test
    @TestTransaction
    fun `a second non-fallback language is accepted`() {
        assertDoesNotThrow {
            em.createNativeQuery(
                "INSERT INTO language (id, code, is_active, is_fallback, sort_order) VALUES (99, 'fr', TRUE, FALSE, 30)"
            ).executeUpdate()
            em.flush()
        }
    }

    // ── chk_language_code_format ──────────────────────────────────────────────────────────────

    @Test
    @TestTransaction
    fun `a code that does not match the ISO 639-1 -BCP-47 pattern is rejected`() {
        assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                "INSERT INTO language (id, code, is_active, is_fallback, sort_order) VALUES (98, 'DE', TRUE, FALSE, 40)"
            ).executeUpdate()
            em.flush()
        }
    }

    @Test
    @TestTransaction
    fun `a region-tagged code such as en-GB is accepted`() {
        assertDoesNotThrow {
            em.createNativeQuery(
                "INSERT INTO language (id, code, is_active, is_fallback, sort_order) VALUES (97, 'en-GB', TRUE, FALSE, 50)"
            ).executeUpdate()
            em.flush()
        }
    }

    // ── localized_text_group / localized_text ─────────────────────────────────────────────────

    private fun insertGroup(): Long =
        (em.createNativeQuery("INSERT INTO localized_text_group (purpose) VALUES (0) RETURNING id").singleResult as Number).toLong()

    @Test
    @TestTransaction
    fun `localized_text rejects a blank value`() {
        val groupId = insertGroup()
        assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                "INSERT INTO localized_text (group_id, language_id, value) VALUES ($groupId, 1, '')"
            ).executeUpdate()
            em.flush()
        }
    }

    @Test
    @TestTransaction
    fun `localized_text rejects a whitespace-only value`() {
        val groupId = insertGroup()
        assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                "INSERT INTO localized_text (group_id, language_id, value) VALUES ($groupId, 1, '   ')"
            ).executeUpdate()
            em.flush()
        }
    }

    @Test
    @TestTransaction
    fun `localized_text_group purpose is range-checked to the three declared constants`() {
        assertThrows(PersistenceException::class.java) {
            em.createNativeQuery("INSERT INTO localized_text_group (purpose) VALUES (3)").executeUpdate()
            em.flush()
        }
    }

    @Test
    @TestTransaction
    fun `deleting a group cascades its texts and leaves no orphans`() {
        val groupId = insertGroup()
        em.createNativeQuery(
            "INSERT INTO localized_text (group_id, language_id, value) VALUES ($groupId, 1, 'Deutscher Text')"
        ).executeUpdate()
        em.createNativeQuery(
            "INSERT INTO localized_text (group_id, language_id, value) VALUES ($groupId, 2, 'English text')"
        ).executeUpdate()
        em.flush()
        assertEquals(2L, rowCount("SELECT COUNT(*) FROM localized_text WHERE group_id = $groupId"))

        em.createNativeQuery("DELETE FROM localized_text_group WHERE id = $groupId").executeUpdate()
        em.flush()

        assertEquals(0L, rowCount("SELECT COUNT(*) FROM localized_text WHERE group_id = $groupId"))
    }

    @Test
    @TestTransaction
    fun `at most one text per language per group - the primary key rejects a second row`() {
        val groupId = insertGroup()
        em.createNativeQuery(
            "INSERT INTO localized_text (group_id, language_id, value) VALUES ($groupId, 1, 'Erster Text')"
        ).executeUpdate()
        em.flush()

        assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                "INSERT INTO localized_text (group_id, language_id, value) VALUES ($groupId, 1, 'Zweiter Text')"
            ).executeUpdate()
            em.flush()
        }
    }

    // ── JPA object graph — LocalizedTextGroup/LocalizedTextEntry, not just raw SQL ────────────

    @Test
    @TestTransaction
    fun `persisting a group cascades its entries, and the purpose enum round-trips through its converter`() {
        val german = em.find(Language::class.java, 1)
        val english = em.find(Language::class.java, 2)
        val group = LocalizedTextGroup().apply {
            purpose = LocalizedTextPurpose.PAYMENT_TERM_NAME
            addEntry(german, "Sofort")
            addEntry(english, "Due immediately")
        }
        em.persist(group)
        em.flush()
        em.clear()

        val reloaded = em.find(LocalizedTextGroup::class.java, group.id)
        assertEquals(LocalizedTextPurpose.PAYMENT_TERM_NAME, reloaded.purpose)
        assertEquals(
            mapOf("de" to "Sofort", "en" to "Due immediately"),
            reloaded.entries.associate { it.language?.code to it.value },
        )
    }

    @Test
    @TestTransaction
    fun `removing an entry from the owning collection deletes its row - orphanRemoval`() {
        val german = em.find(Language::class.java, 1)
        val english = em.find(Language::class.java, 2)
        val group = LocalizedTextGroup().apply {
            addEntry(german, "Deutscher Text")
            addEntry(english, "English text")
        }
        em.persist(group)
        em.flush()
        val groupId = group.id!!

        group.entries.removeIf { it.language?.code == "en" }
        em.flush()
        em.clear()

        assertEquals(1L, rowCount("SELECT COUNT(*) FROM localized_text WHERE group_id = $groupId"))
    }

    @Test
    @TestTransaction
    fun `deleting the group entity itself cascades - no orphaned rows survive`() {
        val german = em.find(Language::class.java, 1)
        val group = LocalizedTextGroup().apply {
            addEntry(german, "Deutscher Text")
        }
        em.persist(group)
        em.flush()
        val groupId = group.id!!

        em.remove(group)
        em.flush()

        assertEquals(0L, rowCount("SELECT COUNT(*) FROM localized_text WHERE group_id = $groupId"))
        assertNull(em.find(LocalizedTextGroup::class.java, groupId))
    }
}
