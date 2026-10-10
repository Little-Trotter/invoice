package de.littletrotter.ui.view.mail

import com.vaadin.flow.component.icon.VaadinIcon
import de.littletrotter.application.mail.port.inbound.MailJournalHandoverStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("MailJournalHandoverCell symbols")
class MailJournalHandoverCellTest {

    @Test
    fun `every state has its own symbol`() {
        val icons = MailJournalHandoverStatus.entries.map { MailJournalHandoverCell.iconOf(it) }
        assertEquals(MailJournalHandoverStatus.entries.size, icons.toSet().size)
        assertEquals(VaadinIcon.CHECK_CIRCLE, MailJournalHandoverCell.iconOf(MailJournalHandoverStatus.HANDED_OVER))
    }

    @Test
    fun `only the outcomes carry a colour`() {
        assertNotNull(MailJournalHandoverCell.toneClass(MailJournalHandoverStatus.HANDED_OVER))
        assertNotNull(MailJournalHandoverCell.toneClass(MailJournalHandoverStatus.FAILED))
        assertNull(MailJournalHandoverCell.toneClass(MailJournalHandoverStatus.PENDING))
        assertNull(MailJournalHandoverCell.toneClass(MailJournalHandoverStatus.DISABLED))
        assertNull(MailJournalHandoverCell.toneClass(MailJournalHandoverStatus.NOT_CONFIGURED))
    }
}
