package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.icon.VaadinIcon
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.ui.component.LegendRow
import de.littletrotter.ui.component.StatusChip
import de.littletrotter.ui.component.openLegendDialog
import de.littletrotter.ui.component.tileColumnHeader

/**
 * The Status/Validierung columns of [InvoiceListView]'s grid: the column headers carry a symbol
 * that opens a legend dialog showing every state as the same chip the cells use. Both headers use
 * the shared [tileColumnHeader]/[openLegendDialog] mechanics; kept out of [InvoiceListView] so that
 * already-large class does not grow, and out of `ui/component` because building the per-enum rows
 * needs domain knowledge ([InvoiceStatus], [ValidationStatus]) the generic dialog builder does not have.
 */

// ── Status column ────────────────────────────────────────────────────────────────────────────

internal fun buildStatusColumnHeader(tr: (String) -> String, onOpen: () -> Unit): Component =
    tileColumnHeader(
        icon = VaadinIcon.FLAG,
        titleWord = tr("invoice.table.status"),
        ariaLabel = "${tr("invoice.table.status")} — ${tr("invoice.status.legend.open")}",
        onClick = onOpen,
    )

/** Sentence-key suffix per [InvoiceStatus] — reuses [InvoiceStatus.cssClass] (draft/review/sent/
 *  voided/cancelled) rather than a second parallel mapping, so a new [InvoiceStatus] value only
 *  needs its `cssClass`/`labelKey` (already required by the enum's constructor) and one new
 *  `invoice.status.legend.<cssClass>.sentence` key — no extra `when` to keep in sync. */
internal fun openStatusLegend(tr: (String) -> String) {
    openLegendDialog(
        title = tr("invoice.status.legend.title"),
        // Shared with the Validierung legend's own close button below — "Close"/"Schließen" is
        // the same generic action in both dialogs, not a Validierung-specific word.
        closeLabel = tr("app.close"),
        rows = InvoiceStatus.entries.map { status ->
            LegendRow(
                tile = StatusChip(tr(status.labelKey), invoiceStatusTone(status)),
                sentence = tr("invoice.status.legend.${status.cssClass}.sentence"),
                word = tr(status.labelKey),
            )
        },
    )
}

// ── Validierung column (generalised onto the shared LegendDialog/TileColumnHeader) ──────

internal fun buildValidationColumnHeader(tr: (String) -> String, onOpen: () -> Unit): Component =
    tileColumnHeader(
        icon = VaadinIcon.CLIPBOARD_CHECK,
        titleWord = tr("invoice.table.validation"),
        ariaLabel = "${tr("invoice.table.validation")} — ${tr("invoice.validation.legend.open")}",
        onClick = onOpen,
    )

/** The column-header legend popover's own written chip (space exists in a dialog, unlike
 *  the narrow cell): the shared [StatusChip] component. Never
 *  clickable — the legend only illustrates each state next to its explanatory sentence. */
private fun buildValidationLegendChip(vs: ValidationStatus, tr: (String) -> String): Component {
    val tooltip = validationStatusTooltip(vs, tr)
    return StatusChip(validationStatusLabel(vs, tr), validationStatusTone(vs), pulsing = vs == ValidationStatus.PENDING).apply {
        element.setAttribute("title", tooltip)
    }
}

private fun legendSentenceKey(vs: ValidationStatus): String = when (vs) {
    ValidationStatus.NOT_VALIDATED -> "notValidated"
    ValidationStatus.PENDING -> "pending"
    ValidationStatus.VALID -> "valid"
    ValidationStatus.INVALID -> "invalid"
    ValidationStatus.UNAVAILABLE -> "unavailable"
}

internal fun openValidationLegend(tr: (String) -> String) {
    openLegendDialog(
        title = tr("invoice.validation.legend.title"),
        closeLabel = tr("app.close"),
        // Chronological lifecycle order, not alphabetical — matches how an invoice actually moves
        // through the five states.
        rows = listOf(
            ValidationStatus.NOT_VALIDATED,
            ValidationStatus.PENDING,
            ValidationStatus.VALID,
            ValidationStatus.INVALID,
            ValidationStatus.UNAVAILABLE,
        ).map { vs ->
            LegendRow(
                tile = buildValidationLegendChip(vs, tr),
                sentence = tr("invoice.validation.legend.${legendSentenceKey(vs)}.sentence"),
            )
        },
    )
}
