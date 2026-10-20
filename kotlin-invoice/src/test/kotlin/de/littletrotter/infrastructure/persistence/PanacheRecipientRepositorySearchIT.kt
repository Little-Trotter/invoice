package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.fixture.aRecipient
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.domain.value.Email
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.VatId
import de.littletrotter.domain.value.CustomerNumber

/**
 * Repository IT for multi-token recipient search. Tests the real [PanacheRecipientRepository]
 * against a real Postgres via Testcontainers. Covers [RecipientRepository.findAll] and
 * [RecipientRepository.count] with token-based filtering on name, customerNumber, shortHash,
 * and billing city, with AND logic across tokens.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PanacheRecipientRepositorySearchIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var repository: RecipientRepository

    private val createdRecipientIds = mutableListOf<Long>()

    @BeforeEach
    @Transactional
    fun removeOwnRows() {
        em.createNativeQuery(
            "DELETE FROM address WHERE recipient_id IN (SELECT id FROM recipient WHERE customer_number LIKE 'PSIT-%')"
        ).executeUpdate()
        em.createNativeQuery("DELETE FROM recipient WHERE customer_number LIKE 'PSIT-%'").executeUpdate()
        createdRecipientIds.clear()
    }

    private fun createRecipient(
        name: String,
        customerNumber: String,
        billingCity: String,
    ): Recipient {
        val recipient = aRecipient {
            this.name = name
            this.customerNumber = CustomerNumber.ofNullable(customerNumber)
            email = Email.ofNullable("test@example.de")
            vatId = VatId("DE123456789")
            routingId = "ROUTE-$customerNumber"
            contactPerson = "Test Person"
        }
        // ensureShortHash normally runs in CustomerManagementService.save, one layer above the
        // repository this IT deliberately tests in isolation — mirror it here so every saved row
        // carries the shortHash a production-saved recipient would have.
        recipient.ensureShortHash()
        val saved = repository.save(recipient)
        createdRecipientIds += saved.id!!

        val billingAddress = Address.forRecipient(
            saved,
            Address.AddressType.BILLING,
            "Teststraße 1",
            "12345",
            billingCity,
            Country.DE,
            true
        )
        em.persist(billingAddress)
        em.flush()
        return saved
    }

    @Test
    @Transactional
    fun `find recipient by name token`() {
        val müller = createRecipient("Müller GmbH", "PSIT-0001", "München")
        createRecipient("Schmidt AG", "PSIT-0002", "Hamburg")

        val results = repository.findAll(filterText = "müller")

        assertEquals(1, results.size, "Should find exactly one recipient matching 'müller'")
        assertEquals(müller.id, results[0].id)
    }

    @Test
    @Transactional
    fun `find recipient by customer number token`() {
        val kd1 = createRecipient("First Corp", "PSIT-3001", "Berlin")
        createRecipient("Second Corp", "PSIT-3002", "Dresden")

        val results = repository.findAll(filterText = "psit-3001")

        assertEquals(1, results.size)
        assertEquals(kd1.id, results[0].id)
    }

    @Test
    @Transactional
    fun `find recipient by shortHash token`() {
        val recipient = createRecipient("HashCorp", "PSIT-4001", "Bonn")
        val hash = recipient.shortHash
        assertNotNull(hash, "repository.save must have generated a shortHash")

        val results = repository.findAll(filterText = hash!!.lowercase())

        assertTrue(results.any { it.id == recipient.id }, "shortHash token must match its own recipient")
        assertEquals(results.size.toLong(), repository.count(hash.lowercase()))
    }

    @Test
    @Transactional
    fun `find recipient by billing city token`() {
        val cologne1 = createRecipient("Cologne Company 1", "PSIT-5001", "Köln")
        val cologne2 = createRecipient("Cologne Company 2", "PSIT-5002", "Köln")
        val berlin = createRecipient("Berlin Company", "PSIT-5003", "Berlin")

        val results = repository.findAll(filterText = "köln")

        assertEquals(2, results.size)
        assertTrue(results.any { it.id == cologne1.id })
        assertTrue(results.any { it.id == cologne2.id })
        assertFalse(results.any { it.id == berlin.id })
    }

    @Test
    @Transactional
    fun `find recipient with multiple AND tokens (name AND city)`() {
        val match = createRecipient("Müller GmbH", "PSIT-6001", "Köln")
        createRecipient("Müller AG", "PSIT-6002", "Hamburg")

        val results = repository.findAll(filterText = "müller köln")

        assertEquals(1, results.size, "Should find only Müller in Köln")
        assertEquals(match.id, results[0].id)
    }

    @Test
    @Transactional
    fun `case-insensitive search works correctly`() {
        val recipient = createRecipient("UpperCaseTest", "PSIT-7001", "München")

        val upperResult = repository.findAll(filterText = "UPPERCASE")
        val lowerResult = repository.findAll(filterText = "uppercase")
        val mixedResult = repository.findAll(filterText = "UpPeRcAsE")

        assertEquals(1, upperResult.size)
        assertEquals(1, lowerResult.size)
        assertEquals(1, mixedResult.size)
    }

    @Test
    @Transactional
    fun `literal percent is neutralised in search`() {
        val recipient = createRecipient("Percent Inc. (50%)", "PSIT-8001", "City")

        val results = repository.findAll(filterText = "50%")

        assertEquals(1, results.size)
        assertEquals(recipient.id, results[0].id)
    }

    @Test
    @Transactional
    fun `count agrees with findAll size for token filter`() {
        createRecipient("SearchTest1", "PSIT-A001", "TestCity1")
        createRecipient("SearchTest2", "PSIT-A002", "TestCity2")

        val count = repository.count(filterText = "searchtest")
        val results = repository.findAll(filterText = "searchtest")

        assertEquals(results.size.toLong(), count)
        assertEquals(2, count)
    }

    @Test
    @Transactional
    fun `count and findAll return empty for unsatisfiable token combination`() {
        createRecipient("Berlin Corp", "PSIT-B001", "Berlin")

        val results = repository.findAll(filterText = "berlin münchen")
        val count = repository.count(filterText = "berlin münchen")

        assertEquals(0, results.size)
        assertEquals(0, count)
    }
}
