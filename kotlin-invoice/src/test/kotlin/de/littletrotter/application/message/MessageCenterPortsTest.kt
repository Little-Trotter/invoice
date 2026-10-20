package de.littletrotter.application.message

import de.littletrotter.application.dto.MessageCenterFilter
import de.littletrotter.application.dto.SystemMessageView
import de.littletrotter.domain.message.SystemMessage
import de.littletrotter.domain.message.SystemMessageContent
import de.littletrotter.domain.message.SystemMessageFilter
import de.littletrotter.domain.message.SystemMessageSeverity
import de.littletrotter.domain.message.SystemMessageSource
import de.littletrotter.fixture.edit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.OffsetDateTime

/** The message-center ports delegate to [MessageCenterService] and map stored rows to [SystemMessageView]. */
class MessageCenterPortsTest {

    private val service: MessageCenterService = mock()
    private val at = OffsetDateTime.parse("2026-08-25T10:15:00Z")

    @Test
    fun `find maps every column and decodes the positional arguments`() {
        val stored = SystemMessage.of(
            SystemMessageContent(
                severity = SystemMessageSeverity.WARNING,
                source = SystemMessageSource.INVOICE,
                messageKey = "message.some.key",
                args = listOf("a", "b"),
                resolvedText = "Text",
                refType = "INVOICE",
                refId = "42",
            ),
            actor = "system",
            occurredAt = at,
        ).edit { id = 7L }
        val to = at.plusDays(1)
        whenever(
            service.find(
                "alice",
                SystemMessageFilter(
                    severity = setOf(SystemMessageSeverity.WARNING),
                    source = setOf(SystemMessageSource.INVOICE),
                    from = at,
                    to = to,
                    unreadOnly = true,
                ),
                1,
                20,
            ),
        ).thenReturn(listOf(stored))

        val rows = MessageCenterQueries(service).find(
            "alice",
            MessageCenterFilter(setOf(SystemMessageSeverity.WARNING), setOf(SystemMessageSource.INVOICE), at, to, true),
            1,
            20,
        )

        assertEquals(
            listOf(
                SystemMessageView(
                    id = 7L,
                    occurredAt = at,
                    severity = SystemMessageSeverity.WARNING,
                    source = SystemMessageSource.INVOICE,
                    messageKey = "message.some.key",
                    args = listOf("a", "b"),
                    resolvedText = "Text",
                    refType = "INVOICE",
                    refId = "42",
                ),
            ),
            rows,
        )
    }

    @Test
    fun `count and unread ids are passed through`() {
        val filter = MessageCenterFilter()
        whenever(service.count("alice", SystemMessageFilter.EMPTY)).thenReturn(3L)
        whenever(service.unreadMessageIds("alice")).thenReturn(setOf(1L, 2L))
        val queries = MessageCenterQueries(service)

        assertEquals(3L, queries.count("alice", filter))
        assertEquals(setOf(1L, 2L), queries.unreadMessageIds("alice"))
    }

    @Test
    fun `mark use cases delegate to the service`() {
        MarkMessageRead(service).markRead(5L, "alice")
        MarkAllMessagesRead(service).markAllRead("bob")

        verify(service).markRead(5L, "alice")
        verify(service).markAllRead("bob")
    }
}
