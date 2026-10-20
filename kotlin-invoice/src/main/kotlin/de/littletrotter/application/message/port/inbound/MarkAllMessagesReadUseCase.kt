package de.littletrotter.application.message.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/** Input port for marking every message currently visible and unread for a user as read. */
interface MarkAllMessagesReadUseCase : CommandEntryPoint {
    fun markAllRead(userId: String)
}
