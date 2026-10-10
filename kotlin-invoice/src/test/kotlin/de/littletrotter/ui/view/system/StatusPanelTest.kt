package de.littletrotter.ui.view.system

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasText
import de.littletrotter.application.ApplicationIdentity
import de.littletrotter.application.system.DatabaseMaintenanceService
import de.littletrotter.application.system.SystemStatusService
import de.littletrotter.domain.system.DatabaseMaintenanceEvent
import de.littletrotter.domain.system.DatabaseMaintenanceType
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StatusPanelTest {

    /** Neither test here clicks a System-tab button -- every mutating method just asserts it is
     *  never called. [latestMaintenanceEvent] IS called during construction (the recalculation
     *  status line reads it unconditionally), so it returns null -- "no event recorded yet" --
     *  rather than throwing. */
    private class UnusedDatabaseMaintenanceService : DatabaseMaintenanceService {
        override fun currentPostgresMaintenance() = throw AssertionError("not exercised by this test")
        override fun recordMaintenanceEvent(event: DatabaseMaintenanceEvent) = throw AssertionError("not exercised by this test")
        override fun latestMaintenanceEvent(type: DatabaseMaintenanceType): DatabaseMaintenanceEvent? = null
        override fun performRecalculateTotals() = throw AssertionError("not exercised by this test")
        override fun performQueueIssuedGenerationJobs() = throw AssertionError("not exercised by this test")
        override fun claimOldestPendingMaintenanceJob() = throw AssertionError("not exercised by this test")
        override fun markMaintenanceJobFailed(eventId: Long, reason: String) = throw AssertionError("not exercised by this test")
        override fun queueIssuedGenerationJobsSweep(details: String) = throw AssertionError("not exercised by this test")
        override fun queueRecalculation(details: String) = throw AssertionError("not exercised by this test")
    }

    @Test
    fun `status panel shows healthy summary when all dependencies are up`() {
        val panel = StatusPanel(
            systemStatusService = stubSystemStatusService(
                SystemStatusService.ServiceStatus("invoice", "Invoice App", "http://localhost:9025", true),
                SystemStatusService.ServiceStatus("nats", "NATS", "nats:4222", true),
            ),
            applicationIdentity = ApplicationIdentity("2.0.0"),
            appVersion = "2.0.0",
            profile = "prod",
            databaseMaintenanceService = UnusedDatabaseMaintenanceService(),
            maintenanceActionsEnabled = true,
            time = de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z")),
        )

        val texts = texts(panel)

        assertTrue(flatten(panel).any { it.classNames.contains("system-service-card") })
        assertTrue(texts.any { it.contains("localhost:9025") })
    }

    @Test
    fun `status panel shows unhealthy summary and instance details when dependency is down`() {
        val panel = StatusPanel(
            systemStatusService = stubSystemStatusService(
                SystemStatusService.ServiceStatus("postgresql", "primary", "postgres:5432", true),
                SystemStatusService.ServiceStatus("valkey", "cache-a", "valkey:6379", false),
            ),
            applicationIdentity = ApplicationIdentity("2.0.0"),
            appVersion = "2.0.0",
            profile = "test",
            databaseMaintenanceService = UnusedDatabaseMaintenanceService(),
            maintenanceActionsEnabled = true,
            time = de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z")),
        )

        val texts = texts(panel)

        assertTrue(flatten(panel).count { it.classNames.contains("system-service-card") } >= 2)
        assertTrue(texts.any { it.contains("primary") })
        assertTrue(texts.any { it.contains("cache-a") })
        assertTrue(texts.any { it.contains("valkey:6379") })
    }

    private fun texts(component: Component): List<String> =
        flatten(component).filterIsInstance<HasText>().map { it.text }.filter { it.isNotBlank() }

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    private fun stubSystemStatusService(vararg statuses: SystemStatusService.ServiceStatus): SystemStatusService =
        object : SystemStatusService {
            override fun listDependencies(): List<SystemStatusService.ServiceStatus> = statuses.toList()
        }
}
