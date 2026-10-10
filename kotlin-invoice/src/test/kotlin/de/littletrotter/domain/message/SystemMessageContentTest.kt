package de.littletrotter.domain.message

import de.littletrotter.domain.exception.InvalidParameterException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/** [SystemMessageContent] as the parameter object of [SystemMessage.of]: every field lands
 *  on the entity column it did before the S107 reshaping. */
class SystemMessageContentTest {

    private val at = OffsetDateTime.parse("2026-09-23T10:00:00Z")

    private val content = SystemMessageContent(
        severity = SystemMessageSeverity.WARNING,
        source = SystemMessageSource.EXPORT,
        messageKey = "message.center.document.checksumMismatch",
        args = listOf("RE-2026-0001", "invoices/1.pdf"),
        resolvedText = "Rechnung RE-2026-0001: Prüfsummenabweichung",
        refType = "INVOICE",
        refId = "1",
    )

    @Test
    fun `of maps every content field, the stamps and the recipient onto the entity`() {
        val message = SystemMessage.of(content, actor = "alice", occurredAt = at, recipientUserId = "bob")

        assertEquals(SystemMessageSeverity.WARNING, message.severity)
        assertEquals(SystemMessageSource.EXPORT, message.source)
        assertEquals("message.center.document.checksumMismatch", message.messageKey)
        assertEquals("""["RE-2026-0001","invoices/1.pdf"]""", message.messageArgs)
        assertEquals(listOf("RE-2026-0001", "invoices/1.pdf"), message.argsList())
        assertEquals("Rechnung RE-2026-0001: Prüfsummenabweichung", message.resolvedText)
        assertEquals("INVOICE", message.refType)
        assertEquals("1", message.refId)
        assertEquals("alice", message.actor)
        assertEquals(at, message.occurredAt)
        assertEquals("bob", message.recipientUserId)
    }

    @Test
    fun `boundary - omitted args and references default to an empty list and null`() {
        val minimal = SystemMessageContent(SystemMessageSeverity.INFO, SystemMessageSource.SYSTEM, messageKey = "k", resolvedText = "t")

        val message = SystemMessage.of(minimal, actor = "system", occurredAt = at)

        assertEquals(emptyList<String>(), minimal.args)
        assertEquals("[]", message.messageArgs)
        assertNull(message.refType)
        assertNull(message.refId)
        assertNull(message.recipientUserId, "no recipient = broadcast")
    }

    @Test
    fun `boundary - a blank message key is still rejected by the entity`() {
        val ex = assertThrows(InvalidParameterException::class.java) {
            SystemMessage.of(content.copy(messageKey = " "), actor = "system", occurredAt = at)
        }
        assertEquals("Meldungsschlüssel (messageKey) darf nicht leer sein.", ex.message)
    }

    @Test
    fun `boundary - a blank resolved text is still rejected by the entity`() {
        val ex = assertThrows(InvalidParameterException::class.java) {
            SystemMessage.of(content.copy(resolvedText = ""), actor = "system", occurredAt = at)
        }
        assertEquals("Meldungstext (resolvedText) darf nicht leer sein.", ex.message)
    }

    @Test
    fun `boundary - value equality covers every field`() {
        assertEquals(content, content.copy())
        assertEquals(content.hashCode(), content.copy().hashCode())
        assertNotEquals(content, content.copy(refId = null))
        assertNotEquals(content, content.copy(args = content.args.reversed()))
        assertNotEquals(content, content.copy(severity = SystemMessageSeverity.ERROR))
    }
}
