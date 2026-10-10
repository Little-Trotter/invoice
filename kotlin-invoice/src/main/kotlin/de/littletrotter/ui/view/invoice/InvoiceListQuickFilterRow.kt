package de.littletrotter.ui.view.invoice

import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.ui.component.ChipSpec
import de.littletrotter.ui.component.StatusQuickFilterRow
import de.littletrotter.ui.i18n.TranslationProvider

/** The quick-filter chip row above the invoice list's grid: one chip per invoice state. */
internal fun invoiceQuickFilterRow(
    translations: TranslationProvider,
    onToggle: (InvoiceStatus) -> Unit,
): StatusQuickFilterRow<InvoiceStatus> = StatusQuickFilterRow(
    statuses = InvoiceStatus.entries,
    ariaLabel = translations.tr("invoice.quickFilter.ariaLabel"),
    chip = { st ->
        val label = translations.tr(statusBadgeLabelKey(st))
        ChipSpec(label, invoiceStatusTone(st), invoiceStatusGlyphIcon(st)) { count ->
            translations.tr("invoice.quickFilter.state.ariaLabel", label, count)
        }
    },
    onToggle = onToggle,
)
