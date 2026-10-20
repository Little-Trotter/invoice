package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.shared.Money
import de.littletrotter.ui.component.StatusChip
import de.littletrotter.ui.component.orPlaceholder

/**
 * Frozen context bar shown instead of [InvoiceCockpitHero] while entry mode is active (Task K1):
 * invoice number, status, dirty indicator, gross total — nothing else. Parties, status
 * transitions and the recalculate action are not reachable mid-edit; they belong to the document
 * view the user just left. The actual "Done" affordance is the `backBtn` inside the entry
 * workspace's own footer (see `buildEntryPane`), not this bar. Stays a plain extension function
 * rather than moving with the rest of the cockpit: it never reads a cockpit port, only
 * [InvoiceDetailView.entryGrossSpan] and `tr()`.
 */
internal fun InvoiceDetailView.buildEntryContextBar(current: InvoiceDetailDto, dirtyDot: Span): Div {
    val status = current.statusEnum()
    val invNumber = current.invoiceNumber.orPlaceholder("—")
    // Same cancellation-pair resolution as the cockpit hero's own badge.
    val statusBadge = StatusChip(tr(statusBadgeLabelKey(status, current.typeCodeEnum())), invoiceStatusTone(status))
    val identity = Div(
        Span(invNumber).apply { addClassName("entry-context-number") },
        statusBadge,
        dirtyDot,
    ).apply { addClassName("entry-context-identity") }

    // Hold the gross span for in-place updates by refreshTotals()
    entryGrossSpan = Span(formatCurrency((current.grossAmount ?: Money.ZERO).amount)).apply { addClassName("entry-context-gross") }

    return Div(identity, entryGrossSpan).apply {
        addClassName("entry-context-bar")
        setWidthFull()
    }
}
