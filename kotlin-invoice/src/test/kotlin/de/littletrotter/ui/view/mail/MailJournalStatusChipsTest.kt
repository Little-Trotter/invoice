package de.littletrotter.ui.view.mail

import de.littletrotter.application.mail.port.inbound.MailJournalStatus
import de.littletrotter.ui.component.ChipTone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MailJournalStatusChipsTest {

    @Test
    fun `toneOf maps every MailJournalStatus`() {
        val expected = mapOf(
            MailJournalStatus.CREATED to ChipTone.NEUTRAL,
            MailJournalStatus.QUEUED to ChipTone.NEUTRAL,
            MailJournalStatus.SENDING to ChipTone.NEUTRAL,
            MailJournalStatus.SENT to ChipTone.OK,
            MailJournalStatus.FAILED to ChipTone.ERROR,
        )
        assertEquals(MailJournalStatus.entries.toSet(), expected.keys)
        MailJournalStatus.entries.forEach { assertEquals(expected.getValue(it), MailJournalStatusChips.toneOf(it), "status=$it") }
    }
}
