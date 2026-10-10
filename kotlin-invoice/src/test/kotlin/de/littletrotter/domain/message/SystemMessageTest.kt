package de.littletrotter.domain.message

import de.littletrotter.domain.exception.InvalidParameterException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/** Entity-level validation and the [SystemMessage.argsList]/`encodeArgs` round trip. */
class SystemMessageTest {

    private fun aMessage(
        messageKey: String = "message.invoice.finalized",
        args: List<String> = listOf("RE-2026-0001"),
        resolvedText: String = "Rechnung RE-2026-0001 wurde festgeschrieben.",
        recipientUserId: String? = null,
    ): SystemMessage = SystemMessage.of(
        SystemMessageContent(
            severity = SystemMessageSeverity.INFO,
            source = SystemMessageSource.INVOICE,
            messageKey = messageKey,
            args = args,
            resolvedText = resolvedText,
            refType = "INVOICE",
            refId = "34",
        ),
        actor = "system",
        occurredAt = OffsetDateTime.parse("2026-08-25T10:15:00Z"),
        recipientUserId = recipientUserId,
    )

    @Test
    fun `a well-formed message is valid`() {
        assertDoesNotThrow { aMessage() }
    }

    @Test
    fun `a blank message key is rejected`() {
        assertThrows(InvalidParameterException::class.java) { aMessage(messageKey = "  ") }
    }

    @Test
    fun `blank resolved text is rejected`() {
        assertThrows(InvalidParameterException::class.java) { aMessage(resolvedText = "") }
    }

    @Test
    fun `recipientUserId null means broadcast, not a validation error`() {
        assertDoesNotThrow { aMessage(recipientUserId = null) }
    }

    // ── severity/source persisted by name (@Enumerated(STRING)) ─────────────────────────────

    @Test
    fun `severity and source enums are pinned to their expected literal names`() {
        // Pins the @Enumerated(EnumType.STRING) contract from the outside: V918's CHECK
        // constraints (chk_system_message_severity/source) enumerate these exact literals, so a
        // silent enum rename here would desync entity and migration without either failing loudly
        // on its own.
        assertEquals(setOf("INFO", "WARNING", "ERROR"), SystemMessageSeverity.entries.map { it.name }.toSet())
        assertEquals(
            setOf("EXPORT", "VALIDATION", "GENERATION", "INVOICE", "SYSTEM", "UI"),
            SystemMessageSource.entries.map { it.name }.toSet(),
        )
    }

    // ── message_args JSON round trip ─────────────────────────────────────────────────────────

    @Test
    fun `argsList decodes what encodeArgs produced`() {
        val message = aMessage(args = listOf("RE-2026-0001", "42"))
        assertEquals(listOf("RE-2026-0001", "42"), message.argsList())
    }

    @Test
    fun `an empty args list round-trips to an empty list, not null`() {
        val message = aMessage(args = emptyList())
        assertEquals(emptyList<String>(), message.argsList())
    }

    @Test
    fun `decodeArgs treats a blank column value as no args`() {
        assertEquals(emptyList<String>(), SystemMessage.decodeArgs(null))
        assertEquals(emptyList<String>(), SystemMessage.decodeArgs(""))
    }
}
