package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.reference.B2bProfileField
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.edit
import de.littletrotter.shared.b2b.B2bTerm
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.CustomerNumber

/**
 * The B2B field values of a recipient are written through [Recipient.recordB2bFieldValue] and the
 * recipient repository, against a real Postgres (Testcontainers, via [PostgresTestResource]):
 * insert, projection read, removal by blank value, plus the
 * `uq_recipient_b2b_field_value_recipient_field` unique index the DB enforces underneath.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class RecipientB2bFieldValuePersistenceIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var repository: RecipientRepository

    @Inject
    lateinit var fieldValues: RecipientFieldValueReadRepositoryImpl

    /** Own rows only, own naming prefix — Quarkus test transactions commit, they don't roll
     *  back, so leftovers from a previous run must not collide with this one (same reasoning as
     *  [ArticleRecipientRefRepositoryIT]). */
    @BeforeEach
    fun removeOwnRows() {
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery(
                "DELETE FROM recipient_b2b_field_value WHERE recipient_id IN (SELECT id FROM recipient WHERE customer_number LIKE 'ARC23-%')"
            ).executeUpdate()
            em.createNativeQuery(
                "DELETE FROM b2b_profile_field WHERE profile_id IN (SELECT id FROM b2b_profile WHERE name LIKE 'arc23-%')"
            ).executeUpdate()
            em.createNativeQuery("DELETE FROM b2b_profile WHERE name LIKE 'arc23-%'").executeUpdate()
            em.createNativeQuery("DELETE FROM recipient WHERE customer_number LIKE 'ARC23-%'").executeUpdate()
        }
    }

    private fun newRecipient(customerNumber: String): Recipient =
        aRecipient { this.customerNumber = CustomerNumber(customerNumber); this.name = "ARC-23 IT Recipient" }
            .also { em.persist(it); em.flush() }

    private fun newRecipientField(profileName: String, term: B2bTerm = B2bTerm.BT_58): B2bProfileField {
        val profile = B2bProfile().edit { name = profileName; active = true }.also { em.persist(it); em.flush() }
        val field = B2bProfileField().edit {
            this.profile = profile
            this.term = term
            businessDefinition = "Käufer-Kontakt-E-Mail"
            requirement = B2bFieldRequirement.OPTIONAL
            placement = B2bPlacement.RECIPIENT
        }
        em.persist(field)
        em.flush()
        return field
    }

    @Test
    @Transactional
    fun `a stored value reloads through the field value projection`() {
        val recipient = newRecipient("ARC23-0001")
        val field = newRecipientField("arc23-profile-1")

        val saved = recipient.recordB2bFieldValue(field.id!!, "kunde@example.com")
        repository.save(recipient)
        em.flush()
        em.clear()

        assertNotNull(saved)
        assertEquals(mapOf(field.id!! to "kunde@example.com"), fieldValues.findValues(recipient.id!!))
    }

    @Test
    @Transactional
    fun `the projection returns every stored field of that recipient`() {
        val recipient = newRecipient("ARC23-0002")
        val fieldA = newRecipientField("arc23-profile-2a", B2bTerm.BT_58)
        val fieldB = newRecipientField("arc23-profile-2b", B2bTerm.BT_56)
        recipient.recordB2bFieldValue(fieldA.id!!, "a@example.com")
        recipient.recordB2bFieldValue(fieldB.id!!, "b@example.com")
        repository.save(recipient)
        em.flush()
        em.clear()

        val rows = fieldValues.findValues(recipient.id!!)

        assertEquals(mapOf(fieldA.id!! to "a@example.com", fieldB.id!! to "b@example.com"), rows)
    }

    @Test
    @Transactional
    fun `a blank value removes the stored row`() {
        val recipient = newRecipient("ARC23-0003")
        val field = newRecipientField("arc23-profile-3")
        recipient.recordB2bFieldValue(field.id!!, "to-delete@example.com")
        repository.save(recipient)
        em.flush()

        assertNull(recipient.recordB2bFieldValue(field.id!!, "  "))
        repository.save(recipient)
        em.flush()
        em.clear()

        assertTrue(fieldValues.findValues(recipient.id!!).isEmpty())
    }

    @Test
    @Transactional
    fun `the unique index rejects a second row for the same recipient and profile field`() {
        val recipient = newRecipient("ARC23-0004")
        val field = newRecipientField("arc23-profile-4")
        recipient.recordB2bFieldValue(field.id!!, "first@example.com")
        repository.save(recipient)
        em.flush()

        val ex = assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                "INSERT INTO recipient_b2b_field_value (recipient_id, b2b_profile_field_id, value, version) " +
                    "VALUES (${recipient.id}, ${field.id}, 'second@example.com', 0)"
            ).executeUpdate()
        }
        assertTrue(
            ex.message?.contains("uq_recipient_b2b_field_value_recipient_field") == true ||
                ex.message?.contains("duplicate key value violates unique constraint") == true,
            "Expected a unique-constraint violation, got: ${ex.message}",
        )
    }
}
