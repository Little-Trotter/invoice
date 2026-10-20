package de.littletrotter.e2e.masterdata

import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.fixture.aRecipient
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.domain.value.Email
import de.littletrotter.e2e.PostgresTestResource
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.VatId
import de.littletrotter.domain.value.CustomerNumber

/**
 * E2E test for recipient search with multi-token matching across name, customer number,
 * shortHash, and billing city. Tokens are separated by whitespace and AND-ed together,
 * with each token allowed to match any of the four searchable fields.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class RecipientSearchE2EIT {

    @Inject
    lateinit var recipientRepository: RecipientRepository

    @Inject
    lateinit var recipientQuery: RecipientQueryPort

    private val createdRecipientIds = mutableListOf<Long>()

    @AfterEach
    fun cleanup() {
        createdRecipientIds.forEach { id ->
            QuarkusTransaction.requiringNew().run {
                recipientRepository.delete(id)
            }
        }
    }

    private fun createTestRecipient(name: String, customerNumber: String, city: String): Recipient {
        val recipient = aRecipient {
            this.name = name
            this.customerNumber = CustomerNumber.ofNullable(customerNumber)
            email = Email.ofNullable("test@example.de")
            vatId = VatId("DE123456789")
            routingId = "ROUTE-$customerNumber"
            contactPerson = "Test Contact"
            addAddress(
                Address.forRecipient(
                    entity,
                    Address.AddressType.BILLING,
                    "Teststraße 1",
                    "12345",
                    city,
                    Country.DE,
                    true
                )
            )
        }
        // ensureShortHash normally runs in CustomerManagementService.save, above the bare
        // repository persist used here — mirror it so the shortHash search token exists, same
        // reasoning as PanacheRecipientRepositorySearchIT's fixture.
        recipient.ensureShortHash()
        val saved = QuarkusTransaction.requiringNew().call { recipientRepository.save(recipient) }
        createdRecipientIds += saved.id!!
        return saved
    }

    @Test
    fun `find recipients by name token through facade`() {
        val müller = createTestRecipient("Müller GmbH", "KD-0001", "München")
        // Seeded as noise the search must NOT return — created for the side effect only.
        createTestRecipient("Schmidt AG", "KD-0002", "Hamburg")

        val results = QuarkusTransaction.requiringNew().call {
            recipientQuery.findRecipients(filterText = "müller")
        }

        assertEquals(1, results.size, "Should find exactly one recipient matching 'müller'")
        assertEquals(müller.id, results[0].id, "Found recipient should be Müller GmbH")
    }

    @Test
    fun `find recipients by customer number token through facade`() {
        val kd1 = createTestRecipient("First Corp", "KD-1001", "Berlin")
        // Seeded as noise the search must NOT return — created for the side effect only.
        createTestRecipient("Second Corp", "KD-1002", "Dresden")

        val results = QuarkusTransaction.requiringNew().call {
            recipientQuery.findRecipients(filterText = "kd-1001")
        }

        assertEquals(1, results.size, "Should find exactly one recipient matching customer number")
        assertEquals(kd1.id, results[0].id)
    }

    @Test
    fun `find recipients by shortHash token through facade`() {
        val recipient = createTestRecipient("ShortHash Corp", "KD-8888", "Cologne")
        // shortHash is auto-generated, so we fetch it first
        val saved = QuarkusTransaction.requiringNew().call {
            recipientRepository.findById(recipient.id!!)
        }!!
        // Guard against the degenerate case this test once hid: a null/blank hash normalises to
        // "no filter" and findRecipients returns every row, which is not a shortHash match.
        val shortHash = requireNotNull(saved.shortHash) { "persisted recipient must carry a shortHash" }

        val results = QuarkusTransaction.requiringNew().call {
            recipientQuery.findRecipients(filterText = shortHash)
        }

        assertEquals(1, results.size, "Should find recipient by shortHash")
        assertEquals(saved.id, results[0].id)
    }

    @Test
    fun `find recipients by billing city token through facade`() {
        val cologne1 = createTestRecipient("Cologne Company 1", "KD-5001", "Köln")
        val cologne2 = createTestRecipient("Cologne Company 2", "KD-5002", "Köln")
        // Seeded as noise the search must NOT return — created for the side effect only.
        createTestRecipient("Berlin Company", "KD-5003", "Berlin")

        val results = QuarkusTransaction.requiringNew().call {
            recipientQuery.findRecipients(filterText = "köln")
        }

        assertEquals(2, results.size, "Should find two recipients in Köln")
        assertTrue(results.any { it.id == cologne1.id }, "Should contain Cologne Company 1")
        assertTrue(results.any { it.id == cologne2.id }, "Should contain Cologne Company 2")
    }

    @Test
    fun `find recipients with multiple AND tokens (name AND city)`() {
        val match = createTestRecipient("Müller GmbH", "KD-6001", "Köln")
        // Seeded as noise the search must NOT return — created for the side effect only.
        createTestRecipient("Müller AG", "KD-6002", "Hamburg")

        val results = QuarkusTransaction.requiringNew().call {
            recipientQuery.findRecipients(filterText = "müller köln")
        }

        assertEquals(1, results.size, "Should find only Müller in Köln")
        assertEquals(match.id, results[0].id)
    }

    @Test
    fun `no match with unsatisfiable token combination returns empty`() {
        val recipient = createTestRecipient("Berlin Corp", "KD-7001", "Berlin")

        val results = QuarkusTransaction.requiringNew().call {
            recipientQuery.findRecipients(filterText = "berlin münchen")
        }

        assertEquals(0, results.size, "No recipient satisfies both 'berlin' AND 'münchen'")
    }

    @Test
    fun `count agrees with findRecipients results for token filter`() {
        createTestRecipient("SearchTest1", "KD-9001", "TestCity1")
        createTestRecipient("SearchTest2", "KD-9002", "TestCity2")

        val count = QuarkusTransaction.requiringNew().call {
            recipientQuery.countRecipients(filterText = "searchtest")
        }

        val results = QuarkusTransaction.requiringNew().call {
            recipientQuery.findRecipients(filterText = "searchtest")
        }

        assertEquals(results.size.toLong(), count, "Count should match findRecipients size")
        assertEquals(2, count)
    }

    @Test
    fun `empty filter returns all recipients`() {
        // Seeded for the side effect only — the assertion counts the whole result set.
        createTestRecipient("Recipient 1", "KD-0001", "City1")
        createTestRecipient("Recipient 2", "KD-0002", "City2")

        val results = QuarkusTransaction.requiringNew().call {
            recipientQuery.findRecipients(filterText = null)
        }

        assertTrue(results.size >= 2, "Should return all recipients when filter is null")
    }

    @Test
    fun `case-insensitive search works correctly`() {
        val recipient = createTestRecipient("UpperCaseTest", "KD-CASE", "München")

        val upperResult = QuarkusTransaction.requiringNew().call {
            recipientQuery.findRecipients(filterText = "UPPERCASE")
        }

        val lowerResult = QuarkusTransaction.requiringNew().call {
            recipientQuery.findRecipients(filterText = "uppercase")
        }

        val mixedResult = QuarkusTransaction.requiringNew().call {
            recipientQuery.findRecipients(filterText = "UpPeRcAsE")
        }

        assertEquals(1, upperResult.size, "Uppercase search should find recipient")
        assertEquals(1, lowerResult.size, "Lowercase search should find recipient")
        assertEquals(1, mixedResult.size, "Mixed case search should find recipient")
    }

    @Test
    fun `search with extra whitespace normalises tokens`() {
        val müller = createTestRecipient("Müller GmbH", "KD-0001", "Köln")

        val resultWithExtra = QuarkusTransaction.requiringNew().call {
            recipientQuery.findRecipients(filterText = "  müller   köln  ")
        }

        assertEquals(1, resultWithExtra.size, "Extra whitespace should be normalised")
        assertEquals(müller.id, resultWithExtra[0].id)
    }
}
