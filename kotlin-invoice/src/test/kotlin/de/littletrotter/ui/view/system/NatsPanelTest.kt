package de.littletrotter.ui.view.system

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasText
import com.vaadin.flow.component.button.Button
import de.littletrotter.application.system.JobPipelineStatus
import de.littletrotter.application.system.JobPipelineStatusService
import de.littletrotter.application.system.NatsStatusService
import de.littletrotter.application.system.PipelineCardState
import de.littletrotter.ui.component.DataTable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NatsPanelTest {

    private fun natsStatus(
        subjects: List<NatsStatusService.SubjectStatus> = listOf(NatsStatusService.SubjectStatus("document-jobs", 100, 0)),
        error: String? = null,
    ): NatsStatusService = object : NatsStatusService {
        override fun currentStatus(): NatsStatusService.NatsStatus = NatsStatusService.NatsStatus(
            brokerUp = error == null,
            serverUrl = "nats://nats:4222",
            sentMessages = 42,
            totalLag = 3,
            subjects = subjects,
            error = error,
        )
    }

    private fun pipelineStatus(
        createdCount: Long = 0,
        transportReachable: Boolean = true,
        dlqMessages: Long = 0,
        dlqBytes: Long = 0,
    ): JobPipelineStatusService = object : JobPipelineStatusService {
        override fun currentStatus(): JobPipelineStatus = JobPipelineStatus(
            created = JobPipelineStatus.CreatedStage(createdCount, null, PipelineCardState.OK),
            inProgress = JobPipelineStatus.InProgressStage(0, false, PipelineCardState.OK),
            completed = JobPipelineStatus.CompletedStage(0, 0),
            failed = JobPipelineStatus.FailedStage(0, dlqMessages, dlqBytes, if (dlqMessages > 0) PipelineCardState.ERROR else PipelineCardState.OK),
            transport = JobPipelineStatus.TransportLine(reachable = transportReachable),
        )
    }

    @Test
    fun `nats panel renders subject rows and the pipeline row when nats status is healthy`() {
        val panel = NatsPanel(
            natsStatus(
                subjects = listOf(
                    NatsStatusService.SubjectStatus("document-jobs", 100, 0),
                    NatsStatusService.SubjectStatus("document-job-results", 90, 2),
                ),
            ),
            pipelineStatus(createdCount = 7),
        )

        val texts = texts(panel)
        assertTrue(texts.any { it == "7" }, "Should show the created-stage job count")
        assertTrue(flatten(panel).any { it is PipelineRow })

        val table = flatten(panel).filterIsInstance<DataTable<*>>().single()
        assertEquals(2, table.dataProvider.size(com.vaadin.flow.data.provider.Query()))
    }

    @Test
    fun `nats panel shows unreachable wording on the transport line when the message broker is unreachable`() {
        val panel = NatsPanel(natsStatus(), pipelineStatus(transportReachable = false))

        val texts = texts(panel)
        assertTrue(texts.any { it.contains("nicht erreichbar") }, "Should show unreachable wording for the transport line")
    }

    // ── The DLQ figures live on the Fehlgeschlagen pipeline card's
    // sub-line, sourced from JobPipelineStatus rather than NatsStatus. ──────────────────────────

    @Test
    fun `nats panel shows the DLQ message count and a human-readable byte size when the DLQ is empty`() {
        val panel = NatsPanel(natsStatus(), pipelineStatus(dlqMessages = 0, dlqBytes = 0))

        val texts = texts(panel)
        assertTrue(texts.any { it.contains("DLQ: 0") }, "DLQ message count must render as 0")
        assertTrue(texts.any { it.contains("0 B") }, "DLQ byte size must render as 0 B")
    }

    @Test
    fun `nats panel shows a non-empty DLQ's message count and byte size in KB or MB`() {
        val panel = NatsPanel(natsStatus(), pipelineStatus(dlqMessages = 5, dlqBytes = 2 * 1024 * 1024))

        val texts = texts(panel)
        assertTrue(texts.any { it.contains("DLQ: 5") }, "DLQ message count must render as 5")
        assertTrue(texts.any { it.contains("2,0 MB") || it.contains("2.0 MB") }, "DLQ byte size must render in MB")
    }

    @Test
    fun `nats panel renders error message instead of subjects when status contains error`() {
        val panel = NatsPanel(natsStatus(subjects = emptyList(), error = "server unreachable"), pipelineStatus())

        val texts = texts(panel)
        assertTrue(texts.any { it.contains("server unreachable") })
        assertTrue(flatten(panel).filterIsInstance<DataTable<*>>().isEmpty())
    }

    private fun texts(component: Component): List<String> =
        flatten(component).filterIsInstance<HasText>().map { it.text }.filter { it.isNotBlank() }

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    // ── boundary tests ──────────────────────────────────────────────────────────────────────

    @Test
    fun `boundary - the broker being up with a stray error still renders the down status`() {
        val statusWithStrayError = object : NatsStatusService {
            override fun currentStatus(): NatsStatusService.NatsStatus = NatsStatusService.NatsStatus(
                brokerUp = true,
                serverUrl = "nats://nats:4222",
                sentMessages = 0,
                totalLag = 0,
                subjects = emptyList(),
                error = "transient issue",
            )
        }

        val panel = NatsPanel(statusWithStrayError, pipelineStatus())

        // A stray error, even with the broker technically up, routes the error banner — never the
        // subject grid — per rebuild()'s own error.isNullOrBlank() check.
        assertTrue(flatten(panel).filterIsInstance<DataTable<*>>().isEmpty())
        val texts = texts(panel)
        assertTrue(texts.any { it.contains("transient issue") })
    }

    @Test
    fun `boundary - a subject with zero consumer lag renders the ok color, a lagging one the error color`() {
        val okSubject = NatsStatusService.SubjectStatus("document-jobs", 10, 0)
        val laggingSubject = NatsStatusService.SubjectStatus("document-job-results", 5, 3)
        val panel = NatsPanel(natsStatus(subjects = listOf(okSubject, laggingSubject)), pipelineStatus())

        // The lag column's cells are Grid-rendered (component-column renderer) from NatsPanel's
        // own lagCell — calling it directly is equivalent to what the renderer does per row,
        // without walking the grid's column renderer.
        val okSpan = panel.lagCell(okSubject)
        val laggingSpan = panel.lagCell(laggingSubject)

        assertEquals("0", okSpan.text)
        assertTrue(okSpan.hasClassName("lt-text-ok"))
        assertEquals("3", laggingSpan.text)
        assertTrue(laggingSpan.hasClassName("lt-text-error"))
    }

    @Test
    fun `boundary - clicking refresh rebuilds the panel content from a fresh status call`() {
        var calls = 0
        val countingStatus = object : NatsStatusService {
            override fun currentStatus(): NatsStatusService.NatsStatus {
                calls++
                return NatsStatusService.NatsStatus(
                    brokerUp = true,
                    serverUrl = "nats://nats:4222",
                    sentMessages = 0,
                    totalLag = 0,
                    subjects = emptyList(),
                    error = null,
                )
            }
        }
        val panel = NatsPanel(countingStatus, pipelineStatus())
        assertEquals(1, calls)

        val refreshButton = flatten(panel).filterIsInstance<Button>().first { it.text.isNotBlank() }
        refreshButton.click()

        assertEquals(2, calls, "the refresh button must re-fetch the NATS status, not just repaint")
    }

    @Test
    fun `boundary - an empty subjects list with no error renders the grid with zero rows`() {
        val panel = NatsPanel(natsStatus(subjects = emptyList()), pipelineStatus())

        val table = flatten(panel).filterIsInstance<DataTable<*>>().single()
        assertEquals(0, table.dataProvider.size(com.vaadin.flow.data.provider.Query()))
    }
}
