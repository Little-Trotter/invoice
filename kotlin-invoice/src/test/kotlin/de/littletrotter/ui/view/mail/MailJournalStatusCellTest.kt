package de.littletrotter.ui.view.mail

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import de.littletrotter.application.mail.port.inbound.MailJournalStatus
import de.littletrotter.ui.i18n.TranslationProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("MailJournalStatusCell symbols")
class MailJournalStatusCellTest {

    @Test
    fun `every state has its own symbol`() {
        val icons = MailJournalStatus.entries.map { MailJournalStatusCell.iconOf(it) }
        assertEquals(MailJournalStatus.entries.size, icons.toSet().size)
        assertEquals(VaadinIcon.CHECK_CIRCLE, MailJournalStatusCell.iconOf(MailJournalStatus.SENT))
    }

    @Test
    fun `the state name is the tooltip and the accessible label, the tone follows the chip rule`() {
        val translations = object : TranslationProvider {
            override fun getComponent(): Component = Span()
            override fun tr(key: String, vararg params: Any): String = key
        }
        val cell = MailJournalStatusCell(translations)

        val sent = cell.build(MailJournalStatus.SENT)
        val sending = cell.build(MailJournalStatus.SENDING)

        assertEquals("mail.journal.status.SENT", sent.element.getAttribute("title"))
        assertEquals("mail.journal.status.SENT", sent.element.getAttribute("aria-label"))
        assertTrue(sent.element.classList.contains("status-glyph--ok"))
        assertTrue(sending.element.classList.contains("status-glyph--pulse"))
    }
}
