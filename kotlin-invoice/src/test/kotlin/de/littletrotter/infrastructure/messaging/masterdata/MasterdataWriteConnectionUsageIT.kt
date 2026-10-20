package de.littletrotter.infrastructure.messaging.masterdata

import de.littletrotter.application.NatsTestResource
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.dto.RecipientDraft
import de.littletrotter.application.masterdata.RecipientAddressInput
import de.littletrotter.application.masterdata.port.inbound.ManageRecipientUseCase
import de.littletrotter.domain.masterdata.ReverseChargePreference
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.shared.tax.CustomerType
import io.agroal.api.AgroalDataSource
import io.agroal.api.AgroalPoolInterceptor
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.postgresql.PGConnection
import java.sql.Connection
import java.util.concurrent.CopyOnWriteArrayList
import java.util.UUID

/**
 * Records every pool acquisition with the Postgres backend it resolves to, plus thread and first
 * application frame. Several leases inside one transaction may hand out the same physical
 * connection, so the backend id, not the lease count, identifies the connections in use.
 */
private class AcquisitionRecorder : AgroalPoolInterceptor {
    val acquisitions = CopyOnWriteArrayList<Triple<String, Int, String>>()

    override fun onConnectionAcquire(connection: Connection) {
        val frame = Throwable().stackTrace.firstOrNull {
            it.className.startsWith("de.littletrotter") && !it.className.contains("AcquisitionRecorder")
        }
        val backend = if (connection.isWrapperFor(PGConnection::class.java)) {
            connection.unwrap(PGConnection::class.java).backendPID
        } else {
            -1
        }
        acquisitions += Triple(Thread.currentThread().name, backend, frame?.toString() ?: "no application frame")
    }
}

/**
 * A master data write must need one physical connection: the domain change and the outbox row share
 * the transaction's connection. A second connection per write halves the pool's capacity under
 * concurrent users and lets the pool starve.
 */
@QuarkusTest
@TestProfile(QuoteEnabledProfile::class)
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(NatsTestResource::class)
class MasterdataWriteConnectionUsageIT {

    @Inject lateinit var recipients: ManageRecipientUseCase
    @Inject lateinit var dataSource: AgroalDataSource
    @Inject lateinit var em: EntityManager

    private fun draft() = RecipientDraft(
        id = null, version = null, customerNumber = null, name = "Pool ${UUID.randomUUID()}",
        vatId = "DE136695976", vatIdValidationNote = null, routingId = null, email = "pool@example.org", phone = null,
        contactPerson = null, iban = null, customerType = CustomerType.BUSINESS,
        invoiceFormat = InvoiceFormat.GERMAN, documentLanguage = "de",
        reverseChargePreference = ReverseChargePreference.ASK,
        buyerIdentifierValue = null, buyerIdentifierSchemeId = null,
        legalRegistrationValue = null, legalRegistrationSchemeId = null,
        b2bProfileId = null, partyPairs = emptyMap(),
        addresses = RecipientAddressInput("Weg 1", "10115", "Berlin"),
        proposedCustomerNumber = "KD-${UUID.randomUUID().toString().take(8)}",
        vatIdValidatedByUser = false, pendingVatIdVerification = null,
    )

    private fun scalar(sql: String, id: Long): String = QuarkusTransaction.requiringNew().call {
        em.createNativeQuery(sql).setParameter("i", id).singleResult.toString()
    }

    @Test
    fun `saving a recipient uses exactly one physical connection in one database transaction`() {
        recipients.saveRecipientWithAddresses(draft())
        val recorder = AcquisitionRecorder()
        dataSource.setPoolInterceptors(listOf(recorder))
        val testThread = Thread.currentThread().name

        // The surrounding transaction makes the commit-time outbox append part of the measured
        // window and exposes the transaction's id. The outbox row's own xmin cannot be compared:
        // the relay updates the row to PUBLISHED within milliseconds, which assigns a new xmin.
        val (saved, transactionId) = try {
            QuarkusTransaction.requiringNew().call {
                val result = recipients.saveRecipientWithAddresses(draft())
                result to em.createNativeQuery("SELECT txid_current()").singleResult.toString()
            }
        } finally {
            dataSource.setPoolInterceptors(emptyList())
        }

        val onTestThread = recorder.acquisitions.filter { it.first == testThread }
        assertEquals(
            1, onTestThread.map { it.second }.distinct().size,
            "backends on $testThread; all recorded: ${recorder.acquisitions.joinToString("\n  ", prefix = "\n  ")}",
        )
        assertEquals(
            transactionId, scalar("SELECT xmin::text FROM recipient WHERE id = :i", saved.id),
            "the recipient row must be written by the surrounding transaction",
        )
        assertEquals(
            "1",
            scalar("SELECT count(*) FROM outbox WHERE aggregate_type = 'customer' AND aggregate_id = CAST(:i AS text)", saved.id),
            "the outbox row must be committed with the recipient",
        )
    }
}
