package de.littletrotter.ui.view.system

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.FlexLayout
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import de.littletrotter.application.system.JobPipelineStatus
import de.littletrotter.application.system.PipelineCardState
import de.littletrotter.ui.i18n.TranslationProvider
import java.time.Duration

/**
 * The pipeline row: four stage cards (Angelegt/In Bearbeitung/Fertig/Fehlgeschlagen) left to
 * right, plus the compact JetStream transport line beneath them. Extracted out of [NatsPanel] to
 * keep that class under its own line budget.
 *
 * State is never colour-only: every card carries a state icon whose *shape* differs per
 * [PipelineCardState] (same convention as de.littletrotter.ui.view.invoice.JobStatusChip's own
 * icon-shape rule), and `data-state` on the card element itself for a Karibu/CSS assertion that
 * does not depend on reading a colour.
 */
class PipelineRow(status: JobPipelineStatus) : Div(), TranslationProvider {

    init {
        addClassName("system-pipeline-row")

        val cards = FlexLayout(
            stageCard(
                tr("system.pipeline.created"),
                status.created.count.toString(),
                createdSubLine(status.created.oldestAge),
                status.created.state,
            ),
            stageCard(
                tr("system.pipeline.inProgress"),
                inProgressValue(status.inProgress.ackPending),
                inProgressSubLine(status.inProgress),
                status.inProgress.state,
            ),
            stageCard(
                tr("system.pipeline.completed"),
                status.completed.last24h.toString(),
                tr("system.pipeline.completed.last5m", status.completed.last5m),
                PipelineCardState.OK,
            ),
            stageCard(
                tr("system.pipeline.failed"),
                status.failed.failedCount.toString(),
                failedSubLine(status),
                status.failed.state,
            ),
        ).also {
            it.setWidthFull()
            it.flexWrap = FlexLayout.FlexWrap.WRAP
            it.addClassName("lt-pipeline-stages")
        }

        add(cards, transportLine(status.transport))
    }

    private fun createdSubLine(oldestAge: Duration?): String =
        if (oldestAge == null) {
            tr("system.pipeline.created.none")
        } else {
            tr("system.pipeline.created.oldestAge", oldestAge.toMinutes())
        }

    /** "In Bearbeitung"'s big number is `numAckPending` — a transport figure, `null` when the
     *  broker is unreachable, in which case the card shows the unreachable text instead of `0`. */
    private fun inProgressValue(ackPending: Long?): String =
        ackPending?.toString() ?: tr("system.pipeline.transport.unreachable")

    private fun inProgressSubLine(stage: JobPipelineStatus.InProgressStage): String =
        if (stage.workerConnected) {
            tr("system.pipeline.inProgress.workerConnected")
        } else {
            tr("system.pipeline.inProgress.workerDisconnected")
        }

    private fun failedSubLine(status: JobPipelineStatus): String =
        if (status.transport.reachable) {
            tr("system.pipeline.failed.dlq", status.failed.dlqMessages, formatBytes(status.failed.dlqBytes))
        } else {
            tr("system.pipeline.failed.dlq.unreachable")
        }

    private fun stageCard(label: String, value: String, subLine: String, state: PipelineCardState): Div {
        val header = HorizontalLayout(
            Span(value).apply {
                addClassNames("system-pipeline-card-value", "lt-pipeline-value")
            },
            stateIcon(state),
        ).apply {
            isPadding = false
            isSpacing = true
            alignItems = FlexComponent.Alignment.CENTER
        }

        val subLineSpan = Span(subLine).apply { addClassName("lt-pipeline-subline") }

        return Div(SystemUiComponents.captionSpan(label), header, subLineSpan).apply {
            addClassNames("system-pipeline-card", "lt-sys-card")
            element.setAttribute("data-state", state.name.lowercase())
        }
    }

    private fun stateIcon(state: PipelineCardState): Icon {
        val icon = when (state) {
            PipelineCardState.OK -> VaadinIcon.CHECK_CIRCLE
            PipelineCardState.WARNING -> VaadinIcon.WARNING
            PipelineCardState.ERROR -> VaadinIcon.CLOSE_CIRCLE
        }
        return icon.create().apply {
            addClassName(stateColorClass(state))
            setSize("1rem")
        }
    }

    private fun stateColorClass(state: PipelineCardState): String = when (state) {
        PipelineCardState.OK -> "lt-text-ok"
        PipelineCardState.WARNING -> "lt-text-warn"
        PipelineCardState.ERROR -> "lt-text-error"
    }

    private fun transportLine(transport: JobPipelineStatus.TransportLine): Div {
        val content: Component = if (!transport.reachable) {
            Span(tr("system.pipeline.transport.unreachable")).apply { addClassName("lt-text-secondary") }
        } else {
            HorizontalLayout(
                transportValue(tr("system.pipeline.transport.queueDepth"), transport.queueDepth.toString()),
                transportValue(tr("system.pipeline.transport.ackPending"), transport.ackPending.toString()),
                transportValue(tr("system.pipeline.transport.unprocessedResults"), transport.unprocessedResults.toString()),
                transportValue(tr("system.pipeline.transport.redelivered"), transport.redelivered.toString()),
            ).apply {
                isPadding = false
                isSpacing = true
                addClassName("lt-pipeline-transport-values")
                if (transport.duplicates > 0) {
                    add(duplicatesBadge(transport.duplicates))
                }
            }
        }
        return Div(content).apply {
            addClassName("system-pipeline-transport")
        }
    }

    private fun transportValue(label: String, value: String): Span = Span("$label: $value")

    /** The duplicate-message badge carries its own icon, not just red text, so it is never
     *  colour-only either. */
    private fun duplicatesBadge(duplicates: Long): Component =
        HorizontalLayout(
            VaadinIcon.WARNING.create().apply {
                addClassName("lt-text-error")
                setSize("1rem")
            },
            Span(tr("system.pipeline.transport.duplicates", duplicates)).apply { addClassName("lt-pipeline-duplicates-text") },
        ).apply {
            addClassName("system-pipeline-duplicates")
            isPadding = false
            isSpacing = false
            alignItems = FlexComponent.Alignment.CENTER
        }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= BYTES_PER_MB -> "%.1f MB".format(bytes / BYTES_PER_MB.toDouble())
        bytes >= BYTES_PER_KB -> "%.1f KB".format(bytes / BYTES_PER_KB.toDouble())
        else -> "$bytes B"
    }

    override fun getComponent(): Component = this

    private companion object {
        const val BYTES_PER_KB = 1024L
        const val BYTES_PER_MB = 1024L * 1024L
    }
}
