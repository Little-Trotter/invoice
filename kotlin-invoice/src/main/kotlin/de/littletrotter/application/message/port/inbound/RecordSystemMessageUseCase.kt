package de.littletrotter.application.message.port.inbound

import de.littletrotter.application.command.CommandEntryPoint
import de.littletrotter.application.dto.RecordSystemMessageCommand

/** Input port for the UI to put a message into the message center, e.g. a shown error toast. */
interface RecordSystemMessageUseCase : CommandEntryPoint {
    /** Records the message in [command] for everyone. */
    fun record(command: RecordSystemMessageCommand)
}
