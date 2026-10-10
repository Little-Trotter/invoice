package de.littletrotter.ui.view.system

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.H3
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import de.littletrotter.application.system.JobPipelineStatusService
import de.littletrotter.application.system.NatsStatusService
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.asNumericColumn
import de.littletrotter.ui.component.asTextColumn
import de.littletrotter.ui.i18n.TranslationProvider
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject

/**
 * Shows the job pipeline (created/in progress/completed/failed, DB-authoritative via
 * [JobPipelineStatusService]) above the raw JetStream subject grid (via [NatsStatusService]),
 * rather than a flat row of transport counters. See [PipelineRow] for the pipeline row itself,
 * extracted so this class stays under its own ~250-line budget.
 */
@Dependent
class NatsPanel @Inject constructor(
    private val natsStatusService: NatsStatusService,
    private val jobPipelineStatusService: JobPipelineStatusService,
) : VerticalLayout(), TranslationProvider {

    init {
        addClassName("system-nats-panel")
        setWidthFull(); isPadding = false; isSpacing = true
        rebuild()
    }

    /** Green-IT — the refresh button re-renders this panel's own content only, never
     *  `UI.getCurrent()?.page?.reload()` (no new polling, no full-page reload). */
    private fun rebuild() {
        removeAll()

        val status = natsStatusService.currentStatus()
        val pipelineRow = PipelineRow(jobPipelineStatusService.currentStatus())

        add(buildHero(status), pipelineRow)
        val error = status.error
        if (error.isNullOrBlank()) {
            add(SystemUiComponents.sectionHeader(tr("system.nats.subjectsHeader")), buildSubjectGrid(status.subjects))
        } else {
            add(buildErrorBanner(error))
        }
    }

    private fun buildHero(status: NatsStatusService.NatsStatus): Div {
        val healthy = status.brokerUp && status.error.isNullOrBlank()
        return Div().apply {
            addClassName("lt-nats-hero")

            val text = VerticalLayout().apply {
                isPadding = false; isSpacing = false
                add(H3(tr("system.nats.title")).apply { addClassName("lt-nats-hero__title") })
                add(Span(if (healthy) tr("system.nats.status.up") else tr("system.nats.status.down")).apply {
                    addClassNames("lt-nats-hero__status", if (healthy) "lt-text-ok" else "lt-text-error")
                })
                add(Span(tr("system.nats.server", status.serverUrl)).apply { addClassName("lt-nats-hero__server") })
            }

            val refreshBtn = AppButtons.tertiary(tr("system.refresh"), ActionIcons.REFRESH) { rebuild() }

            add(text, refreshBtn)
        }
    }

    private fun buildSubjectGrid(subjects: List<NatsStatusService.SubjectStatus>): DataTable<NatsStatusService.SubjectStatus> =
        DataTable(NatsStatusService.SubjectStatus::class.java, false).apply {
            setWidthFull()
            isAllRowsVisible = true
            addColumn(NatsStatusService.SubjectStatus::name)
                .setHeader(tr("system.nats.subject"))
                .setSortable(false)
                .asTextColumn(TextColumnWidth.MEDIUM)
            addColumn { it.retainedMessages.toString() }
                .setHeader(tr("system.nats.retained"))
                .setSortable(false)
                .asNumericColumn()
            addComponentColumn { subject -> lagCell(subject) }
                .setHeader(tr("system.nats.lag"))
                .setSortable(false)
                .asCompactColumn()
            setItems(subjects)
        }

    /** The subject grid's lag column cell: bold, right-aligned, red once anything is lagging. A
     *  named function rather than only an inline `addComponentColumn` lambda, so a test can render
     *  it directly for an arbitrary [subject] without walking the grid's own column renderer. */
    internal fun lagCell(subject: NatsStatusService.SubjectStatus): Span = Span(subject.consumerLag.toString()).apply {
        addClassNames("lt-nats-lag", if (subject.consumerLag > 0) "lt-text-error" else "lt-text-ok")
    }

    private fun buildErrorBanner(error: String): Div = Div(Span(tr("system.nats.error", error))).apply { addClassName("lt-nats-error") }

    override fun getComponent(): Component = this
}
