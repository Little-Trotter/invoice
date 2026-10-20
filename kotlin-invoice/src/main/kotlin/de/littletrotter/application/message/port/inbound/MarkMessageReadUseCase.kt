package de.littletrotter.application.message.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/** Input port for marking one message read for one user; idempotent. */
interface MarkMessageReadUseCase : CommandEntryPoint {
    fun markRead(messageId: Long, userId: String)
}
