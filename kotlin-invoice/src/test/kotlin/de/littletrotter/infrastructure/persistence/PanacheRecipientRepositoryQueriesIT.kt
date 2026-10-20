package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.exception.DuplicateCustomerNumberException
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.domain.value.Email
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.VatId
import de.littletrotter.domain.value.CustomerNumber

/**
 * real-Hibernate coverage for [PanacheRecipientRepository] methods not already exercised
 * by [PanacheRecipientRepositorySearchIT] (token-based [RecipientRepository.findAll]/[count]).
 * Own `COV8-REC-` customer-number token keeps cleanup and assertions scoped to this test's own
 * fixtures in the shared dev-services database.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PanacheRecipientRepositoryQueriesIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var repository: RecipientRepository

    @AfterEach
    @Transactional
    fun removeOwnRows() {
        em.createNativeQuery("DELETE FROM address WHERE recipient_id IN (SELECT id FROM recipient WHERE customer_number LIKE 'COV8-REC-%')").executeUpdate()
        em.createNativeQuery("DELETE FROM recipient WHERE customer_number LIKE 'COV8-REC-%'").executeUpdate()
        em.createNativeQuery("DELETE FROM b2b_profile WHERE name LIKE 'COV8-REC %'").executeUpdate()
    }

    private fun newRecipient(customerNumber: String, name: String, contactPerson: String = "Kontakt"): Recipient =
        aRecipient {
            this.customerNumber = CustomerNumber.ofNullable(customerNumber)
            this.name = name
            email = Email.ofNullable("$customerNumber@overview.example")
            vatId = VatId("DE${customerNumber.hashCode().mod(900000000) + 100000000}")
            this.contactPerson = contactPerson
        }

    @Test
    @Transactional
    fun `happy path - save persists a recipient, findByCustomerNumber and its case-insensitive variant find it, and delete removes it`() {
        val saved = repository.save(newRecipient("COV8-REC-0001", "COV8-REC Alpha GmbH"))

        assertEquals(saved.id, repository.findByCustomerNumber("COV8-REC-0001")?.id)
        assertEquals(saved.id, repository.findByCustomerNumberIgnoreCase("cov8-rec-0001")?.id)
        assertNull(repository.findByCustomerNumber("COV8-REC-NO-SUCH"))

        repository.delete(saved.id!!)
        assertNull(repository.findByCustomerNumber("COV8-REC-0001"))
    }

    @Test
    @Transactional
    fun `boundary - findById force-initializes lazy addresses and the b2bProfile so both are readable once detached`() {
        val profile = B2bProfile().edit { name = "COV8-REC B2bProfile" }
        em.persist(profile)
        val recipient = repository.save(newRecipient("COV8-REC-0002", "COV8-REC Beta AG").edit { b2bProfile = profile })
        val address = Address.forRecipient(recipient, Address.AddressType.BILLING, "Teststr. 1", "12345", "Musterstadt", Country.DE, true)
        em.persist(address)
        em.flush()
        em.clear()

        val found = repository.findById(recipient.id!!)
        em.detach(found)
        assertEquals(1, found?.addressList?.size, "addresses must be initialized before detach")
        assertEquals("COV8-REC B2bProfile", found?.b2bProfile?.name, "b2bProfile must be initialized before detach")
    }

    @Test
    @Transactional
    fun `boundary - each sort-allowlist field (customerNumber, name, city, contactPerson) and an unknown field all order without error`() {
        val first = repository.save(newRecipient("COV8-REC-1001", "COV8-REC Aaa", "Anna"))
        val second = repository.save(newRecipient("COV8-REC-1002", "COV8-REC Bbb", "Bernd"))
        em.persist(Address.forRecipient(first, Address.AddressType.BILLING, "Str. 1", "10115", "Aachen", Country.DE, true))
        em.persist(Address.forRecipient(second, Address.AddressType.BILLING, "Str. 2", "20095", "Berlin", Country.DE, true))
        em.flush()

        for (field in listOf("customerNumber", "name", "city", "contactPerson", "unknown-field")) {
            val ordered = repository.findAll(filterText = "cov8-rec-100", sortField = field, sortDir = SortDirection.ASC)
            val ids = ordered.map { it.id }
            assertTrue(ids.indexOf(first.id) < ids.indexOf(second.id), "field '$field' must sort Aaa/Aachen before Bbb/Berlin ascending")
        }
    }

    @Test
    fun `boundary - save translates a duplicate customer number into DuplicateCustomerNumberException`() {
        // Each save runs in its own top-level transaction (ST-B4: save deliberately flushes in the
        // *ambient* transaction, not REQUIRES_NEW) — the violating call must abort only its own,
        // isolated transaction, not this test method's other work.
        QuarkusTransaction.requiringNew().run {
            repository.save(newRecipient("COV8-REC-2001", "COV8-REC Existing"))
        }

        assertThrows(DuplicateCustomerNumberException::class.java) {
            QuarkusTransaction.requiringNew().run {
                repository.save(newRecipient("COV8-REC-2001", "COV8-REC Duplicate"))
            }
        }
    }

    @Test
    @Transactional
    fun `boundary - findAllCustomerNumbers, forceVersionIncrement, findNamesByIds, findNamesByB2bProfileId and countGroupedByB2bProfile all reflect saved rows`() {
        val profile = B2bProfile().edit { name = "COV8-REC Grouped Profile" }
        em.persist(profile)
        val recipient = repository.save(newRecipient("COV8-REC-3001", "COV8-REC Grouped").edit { b2bProfile = profile })
        em.flush()

        assertTrue("COV8-REC-3001" in repository.findAllCustomerNumbers())

        val versionBefore = recipient.version
        repository.forceVersionIncrement(recipient)
        em.flush()
        em.refresh(recipient)
        assertNotNull(recipient.version)
        assertTrue((recipient.version ?: 0L) >= (versionBefore ?: 0L))

        val names = repository.findNamesByIds(listOf(recipient.id!!))
        assertEquals("COV8-REC Grouped", names[recipient.id])

        assertTrue(repository.findNamesByB2bProfileId(profile.id!!).contains("COV8-REC Grouped"))
        assertEquals(1L, repository.countGroupedByB2bProfile()[profile.id])
    }
}
