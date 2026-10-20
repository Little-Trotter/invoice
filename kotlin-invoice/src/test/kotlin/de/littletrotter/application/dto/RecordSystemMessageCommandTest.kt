package de.littletrotter.application.dto

import de.littletrotter.domain.message.SystemMessageSeverity
import de.littletrotter.domain.message.SystemMessageSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RecordSystemMessageCommandTest {

    @Test
    fun `args default to an empty list`() {
        val command = RecordSystemMessageCommand(
            severity = SystemMessageSeverity.INFO,
            source = SystemMessageSource.UI,
            messageKey = "key",
            resolvedText = "text",
        )

        assertEquals(emptyList<String>(), command.args)
    }

    @Test
    fun `explicit args are kept and copy preserves the remaining fields`() {
        val command = RecordSystemMessageCommand(
            severity = SystemMessageSeverity.WARNING,
            source = SystemMessageSource.EXPORT,
            messageKey = "key",
            args = listOf("a", "b"),
            resolvedText = "text",
        )

        val copy = command.copy(severity = SystemMessageSeverity.ERROR)

        assertEquals(listOf("a", "b"), copy.args)
        assertEquals("text", copy.resolvedText)
        assertEquals(SystemMessageSeverity.ERROR, copy.severity)
    }
}
