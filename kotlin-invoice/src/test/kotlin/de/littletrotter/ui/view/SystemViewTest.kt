package de.littletrotter.ui.view

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasText
import de.littletrotter.application.ApplicationIdentity
import de.littletrotter.application.invoice.AuditJournalQueryService
import de.littletrotter.application.system.port.outbound.DatabaseMaintenanceService
import de.littletrotter.application.dto.JobPipelineStatus
import de.littletrotter.application.system.port.inbound.JobPipelineStatusQueryPort
import de.littletrotter.application.system.MaintenanceEventQueries
import de.littletrotter.application.system.port.inbound.NatsStatusQueryPort
import de.littletrotter.application.dto.PipelineCardState
import de.littletrotter.application.system.port.inbound.SystemStatusQueryPort
import de.littletrotter.application.system.port.inbound.ValkeyStatsQueryPort
import de.littletrotter.application.dto.ValkeyStatsStatus
import de.littletrotter.ui.view.system.BackupStatusLoader
import de.littletrotter.ui.view.system.CachePanel
import de.littletrotter.ui.view.system.JournalPanel
import de.littletrotter.ui.view.system.NatsPanel
import de.littletrotter.ui.view.system.SbomPanel
import de.littletrotter.ui.view.system.StatusPanel
import de.littletrotter.ui.view.system.StatusPanelMaintenance
import de.littletrotter.ui.view.system.SystemView
import java.util.Optional
import java.util.stream.Stream
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import de.littletrotter.application.dto.ServiceStatus
import de.littletrotter.application.dto.SubjectStatus
import de.littletrotter.application.dto.NatsStatus
import de.littletrotter.application.system.CacheStatusService
import de.littletrotter.application.system.QueueDatabaseMaintenance

class SystemViewTest {

    @Test
    fun `system view renders docker dependencies`() {
        val dockerStatusService = object : SystemStatusQueryPort {
            override fun listDependencies(): List<ServiceStatus> = listOf(
                ServiceStatus("postgresql", "Postgres", "postgres:5432", true),
                ServiceStatus("rustfs", "RustFS", "rustfs:9000", true),
                ServiceStatus("nats", "NATS Bus", "nats:4222", true),
                ServiceStatus("xpdf", "PDF Renderer", "xpdf:8081", true),
                ServiceStatus("invoice", "Invoice App", "localhost:8080", true),
                ServiceStatus("worker", "Background Worker", "localhost:8080", true),
                ServiceStatus("omlx", "oMLX", "omlx:8020", false),
            )
        }

        val stubNatsStatusService = object : NatsStatusQueryPort {
            override fun currentStatus(): NatsStatus = NatsStatus(
                brokerUp = true,
                serverUrl = "nats://nats:4222",
                sentMessages = 42,
                totalLag = 3,
                subjects = listOf(
                    SubjectStatus("document-jobs", 50, 2),
                    SubjectStatus("document-job-results", 48, 1),
                ),
            )
        }

        val stubCacheStatusService = CacheStatusService(
            "valkey",
            6379,
            "",
        )

        val stubJobPipelineStatusService = object : JobPipelineStatusQueryPort {
            override fun currentStatus(): JobPipelineStatus = JobPipelineStatus(
                created = JobPipelineStatus.CreatedStage(0, null, PipelineCardState.OK),
                inProgress = JobPipelineStatus.InProgressStage(0, false, PipelineCardState.OK),
                completed = JobPipelineStatus.CompletedStage(0, 0),
                failed = JobPipelineStatus.FailedStage(0, 0, 0, PipelineCardState.OK),
                transport = JobPipelineStatus.TransportLine(reachable = true),
            )
        }

        val stubValkeyStatsService = object : ValkeyStatsQueryPort {
            override fun getStats(): ValkeyStatsStatus =
                ValkeyStatsStatus(true, emptyList(), 0)
        }

        val view = SystemView(
            statusPanel = StatusPanel(
                dockerStatusService, ApplicationIdentity("test-version"), "test",
                StatusPanelMaintenance(QueueDatabaseMaintenance(mock<DatabaseMaintenanceService>()), MaintenanceEventQueries(mock<DatabaseMaintenanceService>()), true),
                de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z")),
                BackupStatusLoader(Optional.empty()),
            ),
            natsPanel = NatsPanel(stubNatsStatusService, stubJobPipelineStatusService),
            cachePanel = CachePanel(stubCacheStatusService, stubValkeyStatsService),
            sbomPanel = SbomPanel { emptyList() },
            journalPanel = JournalPanel(mock<AuditJournalQueryService>()),
        )

        val texts = flatten(view)
            .filter { it is HasText }
            .map { it as HasText }
            .map { it.text }
            .filter { !it.isNullOrBlank() }
            .toList()

        // Service names may render differently depending on i18n ordering; cover common variants.
        assertTrue(texts.any { it.contains("Hauptdatenbank", ignoreCase = true) || it.contains("Postgre", ignoreCase = true) })
        assertTrue(texts.any { it.contains("postgres:5432") })
        assertTrue(texts.any { it.contains("RustFS", ignoreCase = true) })
        assertTrue(texts.any { it.contains("rustfs:9000") })

        assertTrue(texts.any { it.contains("Invoice-App", ignoreCase = true) || it.contains("Rechnungs-Frontend", ignoreCase = true) })
        assertTrue(texts.any { it.contains("PDF-Konvertierung", ignoreCase = true) || it.contains("XPDF", ignoreCase = true) })

        // SBOM tab is shown in the navigation tab titles
        assertTrue(texts.any { it.contains("SBOM", ignoreCase = true) })
        // NATS tab + pipeline row are shown
        assertTrue(texts.any { it.contains("NATS", ignoreCase = true) })
        assertTrue(texts.any { it.contains("nats://nats:4222") })
    }

    private fun flatten(component: Component): Stream<Component> =
        Stream.concat(Stream.of(component), component.children.flatMap { flatten(it) })
}
