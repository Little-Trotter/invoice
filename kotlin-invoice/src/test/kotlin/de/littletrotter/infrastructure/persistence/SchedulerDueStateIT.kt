package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.system.DatabaseMaintenanceService
import de.littletrotter.domain.system.DatabaseMaintenanceEvent
import de.littletrotter.domain.system.DatabaseMaintenanceStatus
import de.littletrotter.domain.system.DatabaseMaintenanceType
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * [ChainVerificationScheduler.isDue]/[SystemMessageRetentionScheduler.isDue] against a
 * REAL Postgres (via [PostgresTestResource]), proving the due-state read/write round trip through
 * the actual `database_maintenance_event` table and its `chk_dbm_type` CHECK constraint (bumped to
 * accommodate the new [DatabaseMaintenanceType.SYSTEM_MESSAGE_RETENTION] ordinal), not merely
 * against a mocked [DatabaseMaintenanceService] the way [ChainVerificationSchedulerTest]/
 * [SystemMessageRetentionSchedulerTest] already do.
 *
 * [database_maintenance_event] is a table shared by every IT in this module — [clearOwnTypes] in
 * [BeforeEach] deletes only the two [DatabaseMaintenanceType] ordinals this class cares about
 * (`CHAIN_VERIFICATION`, `SYSTEM_MESSAGE_RETENTION`) before every test, so a prior test's rows
 * (e.g. [ChainVerificationServiceIT]'s own `CHAIN_VERIFICATION` events) never leak into this
 * class's "latest event" reads. Surefire runs this module's tests sequentially within one fork
 * (no parallel IT execution configured), so nothing else in this run writes those two types
 * between this cleanup and each test's own assertions.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class SchedulerDueStateIT {

    @Inject
    lateinit var chainVerificationScheduler: ChainVerificationScheduler

    @Inject
    lateinit var systemMessageRetentionScheduler: SystemMessageRetentionScheduler

    @Inject
    lateinit var maintenanceService: DatabaseMaintenanceService

    @Inject
    lateinit var em: EntityManager

    @BeforeEach
    fun clearOwnTypes() {
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery(
                "DELETE FROM database_maintenance_event WHERE type IN (:chainVerification, :systemMessageRetention)",
            )
                .setParameter("chainVerification", ordinalOf(DatabaseMaintenanceType.CHAIN_VERIFICATION))
                .setParameter("systemMessageRetention", ordinalOf(DatabaseMaintenanceType.SYSTEM_MESSAGE_RETENTION))
                .executeUpdate()
        }
    }

    private fun ordinalOf(type: DatabaseMaintenanceType): Int = type.ordinal

    private fun seedEvent(type: DatabaseMaintenanceType, createdAt: OffsetDateTime) {
        QuarkusTransaction.requiringNew().run {
            em.persist(
                DatabaseMaintenanceEvent(
                    createdAt = createdAt,
                    type = type,
                    status = DatabaseMaintenanceStatus.SUCCESS,
                ),
            )
        }
    }

    // ─── ChainVerificationScheduler ────────────────────────────────────────────────────

    @Test
    fun `CHAIN_VERIFICATION - a fresh deployment with no recorded event is due`() {
        assertTrue(
            maintenanceService.latestMaintenanceEvent(DatabaseMaintenanceType.CHAIN_VERIFICATION) == null,
            "cleared before this test — no CHAIN_VERIFICATION event must exist",
        )

        assertTrue(chainVerificationScheduler.isDue())
    }

    @Test
    fun `CHAIN_VERIFICATION - a real event from 2 hours ago is not yet due`() {
        seedEvent(DatabaseMaintenanceType.CHAIN_VERIFICATION, OffsetDateTime.now().minusHours(2))

        assertFalse(chainVerificationScheduler.isDue())
    }

    @Test
    fun `CHAIN_VERIFICATION - a real event from 25 hours ago is due again`() {
        seedEvent(DatabaseMaintenanceType.CHAIN_VERIFICATION, OffsetDateTime.now().minusHours(25))

        assertTrue(chainVerificationScheduler.isDue())
    }

    // ─── SystemMessageRetentionScheduler ───────────────────────────────────────────────

    @Test
    fun `SYSTEM_MESSAGE_RETENTION - a fresh deployment with no recorded event is due`() {
        assertTrue(
            maintenanceService.latestMaintenanceEvent(DatabaseMaintenanceType.SYSTEM_MESSAGE_RETENTION) == null,
            "cleared before this test — no SYSTEM_MESSAGE_RETENTION event must exist",
        )

        assertTrue(systemMessageRetentionScheduler.isDue())
    }

    @Test
    fun `SYSTEM_MESSAGE_RETENTION - a real event from 2 hours ago is not yet due`() {
        seedEvent(DatabaseMaintenanceType.SYSTEM_MESSAGE_RETENTION, OffsetDateTime.now().minusHours(2))

        assertFalse(systemMessageRetentionScheduler.isDue())
    }

    @Test
    fun `SYSTEM_MESSAGE_RETENTION - a real event from 25 hours ago is due again`() {
        seedEvent(DatabaseMaintenanceType.SYSTEM_MESSAGE_RETENTION, OffsetDateTime.now().minusHours(25))

        assertTrue(systemMessageRetentionScheduler.isDue())
    }

    // ─── SystemMessageRetentionScheduler.cleanup() actually persists the new event type ──

    @Test
    fun `SYSTEM_MESSAGE_RETENTION - a due cleanup run persists a new event through the real CHECK constraint`() {
        QuarkusTransaction.requiringNew().run { systemMessageRetentionScheduler.cleanup() }

        val recorded = maintenanceService.latestMaintenanceEvent(DatabaseMaintenanceType.SYSTEM_MESSAGE_RETENTION)
        assertTrue(recorded != null, "cleanup() must persist a SYSTEM_MESSAGE_RETENTION event when due")
        assertTrue(recorded?.status == DatabaseMaintenanceStatus.SUCCESS)
    }
}
