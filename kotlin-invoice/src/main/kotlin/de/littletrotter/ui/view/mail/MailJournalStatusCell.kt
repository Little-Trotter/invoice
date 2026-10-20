package de.littletrotter.ui.view.mail

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.icon.VaadinIcon
import de.littletrotter.application.mail.port.inbound.MailJournalStatus
import de.littletrotter.ui.component.StatusGlyph
import de.littletrotter.ui.i18n.TranslationProvider

/**
 * The delivery state of a journal row as the shared status symbol: the state is the icon, its
 * name is the tooltip and the accessible label, so the column stays narrow like the invoice list.
 */
internal class MailJournalStatusCell(private val translations: TranslationProvider) {

    fun build(status: MailJournalStatus): Component =
        StatusGlyph(iconOf(status), MailJournalStatusChips.toneOf(status), translations.tr("mail.journal.status.${status.name}"), pulsing = status == MailJournalStatus.SENDING)

    companion object {
        fun iconOf(status: MailJournalStatus): VaadinIcon = when (status) {
            MailJournalStatus.CREATED -> VaadinIcon.FILE_O
            MailJournalStatus.QUEUED -> VaadinIcon.CLOCK
            MailJournalStatus.SENDING -> VaadinIcon.PAPERPLANE_O
            MailJournalStatus.SENT -> VaadinIcon.CHECK_CIRCLE
            MailJournalStatus.FAILED -> VaadinIcon.EXCLAMATION_CIRCLE
        }
    }
}
