package de.littletrotter.application.message

import de.littletrotter.application.dto.RecordSystemMessageCommand
import de.littletrotter.domain.message.SystemMessageContent
import de.littletrotter.domain.message.SystemMessageSeverity
import de.littletrotter.domain.message.SystemMessageSource
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class RecordSystemMessageTest {

    private val service: MessageCenterService = mock()
    private val useCase = RecordSystemMessage(service)

    @Test
    fun `record hands the command fields to the message center unchanged`() {
        useCase.record(
            RecordSystemMessageCommand(
                severity = SystemMessageSeverity.WARNING,
                source = SystemMessageSource.EXPORT,
                messageKey = "export.failed",
                args = listOf("a", "b"),
                resolvedText = "Export failed",
            ),
        )

        verify(service).record(
            SystemMessageContent(
                severity = SystemMessageSeverity.WARNING,
                source = SystemMessageSource.EXPORT,
                messageKey = "export.failed",
                args = listOf("a", "b"),
                resolvedText = "Export failed",
            ),
        )
    }
}
