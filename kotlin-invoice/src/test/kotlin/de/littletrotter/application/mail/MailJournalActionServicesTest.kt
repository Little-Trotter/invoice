package de.littletrotter.application.mail

import de.littletrotter.application.mail.port.inbound.MailJournalArchiveStatus
import de.littletrotter.application.mail.port.inbound.MailJournalStatus
import de.littletrotter.application.mail.port.inbound.MailRearchiveOutcome
import de.littletrotter.application.mail.port.inbound.MailSendOutcome
import de.littletrotter.application.mail.port.outbound.MailJournalActionPort
import jakarta.enterprise.inject.Instance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@DisplayName("RetryMailService / RearchiveMailService")
class MailJournalActionServicesTest {

    private val sendOutcome = MailSendOutcome(8, MailJournalStatus.QUEUED)
    private val rearchiveOutcome = MailRearchiveOutcome(7, MailJournalArchiveStatus.ARCHIVED, null)

    private fun instance(port: MailJournalActionPort?): Instance<MailJournalActionPort> =
        mock<Instance<MailJournalActionPort>>().also {
            whenever(it.isResolvable).thenReturn(port != null)
            if (port != null) whenever(it.get()).thenReturn(port)
        }

    @Test
    fun `retry is delegated to the component port`() {
        val port = mock<MailJournalActionPort>()
        whenever(port.retry(7)).thenReturn(sendOutcome)

        assertSame(sendOutcome, RetryMailService(instance(port)).retry(7))
        verify(port).retry(7)
    }

    @Test
    fun `rearchive is delegated to the component port`() {
        val port = mock<MailJournalActionPort>()
        whenever(port.rearchive(7)).thenReturn(rearchiveOutcome)

        assertSame(rearchiveOutcome, RearchiveMailService(instance(port)).rearchive(7))
        verify(port).rearchive(7)
    }

    @Test
    fun `without the component both actions fail readably`() {
        val retry = assertThrows(MailDispatchException::class.java) { RetryMailService(instance(null)).retry(7) }
        val rearchive = assertThrows(MailDispatchException::class.java) { RearchiveMailService(instance(null)).rearchive(7) }

        assertEquals(MailNotAvailable.MESSAGE, retry.message)
        assertEquals(MailNotAvailable.MESSAGE, rearchive.message)
    }
}
