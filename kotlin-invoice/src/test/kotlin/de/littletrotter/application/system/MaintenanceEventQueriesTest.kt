package de.littletrotter.application.system

import de.littletrotter.application.system.port.inbound.MaintenanceEventView
import de.littletrotter.domain.system.DatabaseMaintenanceEvent
import de.littletrotter.domain.system.DatabaseMaintenanceStatus
import de.littletrotter.domain.system.DatabaseMaintenanceType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import de.littletrotter.application.system.port.outbound.DatabaseMaintenanceService

class MaintenanceEventQueriesTest {

    private val service: DatabaseMaintenanceService = mock()
    private val queries = MaintenanceEventQueries(service)

    @Test
    fun `maps status and details of the latest event`() {
        whenever(service.latestMaintenanceEvent(DatabaseMaintenanceType.RECALCULATE_TOTALS)).thenReturn(
            DatabaseMaintenanceEvent(
                type = DatabaseMaintenanceType.RECALCULATE_TOTALS,
                status = DatabaseMaintenanceStatus.FAILED,
                details = "aborted at invoice 42",
            ),
        )

        assertEquals(
            MaintenanceEventView(DatabaseMaintenanceStatus.FAILED, "aborted at invoice 42"),
            queries.latestEvent(DatabaseMaintenanceType.RECALCULATE_TOTALS),
        )
    }

    @Test
    fun `keeps null details`() {
        whenever(service.latestMaintenanceEvent(DatabaseMaintenanceType.BACKUP)).thenReturn(
            DatabaseMaintenanceEvent(type = DatabaseMaintenanceType.BACKUP, status = DatabaseMaintenanceStatus.PENDING, details = null),
        )

        assertEquals(MaintenanceEventView(DatabaseMaintenanceStatus.PENDING, null), queries.latestEvent(DatabaseMaintenanceType.BACKUP))
    }

    @Test
    fun `no recorded event yields null`() {
        whenever(service.latestMaintenanceEvent(DatabaseMaintenanceType.REINDEX)).thenReturn(null)

        assertNull(queries.latestEvent(DatabaseMaintenanceType.REINDEX))
    }
}
