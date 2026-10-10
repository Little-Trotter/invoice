package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.message.SystemMessageContent
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.message.SystemMessage
import de.littletrotter.domain.message.SystemMessageSeverity
import de.littletrotter.domain.message.SystemMessageSource
import de.littletrotter.domain.system.DatabaseMaintenanceType
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * [SystemMessageRetentionScheduler] had no test at all: it only ever fires via
 * `@Scheduled` in the worker profile (`quarkus.scheduler.enabled=false` otherwise), so nothing
 * exercised `cleanup()` directly. This seeds a message far older than the configured retention
 * window (`littletrotter.message-center.retention-days`, default 90) and proves the scheduler's
 * own call into [de.littletrotter.application.message.MessageCenterService.cleanup] actually
 * deletes it — the `deleted > 0` branch inside [SystemMessageRetentionScheduler.cleanup] that a
 * scheduler run against an empty/fresh table would never hit.
 *
 * [clearDueState] deletes any [de.littletrotter.domain.system.DatabaseMaintenanceEvent]
 * of type [DatabaseMaintenanceType.SYSTEM_MESSAGE_RETENTION] before every test: `database_maintenance_event`
 * is shared across every IT in this module's Surefire/failsafe run, and [SchedulerDueStateIT]
 * (same package) deliberately records a real one. Without this, this class's own `cleanup()` call
 * could see itself as "not due yet" purely because of a recent event another test recorded,
 * skipping the very `messageCenterService.cleanup()` call this test exists to verify — a real
 * regression caught by a live run of the two classes together, not merely theorized.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class SystemMessageRetentionSchedulerIT {

    @Inject
    lateinit var scheduler: SystemMessageRetentionScheduler

    @Inject
    lateinit var em: EntityManager

    @BeforeEach
    fun clearDueState() {
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery("DELETE FROM database_maintenance_event WHERE type = :type")
                .setParameter("type", DatabaseMaintenanceType.SYSTEM_MESSAGE_RETENTION.ordinal)
                .executeUpdate()
        }
    }

    @Test
    fun `cleanup deletes a system message far older than the retention window`() {
        val staleId = QuarkusTransaction.requiringNew().call<Long> {
            val message = SystemMessage.of(
                SystemMessageContent(
                    severity = SystemMessageSeverity.INFO,
                    source = SystemMessageSource.SYSTEM,
                    messageKey = "ap-imp-6.retention-fixture",
                    args = emptyList(),
                    resolvedText = "AP-IMP-6 retention fixture",
                ),
                actor = "system",
                occurredAt = OffsetDateTime.now().minusDays(365),
            )
            em.persist(message)
            em.flush()
            message.id!!
        }

        QuarkusTransaction.requiringNew().run { scheduler.cleanup() }

        assertNull(em.find(SystemMessage::class.java, staleId), "a message far older than the retention window must be gone after cleanup")
    }
}
