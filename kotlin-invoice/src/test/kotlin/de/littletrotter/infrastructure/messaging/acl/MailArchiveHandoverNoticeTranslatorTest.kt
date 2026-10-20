package de.littletrotter.infrastructure.messaging.acl

import de.littletrotter.application.mail.ArchiveHandoverNoticeReason
import de.littletrotter.shared.mail.MailArchiveHandoverNoticeEvent
import de.littletrotter.shared.mail.MailArchiveHandoverNoticeReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

@DisplayName("MailArchiveHandoverNoticeTranslator")
class MailArchiveHandoverNoticeTranslatorTest {

    @Test
    fun `a notice keeps its journal row, reference and reason`() {
        val at = Instant.parse("2026-10-01T10:00:00Z")
        MailArchiveHandoverNoticeReason.entries.forEach { reason ->
            val notice = MailArchiveHandoverNoticeTranslator.toNotice(MailArchiveHandoverNoticeEvent(9, "RE-1", reason, "e", at))
            assertEquals(ArchiveHandoverNoticeReason.valueOf(reason.name), notice.reason)
            assertEquals(9L, notice.journalId)
            assertEquals("RE-1", notice.referenceKey)
            assertEquals("e", notice.error)
            assertEquals(at, notice.occurredAt)
        }
    }
}
