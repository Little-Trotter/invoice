package de.littletrotter.application.message

import de.littletrotter.application.dto.RecordSystemMessageCommand
import de.littletrotter.application.message.port.inbound.RecordSystemMessageUseCase
import de.littletrotter.domain.message.SystemMessageContent
import io.quarkus.arc.Unremovable
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Implements [RecordSystemMessageUseCase] on top of [MessageCenterService.record]. The toast helper
 * is a plain object that looks the port up programmatically, so the container must not drop this
 * bean as unused.
 */
@Unremovable
@ApplicationScoped
class RecordSystemMessage @Inject constructor(
    private val service: MessageCenterService,
) : RecordSystemMessageUseCase {
    override fun record(command: RecordSystemMessageCommand) {
        service.record(
            SystemMessageContent(
                severity = command.severity,
                source = command.source,
                messageKey = command.messageKey,
                args = command.args,
                resolvedText = command.resolvedText,
            ),
        )
    }
}
