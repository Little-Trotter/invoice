package de.littletrotter.ui.view.system

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasText
import com.vaadin.flow.component.tabs.Tabs
import de.littletrotter.application.ApplicationIdentity
import de.littletrotter.application.invoice.AuditJournalQueryService
import de.littletrotter.application.system.CacheStatusService
import de.littletrotter.application.system.DatabaseMaintenanceService
import de.littletrotter.application.system.JobPipelineStatus
import de.littletrotter.application.system.JobPipelineStatusService
import de.littletrotter.application.system.NatsStatusService
import de.littletrotter.application.system.PipelineCardState
import de.littletrotter.application.system.SystemStatusService
import de.littletrotter.application.system.ValkeyStatsService
import de.littletrotter.application.system.ValkeyStatsStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class SystemViewKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try {
            MockVaadin.tearDown()
        } catch (_: Exception) {
        }
    }

    @Test
    fun `system view shows one tab per SystemTab and only the first panel initially`() {
        val view = createView()

        val tabs = flatten(view).filterIsInstance<Tabs>().first()
        val panels = view.children.filter { it !is Tabs }.toList()

        // Derived from SystemTab so adding or removing a tab never leaves this number stale.
        assertEquals(SystemTab.entries.size.toLong(), tabs.children.count())
        assertTrue(panels[0].isVisible)
        assertTrue(panels.drop(1).all { !it.isVisible })
    }

    @Test
    fun `selecting nats tab toggles visible panel`() {
        val view = createView()
        val tabs = flatten(view).filterIsInstance<Tabs>().first()
        val panels = view.children.filter { it !is Tabs }.toList()

        tabs.selectedIndex = SystemTab.NATS.ordinal

        SystemTab.entries.forEach { tab ->
            assertEquals(
                tab == SystemTab.NATS,
                panels[tab.ordinal].isVisible,
                "only the selected tab's panel may be visible, offender: $tab",
            )
        }
    }

    /** [SystemView.applyJournalLayout] is the one place `maxWidth` is derived from the
     *  selected panel; called both at construction and from the tab-change listener, so this
     *  pins the rule itself rather than only the (today unreachable, since `Tabs` always selects
     *  index 0) "entered directly on the Journal tab" scenario. */
    @Test
    fun `applyJournalLayout widens to 100pct for the journal panel and back to the reading column for any other`() {
        val view = createView()
        val journalPanel = view.children.filter { it !is Tabs }.toList()[SystemTab.JOURNAL.ordinal]
        val statusPanel = view.children.filter { it !is Tabs }.toList()[SystemTab.STATUS.ordinal]
        val method = SystemView::class.java.getDeclaredMethod("applyJournalLayout", Component::class.java).apply { isAccessible = true }

        method.invoke(view, journalPanel)
        assertEquals("100%", view.maxWidth)

        method.invoke(view, statusPanel)
        assertEquals("1400px", view.maxWidth)
    }

    @Test
    fun `status panel exposes app version and profile in system view`() {
        val view = createView(appVersion = "9.9.9-test", profile = "it")

        val texts = flatten(view)
            .filterIsInstance<HasText>()
            .map { it.text }
            .filter { it.isNotBlank() }
            .toList()

        assertTrue(texts.any { it.contains("9.9.9-test") })
        assertTrue(texts.any { it.contains("it") })
    }

    private fun createView(appVersion: String = "1.2.3", profile: String = "test"): SystemView {
        val systemStatusService = object : SystemStatusService {
            override fun listDependencies(): List<SystemStatusService.ServiceStatus> = listOf(
                SystemStatusService.ServiceStatus("postgresql", "Postgres", "postgres:5432", true),
                SystemStatusService.ServiceStatus("nats", "NATS", "nats:4222", true),
                SystemStatusService.ServiceStatus("valkey", "Valkey", "valkey:6379", false),
            )
        }
        val natsStatusService = object : NatsStatusService {
            override fun currentStatus(): NatsStatusService.NatsStatus = NatsStatusService.NatsStatus(
                brokerUp = true,
                serverUrl = "nats://nats:4222",
                sentMessages = 1,
                totalLag = 0,
                subjects = listOf(NatsStatusService.SubjectStatus("document-jobs", 10, 0)),
            )
        }
        val cacheStatusService = CacheStatusService("127.0.0.1", 1, "")
        val jobPipelineStatusService = object : JobPipelineStatusService {
            override fun currentStatus(): JobPipelineStatus = JobPipelineStatus(
                created = JobPipelineStatus.CreatedStage(0, null, PipelineCardState.OK),
                inProgress = JobPipelineStatus.InProgressStage(0, false, PipelineCardState.OK),
                completed = JobPipelineStatus.CompletedStage(0, 0),
                failed = JobPipelineStatus.FailedStage(0, 0, 0, PipelineCardState.OK),
                transport = JobPipelineStatus.TransportLine(reachable = true),
            )
        }
        val valkeyStatsService = object : ValkeyStatsService {
            override fun getStats(): ValkeyStatsStatus =
                ValkeyStatsStatus(true, emptyList(), 0)
        }

        return SystemView(
            StatusPanel(
                systemStatusService, ApplicationIdentity(appVersion), appVersion, profile, mock<DatabaseMaintenanceService>(), true,
                de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z")),
            ),
            NatsPanel(natsStatusService, jobPipelineStatusService),
            CachePanel(cacheStatusService, valkeyStatsService),
            SbomPanel { emptyList() },
            JournalPanel(mock<AuditJournalQueryService>()),
        )
    }

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }
}
