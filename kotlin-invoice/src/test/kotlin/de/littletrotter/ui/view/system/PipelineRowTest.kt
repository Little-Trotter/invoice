package de.littletrotter.ui.view.system

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasText
import com.vaadin.flow.component.icon.Icon
import de.littletrotter.application.dto.JobPipelineStatus
import de.littletrotter.application.dto.PipelineCardState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Karibu-style component test for [PipelineRow] in isolation: card values, the `data-state`
 * attribute (never colour-only) and the German labels/sub-lines every card renders.
 */
class PipelineRowTest {

    private fun status(
        created: JobPipelineStatus.CreatedStage = JobPipelineStatus.CreatedStage(0, null, PipelineCardState.OK),
        inProgress: JobPipelineStatus.InProgressStage = JobPipelineStatus.InProgressStage(0, false, PipelineCardState.OK),
        completed: JobPipelineStatus.CompletedStage = JobPipelineStatus.CompletedStage(0, 0),
        failed: JobPipelineStatus.FailedStage = JobPipelineStatus.FailedStage(0, 0, 0, PipelineCardState.OK),
        transport: JobPipelineStatus.TransportLine = JobPipelineStatus.TransportLine(reachable = true),
    ) = JobPipelineStatus(created, inProgress, completed, failed, transport)

    @Test
    fun `renders the German labels for every stage card`() {
        val row = PipelineRow(status())

        val texts = texts(row)
        assertTrue(texts.contains("Angelegt"))
        assertTrue(texts.contains("In Bearbeitung"))
        assertTrue(texts.contains("Fertig"))
        assertTrue(texts.contains("Fehlgeschlagen"))
    }

    @Test
    fun `renders each stage's big number, including the in-progress card's ackPending`() {
        val row = PipelineRow(
            status(
                created = JobPipelineStatus.CreatedStage(7, null, PipelineCardState.OK),
                inProgress = JobPipelineStatus.InProgressStage(3, true, PipelineCardState.OK),
                completed = JobPipelineStatus.CompletedStage(40, 5),
                failed = JobPipelineStatus.FailedStage(1, 0, 0, PipelineCardState.OK),
            ),
        )

        val texts = texts(row)
        assertTrue(texts.contains("7"))
        assertTrue(texts.contains("3"))
        assertTrue(texts.contains("40"))
        assertTrue(texts.contains("1"))
    }

    @Test
    fun `the in-progress card shows the unreachable text instead of a number when the broker is unreachable`() {
        val row = PipelineRow(
            status(
                inProgress = JobPipelineStatus.InProgressStage(null, false, PipelineCardState.OK),
                transport = JobPipelineStatus.TransportLine(reachable = false),
            ),
        )

        assertTrue(texts(row).contains("nicht erreichbar"))
    }

    @Test
    fun `the oldest-age sub-line reads in minutes when jobs are open`() {
        val row = PipelineRow(status(created = JobPipelineStatus.CreatedStage(2, Duration.ofMinutes(4), PipelineCardState.OK)))

        assertTrue(texts(row).any { it.contains("älteste seit 4 min") })
    }

    @Test
    fun `worker-connected and worker-disconnected sub-lines render the expected German wording, without a number`() {
        val connected = PipelineRow(status(inProgress = JobPipelineStatus.InProgressStage(5, true, PipelineCardState.OK)))
        assertTrue(texts(connected).contains("Worker verbunden"), "the sub-line no longer carries a count -- that moved to the card's big number")

        val disconnected = PipelineRow(status(inProgress = JobPipelineStatus.InProgressStage(0, false, PipelineCardState.ERROR)))
        assertTrue(texts(disconnected).any { it.contains("kein Worker verbunden") })
    }

    @Test
    fun `throughput sub-line shows the last-5-minutes figure`() {
        val row = PipelineRow(status(completed = JobPipelineStatus.CompletedStage(40, 3)))

        assertTrue(texts(row).any { it.contains("davon letzte 5 min: 3") })
    }

    @Test
    fun `the DLQ sub-line shows count and human-readable size when reachable, and unreachable wording otherwise`() {
        val reachable = PipelineRow(
            status(
                failed = JobPipelineStatus.FailedStage(0, 5, 2 * 1024 * 1024, PipelineCardState.ERROR),
                transport = JobPipelineStatus.TransportLine(reachable = true),
            ),
        )
        assertTrue(texts(reachable).any { it.contains("DLQ: 5") && (it.contains("2,0 MB") || it.contains("2.0 MB")) })

        val unreachable = PipelineRow(
            status(
                failed = JobPipelineStatus.FailedStage(0, 0, 0, PipelineCardState.OK),
                transport = JobPipelineStatus.TransportLine(reachable = false),
            ),
        )
        assertTrue(texts(unreachable).any { it.contains("DLQ: nicht erreichbar") })
    }

    @Test
    fun `card state is never colour-only -- each card carries a data-state attribute and its own icon`() {
        val row = PipelineRow(
            status(
                created = JobPipelineStatus.CreatedStage(1, Duration.ofMinutes(20), PipelineCardState.WARNING),
                inProgress = JobPipelineStatus.InProgressStage(0, false, PipelineCardState.ERROR),
                failed = JobPipelineStatus.FailedStage(2, 0, 0, PipelineCardState.ERROR),
            ),
        )

        val cards = flatten(row).filter { it.hasClassName("system-pipeline-card") }
        assertEquals(4, cards.size)
        val states = cards.map { it.element.getAttribute("data-state") }
        assertTrue(states.contains("warning"))
        assertTrue(states.count { it == "error" } == 2, "in-progress and failed cards are both ERROR in this scenario")
        assertTrue(states.contains("ok"), "the completed card is always OK")

        // Icon shape differs per state (never colour-only) -- every card has exactly one icon.
        cards.forEach { card -> assertEquals(1, flatten(card).filterIsInstance<Icon>().size) }
    }

    @Test
    fun `the transport line renders labelled JetStream values when reachable`() {
        val row = PipelineRow(
            status(
                transport = JobPipelineStatus.TransportLine(
                    reachable = true,
                    queueDepth = 10,
                    ackPending = 4,
                    unprocessedResults = 1,
                    redelivered = 2,
                    duplicates = 0,
                ),
            ),
        )

        val texts = texts(row)
        assertTrue(texts.any { it.contains("Warteschlange") && it.contains("10") })
        assertTrue(texts.any { it.contains("beim Worker") && it.contains("4") })
        assertTrue(texts.any { it.contains("Ergebnisse unverarbeitet") && it.contains("1") })
        assertTrue(texts.any { it.contains("Wiederzustellungen") && it.contains("2") })
        assertTrue(texts.none { it.contains("Duplikate") }, "no duplicates badge when the figure is 0")
    }

    @Test
    fun `a non-zero duplicates figure renders its own badge with an icon, never colour-only`() {
        val row = PipelineRow(status(transport = JobPipelineStatus.TransportLine(reachable = true, duplicates = 3)))

        assertTrue(texts(row).any { it.contains("Duplikate: 3") })
        val badge = flatten(row).single { it.hasClassName("system-pipeline-duplicates") }
        assertEquals(1, flatten(badge).filterIsInstance<Icon>().size)
    }

    private fun texts(component: Component): List<String> =
        flatten(component).filterIsInstance<HasText>().map { it.text }.filter { it.isNotBlank() }

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }
}
