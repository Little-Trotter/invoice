package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.message.MessageCenterService
import de.littletrotter.application.system.port.outbound.DatabaseMaintenanceService
import de.littletrotter.domain.system.DatabaseMaintenanceEvent
import de.littletrotter.domain.system.DatabaseMaintenanceStatus
import de.littletrotter.domain.system.DatabaseMaintenanceType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * [SystemMessageRetentionScheduler.isDue] against a controlled [Clock], proving the
 * fresh-deployment / restart-mid-interval / restart-after-elapsed-interval cases by an
 * invocation-count assertion on [MessageCenterService.cleanup] rather than by reading the
 * `@Scheduled` annotation. Also proves the new due-state record itself gets written on a run.
 */
class SystemMessageRetentionSchedulerTest {

    private val messageCenterService = mock<MessageCenterService>()
    private val maintenanceService = mock<DatabaseMaintenanceService>()

    private val now: Instant = Instant.parse("2026-09-17T10:00:00Z")

    private fun schedulerAt(instant: Instant) =
        SystemMessageRetentionScheduler(messageCenterService, maintenanceService, de.littletrotter.shared.time.ClockTimeSource.fixedAt(instant))

    private fun eventAt(instant: Instant) = DatabaseMaintenanceEvent(
        createdAt = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC),
        type = DatabaseMaintenanceType.SYSTEM_MESSAGE_RETENTION,
        status = DatabaseMaintenanceStatus.SUCCESS,
    )

    @Test
    fun `a fresh deployment with no prior event runs immediately`() {
        whenever(maintenanceService.latestMaintenanceEvent(DatabaseMaintenanceType.SYSTEM_MESSAGE_RETENTION)).thenReturn(null)
        whenever(messageCenterService.cleanup()).thenReturn(0)

        schedulerAt(now).cleanup()

        verify(messageCenterService).cleanup()
    }

    @Test
    fun `a restart mid-interval skips the run`() {
        whenever(maintenanceService.latestMaintenanceEvent(DatabaseMaintenanceType.SYSTEM_MESSAGE_RETENTION))
            .thenReturn(eventAt(now.minus(Duration.ofHours(2))))

        schedulerAt(now).cleanup()

        verify(messageCenterService, never()).cleanup()
    }

    @Test
    fun `a restart after the interval has already elapsed runs`() {
        whenever(maintenanceService.latestMaintenanceEvent(DatabaseMaintenanceType.SYSTEM_MESSAGE_RETENTION))
            .thenReturn(eventAt(now.minus(Duration.ofHours(25))))
        whenever(messageCenterService.cleanup()).thenReturn(0)

        schedulerAt(now).cleanup()

        verify(messageCenterService).cleanup()
    }

    @Test
    fun `isDue is false exactly at the boundary and true one second past it`() {
        whenever(maintenanceService.latestMaintenanceEvent(DatabaseMaintenanceType.SYSTEM_MESSAGE_RETENTION))
            .thenReturn(eventAt(now.minus(SystemMessageRetentionScheduler.DUE_INTERVAL).plusSeconds(1)))
        assertFalse(schedulerAt(now).isDue(), "one second short of the interval must not be due yet")

        whenever(maintenanceService.latestMaintenanceEvent(DatabaseMaintenanceType.SYSTEM_MESSAGE_RETENTION))
            .thenReturn(eventAt(now.minus(SystemMessageRetentionScheduler.DUE_INTERVAL)))
        assertTrue(schedulerAt(now).isDue(), "exactly the interval since the last run must already be due")
    }

    @Test
    fun `a due run records a new SYSTEM_MESSAGE_RETENTION event stamped from the injected clock`() {
        whenever(maintenanceService.latestMaintenanceEvent(DatabaseMaintenanceType.SYSTEM_MESSAGE_RETENTION)).thenReturn(null)
        whenever(messageCenterService.cleanup()).thenReturn(4)

        schedulerAt(now).cleanup()

        val captor = argumentCaptor<DatabaseMaintenanceEvent>()
        verify(maintenanceService).recordMaintenanceEvent(captor.capture())
        val recorded = captor.firstValue
        assertTrue(recorded.type == DatabaseMaintenanceType.SYSTEM_MESSAGE_RETENTION)
        assertTrue(recorded.status == DatabaseMaintenanceStatus.SUCCESS)
        assertTrue(recorded.createdAt.toInstant() == now, "the recorded event must be stamped from the scheduler's own clock, not wall-clock time")
    }

    @Test
    fun `a skipped tick never records a new due-state event`() {
        whenever(maintenanceService.latestMaintenanceEvent(DatabaseMaintenanceType.SYSTEM_MESSAGE_RETENTION))
            .thenReturn(eventAt(now.minus(Duration.ofHours(2))))

        schedulerAt(now).cleanup()

        verify(maintenanceService, never()).recordMaintenanceEvent(org.mockito.kotlin.any())
    }
}
