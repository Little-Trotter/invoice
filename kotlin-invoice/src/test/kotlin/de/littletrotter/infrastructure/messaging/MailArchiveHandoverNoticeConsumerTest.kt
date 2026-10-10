package de.littletrotter.infrastructure.messaging

import de.littletrotter.application.mail.ArchiveHandoverNotice
import de.littletrotter.application.mail.ArchiveHandoverNoticeProjection
import de.littletrotter.application.mail.ArchiveHandoverNoticeReason
import de.littletrotter.shared.mail.MailArchiveHandoverNoticeEvent
import de.littletrotter.shared.mail.MailArchiveHandoverNoticeReason
import de.littletrotter.shared.mail.MailContractViolationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

@DisplayName("MailArchiveHandoverNoticeConsumer")
class MailArchiveHandoverNoticeConsumerTest {

    private val projection: ArchiveHandoverNoticeProjection = mock()
    private val consumer = MailArchiveHandoverNoticeConsumer(projection)
    private val payload = MailArchiveHandoverNoticeEvent.encode(
        MailArchiveHandoverNoticeEvent(
            journalId = 4,
            referenceKey = "RE-2026-0004",
            reason = MailArchiveHandoverNoticeReason.HANDOVER_FAILED,
            error = "550 mailbox full",
            occurredAt = Instant.parse("2026-10-01T10:00:00Z"),
        ),
    )

    @Test
    fun `a notice is decoded, translated and projected`() {
        whenever(projection.project(org.mockito.kotlin.any())).thenReturn(true)

        assertTrue(consumer.consume(payload))

        val captor = argumentCaptor<ArchiveHandoverNotice>()
        verify(projection).project(captor.capture())
        assertEquals(4L, captor.firstValue.journalId)
        assertEquals(ArchiveHandoverNoticeReason.HANDOVER_FAILED, captor.firstValue.reason)
    }

    @Test
    fun `a repeated notice changes nothing`() {
        whenever(projection.project(org.mockito.kotlin.any())).thenReturn(false)

        assertFalse(consumer.consume(payload))
    }

    @Test
    fun `an unreadable payload is rejected loudly and projects nothing`() {
        assertThrows(MailContractViolationException::class.java) { consumer.consume("{}") }

        verify(projection, never()).project(org.mockito.kotlin.any())
    }
}
