package de.littletrotter.ui.view.mail

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.shared.Tooltip
import de.littletrotter.application.mail.port.inbound.MailJournalEntry
import de.littletrotter.application.mail.port.inbound.MailJournalHandoverStatus
import de.littletrotter.ui.i18n.TranslationProvider

/**
 * The hand-over state of a journal row to the archive address as one symbol: the state is the
 * icon, its name and meaning are the tooltip (and the accessible label), so the column stays
 * narrow. A failed hand-over adds the component's error text, the one detail the user can act on.
 */
internal class MailJournalHandoverCell(private val translations: TranslationProvider) {

    fun build(entry: MailJournalEntry): Component {
        val status = entry.archiveHandoverStatus
        val text = describe(entry)
        return Icon(iconOf(status)).apply {
            addClassName("lt-handover-icon")
            toneClass(status)?.let { addClassName(it) }
            element.setAttribute("role", "img")
            element.setAttribute("aria-label", text)
            Tooltip.forComponent(this).withText(text)
        }
    }

    /** Status name and meaning, plus the error for a failed hand-over. */
    fun describe(entry: MailJournalEntry): String {
        val status = entry.archiveHandoverStatus
        val label = translations.tr("mail.journal.handover.${status.name}")
        val meaning = translations.tr("mail.journal.handover.${status.name}.hint")
        val error = entry.archiveHandoverError?.takeIf { status == MailJournalHandoverStatus.FAILED && it.isNotBlank() }
        return listOfNotNull("$label: $meaning", error).joinToString(" ")
    }

    companion object {
        fun iconOf(status: MailJournalHandoverStatus): VaadinIcon = when (status) {
            MailJournalHandoverStatus.NOT_CONFIGURED -> VaadinIcon.MINUS_CIRCLE_O
            MailJournalHandoverStatus.DISABLED -> VaadinIcon.BAN
            MailJournalHandoverStatus.PENDING -> VaadinIcon.CLOCK
            MailJournalHandoverStatus.HANDED_OVER -> VaadinIcon.CHECK_CIRCLE
            MailJournalHandoverStatus.FAILED -> VaadinIcon.EXCLAMATION_CIRCLE
        }

        /** Only the two outcomes carry colour; the rest stay neutral. */
        fun toneClass(status: MailJournalHandoverStatus): String? = when (status) {
            MailJournalHandoverStatus.HANDED_OVER -> "lt-check-mark--ok"
            MailJournalHandoverStatus.FAILED -> "lt-text-error-strong"
            MailJournalHandoverStatus.NOT_CONFIGURED, MailJournalHandoverStatus.DISABLED, MailJournalHandoverStatus.PENDING -> null
        }
    }
}
