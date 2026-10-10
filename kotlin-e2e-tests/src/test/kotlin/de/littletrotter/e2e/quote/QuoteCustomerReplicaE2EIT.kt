package de.littletrotter.e2e.quote

import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.Email
import de.littletrotter.e2e.NatsTestResource
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.QuoteAppTestResource
import de.littletrotter.e2e.QuoteModuleTestProfile
import de.littletrotter.e2e.S3TestResource
import de.littletrotter.shared.country.Country
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * A customer created in the invoicing application becomes selectable in the offer module: the
 * master data event leaves the invoicing outbox, the module's consumer turns it into a replica, and
 * the module's customer lookup finds it over HTTP. Both applications are the real ones; the module
 * runs as its image next to the invoicing application under test. Synthetic data only.
 */
@QuarkusTest
@TestProfile(QuoteModuleTestProfile::class)
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(S3TestResource::class)
@QuarkusTestResource(NatsTestResource::class)
@QuarkusTestResource(QuoteAppTestResource::class, restrictToAnnotatedClass = true)
class QuoteCustomerReplicaE2EIT {

    @Inject
    lateinit var recipientRepository: RecipientRepository

    private val createdRecipientIds = mutableListOf<Long>()

    @AfterEach
    fun cleanup() {
        createdRecipientIds.forEach { id -> QuarkusTransaction.requiringNew().run { recipientRepository.delete(id) } }
    }

    @Test
    fun `a customer created in invoicing can be selected in the offer module`() {
        val name = "Angebotskunde ${System.nanoTime()} GmbH"
        createCustomer(name, "KD-Q-${System.nanoTime() % 100_000}")

        val lookup = awaitLookup(name)

        assertTrue(lookup.contains(name), "the offer module's customer lookup finds the replicated customer: $lookup")
    }

    private fun createCustomer(name: String, customerNumber: String) {
        val recipient = Recipient().apply {
            this.name = name
            this.customerNumber = CustomerNumber.ofNullable(customerNumber)
            email = Email.ofNullable("kunde@example.org")
            addAddress(Address.forRecipient(this, Address.AddressType.BILLING, "Teststrasse 1", "12345", "Musterstadt", Country.DE, true))
        }
        recipient.ensureShortHash()
        val saved = QuarkusTransaction.requiringNew().call { recipientRepository.save(recipient) }
        createdRecipientIds += saved.id!!
    }

    /** Polls the module's lookup until the customer shows up; replication is asynchronous. */
    private fun awaitLookup(name: String): String {
        val config = ConfigProvider.getConfig()
        val baseUrl = config.getValue(QuoteAppTestResource.BASE_URL_PROPERTY, String::class.java)
        val token = config.getValue(QuoteAppTestResource.TOKEN_PROPERTY, String::class.java)
        val request = HttpRequest.newBuilder(
            URI.create("$baseUrl/quote/api/lookup/customers?limit=5&text=" + URLEncoder.encode(name, Charsets.UTF_8)),
        )
            .header("Authorization", "Bearer $token")
            .header("X-Lt-User", "e2e")
            .header("X-Lt-Groups", "quoting")
            .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
            .GET()
            .build()
        val client = HttpClient.newHttpClient()
        val deadline = System.nanoTime() + Duration.ofSeconds(REPLICATION_TIMEOUT_SECONDS).toNanos()
        var body = ""
        while (System.nanoTime() < deadline) {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            body = response.body()
            if (response.statusCode() == HTTP_OK && body.contains(name)) return body
            Thread.sleep(POLL_MILLIS)
        }
        return body
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_TIMEOUT_SECONDS = 10L
        const val REPLICATION_TIMEOUT_SECONDS = 60L
        const val POLL_MILLIS = 500L
    }
}
