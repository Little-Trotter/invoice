package de.littletrotter.application.mail

import de.littletrotter.application.message.MessageCenterService
import de.littletrotter.domain.message.SystemMessage
import de.littletrotter.domain.message.SystemMessageContent
import de.littletrotter.domain.message.SystemMessageSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

@DisplayName("ArchiveHandoverNoticeProjection")
class ArchiveHandoverNoticeProjectionTest {

    private val messageCenter: MessageCenterService = mock()
    private val projection = ArchiveHandoverNoticeProjection(messageCenter)
    private val at = Instant.parse("2026-10-01T10:00:00Z")

    private fun recorded(notice: ArchiveHandoverNotice): SystemMessageContent {
        whenever(messageCenter.recordOnce(org.mockito.kotlin.any(), org.mockito.kotlin.anyOrNull())).thenReturn(SystemMessage())
        assertTrue(projection.project(notice))
        val captor = argumentCaptor<SystemMessageContent>()
        verify(messageCenter).recordOnce(captor.capture(), org.mockito.kotlin.anyOrNull())
        return captor.firstValue
    }

    @Test
    fun `a missing address is one standing warning for the whole instance`() {
        val content = recorded(ArchiveHandoverNotice(5, "RE-1", ArchiveHandoverNoticeReason.ADDRESS_MISSING, null, at))

        assertEquals(ArchiveHandoverNoticeProjection.MISSING_ADDRESS_KEY, content.messageKey)
        assertEquals(SystemMessageSeverity.WARNING, content.severity)
        assertEquals(ArchiveHandoverNoticeProjection.REF_ID_ADDRESS, content.refId)
    }

    @Test
    fun `a failed hand-over is one error per journal row and names the cause`() {
        val content = recorded(ArchiveHandoverNotice(5, "RE-1", ArchiveHandoverNoticeReason.HANDOVER_FAILED, "550 mailbox full", at))

        assertEquals(ArchiveHandoverNoticeProjection.FAILED_KEY, content.messageKey)
        assertEquals(SystemMessageSeverity.ERROR, content.severity)
        assertEquals("5", content.refId)
        assertEquals(listOf("RE-1", "550 mailbox full"), content.args)
        assertTrue(content.resolvedText.contains("RE-1") && content.resolvedText.contains("550 mailbox full"))
    }

    @Test
    fun `a failed hand-over without error text still reads as a sentence`() {
        val content = recorded(ArchiveHandoverNotice(5, "RE-1", ArchiveHandoverNoticeReason.HANDOVER_FAILED, null, at))

        assertTrue(content.resolvedText.contains("unbekannter Fehler"))
    }

    @Test
    fun `a repeat is reported as not created`() {
        whenever(messageCenter.recordOnce(org.mockito.kotlin.any(), org.mockito.kotlin.anyOrNull())).thenReturn(null)

        assertFalse(projection.project(ArchiveHandoverNotice(5, "RE-1", ArchiveHandoverNoticeReason.ADDRESS_MISSING, null, at)))
    }
}
