package de.littletrotter.e2e.masterdata

import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.ReverseChargePreference
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.e2e.PostgresTestResource
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import de.littletrotter.domain.value.CustomerNumber

/**
 * Persistence of the recipient's standing default for how Reverse Charge is decided.
 * Verifies the round-trip save-and-reload for all three enum values, and proves the DB
 * column default (ASK/2) independent of the entity's Kotlin default.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class RecipientReverseChargePreferenceE2EIT {

    @Inject
    lateinit var recipientRepository: RecipientRepository

    private val createdRecipientIds = mutableListOf<Long>()

    @AfterEach
    fun cleanup() {
        createdRecipientIds.forEach { id -> QuarkusTransaction.requiringNew().run { recipientRepository.delete(id) } }
    }

    /**
     * Happy path — a recipient is created with [ReverseChargePreference.ALWAYS], saved,
     * reloaded, and the value survives the round trip unchanged.
     */
    @Test
    fun `a recipient created with ALWAYS round-trips correctly`() {
        val recipient = Recipient().apply {
            customerNumber = CustomerNumber("RCPREF-E2E-001")
            name = "Test Customer ALWAYS"
            reverseChargePreference = ReverseChargePreference.ALWAYS
        }

        QuarkusTransaction.requiringNew().run {
            recipientRepository.save(recipient)
        }
        createdRecipientIds += recipient.id!!

        val reloaded = QuarkusTransaction.requiringNew().call<Recipient?> {
            recipientRepository.findById(recipient.id!!)
        } ?: error("expected the recipient to have been persisted")

        assertEquals(
            ReverseChargePreference.ALWAYS, reloaded.reverseChargePreference,
            "ALWAYS must survive a save-and-reload round trip unchanged",
        )
    }

    /**
     * Border case — same round-trip test for [ReverseChargePreference.NEVER], the opposite
     * extreme from ALWAYS.
     */
    @Test
    fun `a recipient created with NEVER round-trips correctly`() {
        val recipient = Recipient().apply {
            customerNumber = CustomerNumber("RCPREF-E2E-002")
            name = "Test Customer NEVER"
            reverseChargePreference = ReverseChargePreference.NEVER
        }

        QuarkusTransaction.requiringNew().run {
            recipientRepository.save(recipient)
        }
        createdRecipientIds += recipient.id!!

        val reloaded = QuarkusTransaction.requiringNew().call<Recipient?> {
            recipientRepository.findById(recipient.id!!)
        } ?: error("expected the recipient to have been persisted")

        assertEquals(
            ReverseChargePreference.NEVER, reloaded.reverseChargePreference,
            "NEVER must survive a save-and-reload round trip unchanged",
        )
    }

    /**
     * Validation case — a recipient row inserted via raw SQL without ever setting the
     * reverse_charge_preference column in application code proves that the DB column default
     * (NOT NULL DEFAULT 2) is applied, and the entity reloads as [ReverseChargePreference.ASK],
     * independent of any Kotlin class default (both happen to be ASK here, but the test
     * verifies they come from the DB, not from the code).
     */
    @Test
    fun `a recipient inserted via raw SQL without the column set defaults to ASK`() {
        val customerNumber = "RCPREF-E2E-003"

        val jdbcUrl = System.getProperty("quarkus.datasource.jdbc.url")
        val adminUser = System.getProperty("test.postgres.admin-username")
        val adminPassword = System.getProperty("test.postgres.admin-password")

        DriverManager.getConnection(jdbcUrl, adminUser, adminPassword).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO recipient (
                        customer_number, name, created_at, updated_at, document_language) VALUES (
                        '$customerNumber', 'SQL-Inserted Recipient', NOW(), NOW(), 'de'
                    )
                    """.trimIndent()
                )
            }
        }

        val reloaded = QuarkusTransaction.requiringNew().call<Recipient?> {
            recipientRepository.findByCustomerNumber(customerNumber)
        } ?: error("expected the SQL-inserted recipient to exist")

        createdRecipientIds += reloaded.id!!

        assertEquals(
            ReverseChargePreference.ASK, reloaded.reverseChargePreference,
            "a recipient inserted via raw SQL without reverse_charge_preference set must default to ASK (DB DEFAULT 2)",
        )
    }
}
