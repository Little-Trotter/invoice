package de.littletrotter.application.system

import de.littletrotter.application.system.port.outbound.MaintenanceDrainLease
import de.littletrotter.application.system.port.outbound.MaintenanceDrainLockLostException
import de.littletrotter.application.system.port.outbound.MaintenanceQueuePort
import de.littletrotter.domain.system.DatabaseMaintenanceType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.sql.SQLException

/**
 * [MaintenanceQueuePort]'s own file (the port interface, its sealed [MaintenanceQueuePort.LeaseAttempt]
 * and [MaintenanceDrainLease] result types, and [MaintenanceDrainLockLostException]) had no direct test:
 * every prior reference only exercised it indirectly through a mocked port ([MaintenanceQueueProcessorTest])
 * or the real Postgres adapter ([PostgresMaintenanceQueueAdapterIT]), neither of which ever constructs
 * [MaintenanceDrainLockLostException] with an explicit cause (that only happens on a genuine lost advisory
 * lock, not reproducible without breaking a live PostgreSQL connection).
 */
class MaintenanceQueuePortTest {

    @Test
    fun `happy path - LeaseAttempt Acquired carries its own result through the sealed hierarchy`() {
        val acquired: MaintenanceQueuePort.LeaseAttempt<String> = MaintenanceQueuePort.LeaseAttempt.Acquired("done")

        assertEquals("done", (acquired as MaintenanceQueuePort.LeaseAttempt.Acquired).result)
    }

    @Test
    fun `boundary - LeaseAttempt Busy carries no result and is a singleton`() {
        val busy: MaintenanceQueuePort.LeaseAttempt<Nothing> = MaintenanceQueuePort.LeaseAttempt.Busy

        assertSame(MaintenanceQueuePort.LeaseAttempt.Busy, busy)
    }

    @Test
    fun `boundary - MaintenanceDrainLockLostException without a cause reports null cause`() {
        val ex = MaintenanceDrainLockLostException("lock lost")

        assertEquals("lock lost", ex.message)
        assertNull(ex.cause)
    }

    @Test
    fun `boundary - MaintenanceDrainLockLostException wraps the original SQLException as its cause`() {
        val original = SQLException("connection closed")

        val ex = MaintenanceDrainLockLostException("lock lost verifying ownership", original)

        assertEquals("lock lost verifying ownership", ex.message)
        assertSame(original, ex.cause)
    }

    @Test
    fun `boundary - RecoveryResult and ClaimedMaintenanceJob carry their fields unchanged`() {
        val recovery = MaintenanceDrainLease.RecoveryResult(terminalizedVersionedClaims = 2, terminalizedHistoricalClaims = 3)
        val claimed = MaintenanceDrainLease.ClaimedMaintenanceJob(
            eventId = 9L,
            type = DatabaseMaintenanceType.RECALCULATE_TOTALS,
            claimToken = 42L,
        )

        assertEquals(2, recovery.terminalizedVersionedClaims)
        assertEquals(3, recovery.terminalizedHistoricalClaims)
        assertEquals(9L, claimed.eventId)
        assertEquals(DatabaseMaintenanceType.RECALCULATE_TOTALS, claimed.type)
        assertEquals(42L, claimed.claimToken)
    }
}
