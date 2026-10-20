package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.icon.VaadinIcon
import de.littletrotter.application.quote.port.inbound.QuoteLogKind
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.view.journal.JournalActionIcons

/**
 * The leading tile of an offer protocol row, built from the same tile the invoice journal uses.
 * Additions and hand-offs (dispatch, attachment, invoice) read as `success`, changes to something
 * that already existed (note, status) as `warning`, like the invoice's action tones.
 */
internal object QuoteLogIcons {

    fun icon(kind: QuoteLogKind): VaadinIcon = when (kind) {
        QuoteLogKind.NOTE -> ActionIcons.NOTE
        QuoteLogKind.STATUS -> VaadinIcon.REFRESH
        QuoteLogKind.SEND -> VaadinIcon.PAPERPLANE
        QuoteLogKind.ATTACHMENT -> ActionIcons.ASSIGN
        QuoteLogKind.INVOICE -> ActionIcons.INVOICE
    }

    fun modifier(kind: QuoteLogKind): String = when (kind) {
        QuoteLogKind.NOTE, QuoteLogKind.STATUS -> "warning"
        QuoteLogKind.SEND, QuoteLogKind.ATTACHMENT, QuoteLogKind.INVOICE -> "success"
    }

    fun tile(kind: QuoteLogKind, label: String): Icon = JournalActionIcons.tile(icon(kind), modifier(kind), label)
}
