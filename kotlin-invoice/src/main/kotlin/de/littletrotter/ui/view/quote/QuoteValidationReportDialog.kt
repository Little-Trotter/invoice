package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.ListItem
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.html.UnorderedList
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import de.littletrotter.application.quote.port.inbound.QuoteValidationReport
import de.littletrotter.ui.component.AppDialog
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.ChipTone
import de.littletrotter.ui.component.StatusChip
import de.littletrotter.ui.i18n.TranslationProvider

/** The check report of one document: the verdict and, when it failed, every finding as a readable line. */
internal class QuoteValidationReportDialog(
    host: TranslationProvider,
    report: QuoteValidationReport,
) : AppDialog(host.tr("quote.documents.report.title"), Size.M) {

    internal val verdict = if (report.valid) {
        StatusChip(host.tr("quote.documents.report.valid"), ChipTone.OK)
    } else {
        StatusChip(host.tr("quote.documents.report.invalid"), ChipTone.ERROR)
    }
    internal val findings = UnorderedList()
    internal val closeButton = AppButtons.tertiary(host.tr("app.close")) { close() }

    init {
        report.findings.forEach { findings.add(ListItem(it)) }
        val body = VerticalLayout(Div(verdict)).apply { isPadding = false }
        if (report.findings.isNotEmpty()) body.add(findings)
        if (!report.valid && report.findings.isEmpty()) body.add(Span(host.tr("quote.documents.report.noDetails")))
        add(body)
        footer(closeButton)
    }
}
