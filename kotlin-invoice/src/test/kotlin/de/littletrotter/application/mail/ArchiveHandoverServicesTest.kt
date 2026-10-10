package de.littletrotter.application.mail

import de.littletrotter.application.mail.port.inbound.ArchiveHandoverRetryOutcome
import de.littletrotter.application.mail.port.inbound.ArchiveHandoverSettings
import de.littletrotter.application.mail.port.inbound.ArchiveHandoverSettingsUpdate
import de.littletrotter.application.mail.port.inbound.MailJournalHandoverStatus
import de.littletrotter.application.mail.port.outbound.MailArchiveHandoverPort
import de.littletrotter.application.message.MessageCenterService
import jakarta.enterprise.inject.Instance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

@DisplayName("Archive hand-over settings, update and retry services")
class ArchiveHandoverServicesTest {

    private val stored = ArchiveHandoverSettings("archiv@example.com", true, true, Instant.parse("2026-10-01T10:00:00Z"), "anna")
    private val unset = stored.copy(address = null)
    private val update = ArchiveHandoverSettingsUpdate("archiv@example.com")
    private val messageCenter: MessageCenterService = mock()

    private fun instance(port: MailArchiveHandoverPort?): Instance<MailArchiveHandoverPort> =
        mock<Instance<MailArchiveHandoverPort>>().also {
            whenever(it.isResolvable).thenReturn(port != null)
            if (port != null) whenever(it.get()).thenReturn(port)
        }

    @Test
    fun `the setting is read from the component`() {
        val port = mock<MailArchiveHandoverPort>()
        whenever(port.settings()).thenReturn(stored)

        assertSame(stored, ArchiveHandoverSettingsQueryService(instance(port)).settings())
    }

    @Test
    fun `saving an address withdraws the standing missing-address notice`() {
        val port = mock<MailArchiveHandoverPort>()
        whenever(port.save(update)).thenReturn(stored)

        assertSame(stored, UpdateArchiveHandoverSettingsService(instance(port), messageCenter).update(update))

        verify(messageCenter).withdraw(
            ArchiveHandoverNoticeProjection.MISSING_ADDRESS_KEY,
            ArchiveHandoverNoticeProjection.REF_TYPE_SETTING,
            ArchiveHandoverNoticeProjection.REF_ID_ADDRESS,
        )
    }

    @Test
    fun `clearing the address keeps the notice`() {
        val port = mock<MailArchiveHandoverPort>()
        val clear = ArchiveHandoverSettingsUpdate(null)
        whenever(port.save(clear)).thenReturn(unset)

        UpdateArchiveHandoverSettingsService(instance(port), messageCenter).update(clear)

        verify(messageCenter, never()).withdraw(any(), any(), any())
    }

    @Test
    fun `a message-center failure does not undo a saved setting`() {
        val port = mock<MailArchiveHandoverPort>()
        whenever(port.save(update)).thenReturn(stored)
        whenever(messageCenter.withdraw(any(), any(), any())).thenThrow(IllegalStateException("db down"))

        assertSame(stored, UpdateArchiveHandoverSettingsService(instance(port), messageCenter).update(update))
    }

    @Test
    fun `the retry is delegated to the component`() {
        val port = mock<MailArchiveHandoverPort>()
        val outcome = ArchiveHandoverRetryOutcome(7, MailJournalHandoverStatus.HANDED_OVER, null)
        whenever(port.retry(7)).thenReturn(outcome)

        assertSame(outcome, RetryArchiveHandoverService(instance(port)).retryArchiveHandover(7))
    }

    @Test
    fun `without the component every operation fails readably`() {
        val none = instance(null)
        val messages = listOf(
            assertThrows(MailDispatchException::class.java) { ArchiveHandoverSettingsQueryService(none).settings() }.message,
            assertThrows(MailDispatchException::class.java) { UpdateArchiveHandoverSettingsService(none, messageCenter).update(update) }.message,
            assertThrows(MailDispatchException::class.java) { RetryArchiveHandoverService(none).retryArchiveHandover(7) }.message,
        )

        assertEquals(List(3) { MailNotAvailable.MESSAGE }, messages)
    }
}
