package de.littletrotter.infrastructure.mail.acl

import de.littletrotter.application.mail.port.inbound.ArchiveHandoverSettingsUpdate
import de.littletrotter.application.mail.port.inbound.MailJournalHandoverStatus
import de.littletrotter.shared.mail.MailArchiveHandoverStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("MailArchiveHandoverTranslator")
class MailArchiveHandoverTranslatorTest {

    @Test
    fun `every wire status maps to the local status of the same name`() {
        MailArchiveHandoverStatus.entries.forEach {
            assertEquals(MailJournalHandoverStatus.valueOf(it.name), MailArchiveHandoverTranslator.statusFrom(it))
        }
    }

    @Test
    fun `an update keeps the switches and turns a blank address into none`() {
        val update = MailArchiveHandoverTranslator.toUpdate(ArchiveHandoverSettingsUpdate(" a@b.de ", invoiceEnabled = false, quoteEnabled = true))
        assertEquals("a@b.de", update.address)
        assertEquals(false, update.invoiceEnabled)
        assertNull(MailArchiveHandoverTranslator.toUpdate(ArchiveHandoverSettingsUpdate(null)).address)
    }
}
