package de.littletrotter.infrastructure.messaging.masterdata

import de.littletrotter.application.NatsTestResource
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.domain.value.CustomerNumber
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/** Without the offer module (`quote.enabled=false`, the default) master data writes leave the outbox alone. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(NatsTestResource::class)
class MasterdataEventsDisabledIT {

    @Inject lateinit var em: EntityManager
    @Inject lateinit var recipients: RecipientRepository

    @Test
    fun `a master data write appends no outbox row`() {
        val id = QuarkusTransaction.requiringNew().call {
            recipients.save(Recipient().apply { customerNumber = CustomerNumber("KD-${UUID.randomUUID().toString().take(8)}"); name = "Off" }).id!!
        }

        val rows = QuarkusTransaction.requiringNew().call {
            em.createNativeQuery("SELECT count(*) FROM outbox WHERE aggregate_id = :i")
                .setParameter("i", id.toString()).singleResult as Number
        }
        assertEquals(0L, rows.toLong())
    }
}
