package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.reference.B2bFieldValue
import de.littletrotter.domain.repository.B2bFieldValueRepository
import de.littletrotter.shared.b2b.B2bTerm
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * AP-IMP-6 — [PanacheB2bFieldValueRepository] had no direct test at all before this class: every
 * existing IT in this package covers a *different* B2b repository
 * ([PanacheRecipientB2bFieldValueRepository] via `RecipientB2bFieldValueRepositoryIT`,
 * [PanacheB2bProfileFieldRepository] elsewhere). Same shared-Postgres pattern as those (no table
 * cleanup — every fixture below uses a distinct, high `ownerId` range so it cannot collide with
 * seed data or sibling tests), against real Postgres via [PostgresTestResource].
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PanacheB2bFieldValueRepositoryIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var repository: B2bFieldValueRepository

    private fun persist(ownerType: B2bFieldOwnerType, ownerId: Long, term: B2bTerm, value: String?): Long =
        QuarkusTransaction.requiringNew().call<Long> {
            val entity = B2bFieldValue().apply {
                this.ownerType = ownerType
                this.ownerId = ownerId
                this.term = term
                this.value = value
            }
            em.persist(entity)
            em.flush()
            entity.id!!
        }

    @Test
    fun `findByOwner returns every value row for that owner, and none for an owner with no rows`() {
        val ownerId = 91_000_001L
        persist(B2bFieldOwnerType.SENDER_PROFILE, ownerId, B2bTerm.BT_58, "IBAN-DEMO")
        persist(B2bFieldOwnerType.SENDER_PROFILE, ownerId, B2bTerm.BT_56, "Ansprechpartner")

        val found = repository.findByOwner(B2bFieldOwnerType.SENDER_PROFILE, ownerId)
        assertEquals(2, found.size)
        assertTrue(found.any { it.term == B2bTerm.BT_58 && it.value == "IBAN-DEMO" })
        assertTrue(found.any { it.term == B2bTerm.BT_56 && it.value == "Ansprechpartner" })

        assertTrue(repository.findByOwner(B2bFieldOwnerType.SENDER_PROFILE, 91_000_002L).isEmpty())
    }

    @Test
    fun `findByOwnerAndBtCode finds the matching term and returns null for a different one`() {
        val ownerId = 91_000_010L
        persist(B2bFieldOwnerType.INVOICE, ownerId, B2bTerm.BT_58, "DE02120300000000202051")

        val hit = repository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE, ownerId, B2bTerm.BT_58)
        assertNotNull(hit)
        assertEquals("DE02120300000000202051", hit!!.value)

        assertNull(repository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE, ownerId, B2bTerm.BT_56))
        // Same term, different owner type — the owner-type column must be part of the match, not
        // just ownerId/term.
        assertNull(repository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE_ITEM, ownerId, B2bTerm.BT_58))
    }

    @Test
    fun `findByOwners returns rows across every requested owner and an empty list for an empty request`() {
        // Empty-input branch: findByOwners short-circuits before ever building a query.
        assertTrue(repository.findByOwners(B2bFieldOwnerType.ARTICLE, emptyList()).isEmpty())

        val ownerA = 91_000_020L
        val ownerB = 91_000_021L
        persist(B2bFieldOwnerType.ARTICLE, ownerA, B2bTerm.BT_58, "A")
        persist(B2bFieldOwnerType.ARTICLE, ownerB, B2bTerm.BT_58, "B")
        // A third owner deliberately left out of the requested list, to prove the IN-clause
        // actually filters instead of returning every row of the owner type.
        persist(B2bFieldOwnerType.ARTICLE, 91_000_022L, B2bTerm.BT_58, "C")

        val found = repository.findByOwners(B2bFieldOwnerType.ARTICLE, listOf(ownerA, ownerB))
        assertEquals(setOf(ownerA, ownerB), found.map { it.ownerId }.toSet())
    }

    @Test
    fun `save inserts a new row then updates the existing one via merge`() {
        val ownerId = 91_000_030L
        val entity = B2bFieldValue().apply {
            this.ownerType = B2bFieldOwnerType.SENDER_PROFILE
            this.ownerId = ownerId
            this.term = B2bTerm.BT_58
            this.value = "first"
        }

        val saved = QuarkusTransaction.requiringNew().call<B2bFieldValue> { repository.save(entity) }
        assertNotNull(saved.id)

        saved.value = "second"
        QuarkusTransaction.requiringNew().run { repository.save(saved) }

        val reloaded = repository.findByOwnerAndBtCode(B2bFieldOwnerType.SENDER_PROFILE, ownerId, B2bTerm.BT_58)
        assertEquals("second", reloaded?.value)
    }

    @Test
    fun `delete removes the row so it no longer shows up in findByOwner`() {
        val ownerId = 91_000_040L
        val id = persist(B2bFieldOwnerType.SENDER_PROFILE, ownerId, B2bTerm.BT_58, "to-delete")

        QuarkusTransaction.requiringNew().run { repository.delete(id) }

        assertTrue(repository.findByOwner(B2bFieldOwnerType.SENDER_PROFILE, ownerId).isEmpty())
    }
}
