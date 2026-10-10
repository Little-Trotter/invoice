package de.littletrotter.ui.view.mail

import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.NativeButton
import com.vaadin.flow.dom.Element
import de.littletrotter.application.mail.port.inbound.MailJournalStatus
import de.littletrotter.ui.component.ChipTone
import de.littletrotter.ui.component.applyChipTone
import de.littletrotter.ui.i18n.TranslationProvider

/**
 * The status chip row above the journal grid: one chip per delivery state with its count, the
 * toggled state highlighted. A persistent container re-rendered on every refresh, so its click
 * listeners survive data refreshes. All five states are always offered: a failed-mail chip at zero
 * is itself the reassurance that nothing is stuck.
 */
internal class MailJournalStatusChips(
    private val translations: TranslationProvider,
    private val onToggle: (MailJournalStatus) -> Unit,
) : Div() {

    init {
        addClassName("validation-quick-filters")
        element.setAttribute("aria-label", translations.tr("mail.journal.chips.ariaLabel"))
    }

    fun render(counts: Map<MailJournalStatus, Long>, active: MailJournalStatus?) {
        removeAll()
        MailJournalStatus.entries.forEach { status -> add(buildChip(status, counts[status] ?: 0L, status == active)) }
    }

    private fun buildChip(status: MailJournalStatus, count: Long, active: Boolean): NativeButton {
        val label = translations.tr("mail.journal.status.${status.name}")
        return NativeButton().apply {
            applyChipTone(toneOf(status))
            addClassName("quick-filter-chip")
            if (active) addClassName("quick-filter-chip--active")
            element.setAttribute("aria-pressed", active.toString())
            element.setAttribute("aria-label", translations.tr("mail.journal.chips.state.ariaLabel", label, count))
            element.appendChild(Element.createText("$label $count"))
            addClickListener { onToggle(status) }
        }
    }

    companion object {
        /** Delivered reads ok, failed reads as an error, everything still in flight stays neutral. */
        fun toneOf(status: MailJournalStatus): ChipTone = when (status) {
            MailJournalStatus.SENT -> ChipTone.OK
            MailJournalStatus.FAILED -> ChipTone.ERROR
            MailJournalStatus.CREATED, MailJournalStatus.QUEUED, MailJournalStatus.SENDING -> ChipTone.NEUTRAL
        }
    }
}
