package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import de.littletrotter.ui.component.ChipTone
import de.littletrotter.ui.component.StatusChip

/**
 * Renders [LineAdvice] findings as dismissible warning chips, reusing the existing
 * the warn tone of [StatusChip] instead of inventing a new visual language for
 * "this is advisory, not an error". Used both by the item dialogs (live, as the user types) and
 * by [InvoiceOverviewPanel]'s review-step summary (once per saved item).
 *
 * "Dismissible" is session-only: clicking × removes the chip from view immediately. Nothing here
 * is persisted or re-validated — a later edit that still matches the same pattern will show the
 * chip again on the next [update] call, which is intentional (task Definition of Done: these
 * never block, never gate a save, and carry no state of their own).
 *
 * Its own file, separate from [LineAdvisor] and from every item dialog: a follow-up change is expected to
 * touch the item dialogs' article-selection listener around the same time, so this rendering
 * helper stays a small, independent add-on the dialogs merely call into.
 */
internal class LineAdvicePanel(private val tr: (String) -> String) {

    private val chipRow = FlexLayout().apply {
        flexWrap = FlexLayout.FlexWrap.WRAP
        addClassName("lt-gap-040")
    }

    /** Hidden (no title, no space) whenever there is nothing to show. */
    val component: VerticalLayout = VerticalLayout(
        Span(tr("invoiceDetail.advisor.title")).apply { addClassName("lt-line-advice-title") },
        chipRow,
    ).apply {
        isPadding = false; isSpacing = false
        addClassName("lt-gap-025")
        isVisible = false
    }

    fun update(advice: List<LineAdvice>) {
        chipRow.removeAll()
        advice.forEach { chipRow.add(chipFor(it)) }
        component.isVisible = advice.isNotEmpty()
    }

    private fun chipFor(advice: LineAdvice): Component {
        val chip = StatusChip(tr(advice.messageKey), ChipTone.WARN)
        val dismiss = Span("×").apply {
            addClassNames("line-advice-dismiss", "lt-line-advice-dismiss")
            element.setAttribute("aria-label", tr("invoiceDetail.advisor.dismiss"))
            addClickListener { chip.isVisible = false }
        }
        chip.add(dismiss)
        return chip
    }
}
