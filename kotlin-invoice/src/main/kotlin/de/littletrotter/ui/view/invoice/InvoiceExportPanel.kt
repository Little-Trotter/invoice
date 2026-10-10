package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.H3
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.ui.i18n.TranslationProvider
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject

/**
 * AR9: Extracted from InvoiceDetailView God-Class. Self-contained panel for the
 * "Versions/print jobs" tab.
 *
 * Composes [InvoicePrintControls] and [InvoiceGenerationJobsPanel] (the engine/template/
 * print controls and the job Grid/Prüfberichte, both extracted from this class' former single-file
 * form) into one card. Both sub-panels, and this panel itself, are `@Dependent` CDI beans
 * injected once into [InvoiceDetailView] for its whole lifetime — unlike the pre-D6 version, a
 * new invoice does not construct a new [InvoiceExportPanel]; [render]/[refresh] just rebuild this
 * one instance's content.
 */
@Dependent
class InvoiceExportPanel
@Inject
constructor(
    private val printControls: InvoicePrintControls,
    private val jobsPanel: InvoiceGenerationJobsPanel,
) : VerticalLayout(), TranslationProvider {

    /** Called after a job is created or refreshed — the parent view uses this to reload data.
     *  Delegates to [printControls], the only sub-panel with a reload trigger (the print/refresh
     *  buttons). */
    var onReload: () -> Unit
        get() = printControls.onReload
        set(value) { printControls.onReload = value }

    private var card: Div? = null

    init {
        isPadding = false; isSpacing = true; setWidthFull()
        alignItems = FlexComponent.Alignment.STRETCH
    }

    override fun getComponent(): Component = this

    /** Forwards the view's [InvoiceDetailState] to [InvoicePrintControls], the only sub-panel
     *  that reports print-job outcomes back to the view (see [InvoiceDetailState]'s own KDoc). */
    internal fun bind(state: InvoiceDetailState) {
        printControls.bind(state)
    }

    fun render(invoice: InvoiceDetailDto) {
        removeAll()
        val newCard = Div().apply {
            addClassNames("ino-card", "ino-card--padded", "lt-w-full", "lt-border-box")
            add(H3(tr("invoiceDetail.jobs.title")))
            add(printControls)
            add(jobsPanel)
        }
        card = newCard
        add(newCard)
        printControls.render(invoice)
        jobsPanel.render(invoice)
    }

    /**
     * fix-job-panel-flicker-and-vanishing-entry — incremental refresh: called by
     * [de.littletrotter.ui.view.invoice.InvoiceDetailView]'s `fillTabHosts` on every background
     * data change (poll tick, push event, print job scheduled) instead of the caller discarding
     * this whole panel and building a new one. Delegates to both sub-panels' own `refresh`.
     */
    fun refresh(invoice: InvoiceDetailDto) {
        if (card == null) {
            render(invoice)
            return
        }
        printControls.refresh(invoice)
        jobsPanel.refresh(invoice)
    }
}
