package de.littletrotter.e2e.quote

import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.Email
import de.littletrotter.e2e.ContainerFaults
import de.littletrotter.e2e.NatsTestResource
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.QuoteAppTestResource
import de.littletrotter.e2e.QuoteModuleTestProfile
import de.littletrotter.e2e.S3TestResource
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.masterdata.MasterdataStreams
import io.nats.client.Nats
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * A customer is renamed in the invoicing application while the offer module is not running, for
 * example during its deployment. Offers must not be written to a stale customer name afterwards: once
 * the module is back, it works off the master data events it missed from its durable position in the
 * stream, and its customer lookup shows the new name. The module container is stopped and started
 * again by the daemon (same host port, same database). Synthetic data only.
 */
@QuarkusTest
@TestProfile(QuoteModuleTestProfile::class)
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(S3TestResource::class)
@QuarkusTestResource(NatsTestResource::class)
@QuarkusTestResource(QuoteAppTestResource::class, restrictToAnnotatedClass = true)
class QuoteReplicaCatchUpAfterOfferModuleOutageE2EIT {

    @Inject
    lateinit var recipientRepository: RecipientRepository

    private var recipientId: Long? = null

    @AfterEach
    fun cleanup() {
        recipientId?.let { id -> QuarkusTransaction.requiringNew().run { recipientRepository.delete(id) } }
    }

    @Test
    fun `a customer renamed while the offer module is stopped shows the new name once the module is back`() {
        val token = System.nanoTime()
        val oldName = "Replikat $token GmbH"
        val newName = "Replikat $token AG"
        recipientId = createCustomer(oldName)
        assertTrue(awaitLookup(oldName, present = true).contains(oldName), "the customer is replicated before the outage")
        val quoteAppId = ContainerFaults.containerId(ContainerFaults.QUOTE_APP_CONTAINER_ID_PROPERTY)
        val streamEndBefore = lastStreamSequence()

        ContainerFaults.stop(quoteAppId)
        try {
            rename(requireNotNull(recipientId), newName)
            awaitCondition("the rename reaches the master data stream while the module is down") { lastStreamSequence() > streamEndBefore }
        } finally {
            ContainerFaults.start(quoteAppId)
        }
        awaitReady()

        val lookup = awaitLookup(newName, present = true)
        assertTrue(lookup.contains(newName), "the module's customer lookup shows the new name: $lookup")
        assertFalse(awaitLookup(oldName, present = false).contains(oldName), "the old name is gone from the replica")
    }

    private fun createCustomer(name: String): Long {
        val recipient = Recipient().apply {
            this.name = name
            customerNumber = CustomerNumber.ofNullable("KD-R-${System.nanoTime() % CUSTOMER_NUMBER_MODULUS}")
            email = Email.ofNullable("kunde@example.org")
            addAddress(Address.forRecipient(this, Address.AddressType.BILLING, "Teststrasse 1", "12345", "Musterstadt", Country.DE, true))
        }
        recipient.ensureShortHash()
        return QuarkusTransaction.requiringNew().call { recipientRepository.save(recipient) }.id!!
    }

    private fun rename(id: Long, newName: String) {
        QuarkusTransaction.requiringNew().run {
            val recipient = checkNotNull(recipientRepository.findById(id)) { "customer $id vanished" }
            recipient.name = newName
            recipientRepository.save(recipient)
        }
    }

    private fun lastStreamSequence(): Long =
        Nats.connect(config("nats.url")).use { it.jetStreamManagement().getStreamInfo(MasterdataStreams.MASTERDATA).streamState.lastSequence }

    /** The module answers again once its readiness check passes; its database and broker connection are re-established by then. */
    private fun awaitReady() {
        val client = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder(URI.create("${config(QuoteAppTestResource.BASE_URL_PROPERTY)}/q/health/ready"))
            .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS)).GET().build()
        awaitCondition("the offer module is ready again") {
            try {
                client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == HTTP_OK
            } catch (_: java.io.IOException) {
                false
            }
        }
    }

    /** Polls the lookup until [name] is found (or, with [present] false, not found any more); replication is asynchronous. */
    private fun awaitLookup(name: String, present: Boolean): String {
        val request = HttpRequest.newBuilder(
            URI.create("${config(QuoteAppTestResource.BASE_URL_PROPERTY)}/quote/api/lookup/customers?limit=5&text=" + URLEncoder.encode(name, Charsets.UTF_8)),
        )
            .header("Authorization", "Bearer ${config(QuoteAppTestResource.TOKEN_PROPERTY)}")
            .header("X-Lt-User", "e2e")
            .header("X-Lt-Groups", "quoting")
            .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
            .GET()
            .build()
        val client = HttpClient.newHttpClient()
        var body = ""
        awaitCondition("lookup of '$name' ${if (present) "finds" else "no longer finds"} the customer") {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            body = response.body()
            response.statusCode() == HTTP_OK && body.contains(name) == present
        }
        return body
    }

    private fun awaitCondition(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(WAIT_SECONDS).toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_MILLIS)
        }
        error("timed out after $WAIT_SECONDS s: $what")
    }

    private fun config(name: String): String = ConfigProvider.getConfig().getValue(name, String::class.java)

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_TIMEOUT_SECONDS = 10L
        const val WAIT_SECONDS = 90L
        const val POLL_MILLIS = 500L
        const val CUSTOMER_NUMBER_MODULUS = 100_000
    }
}
