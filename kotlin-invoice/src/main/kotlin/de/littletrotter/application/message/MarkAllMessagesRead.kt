package de.littletrotter.application.message

import de.littletrotter.application.message.port.inbound.MarkAllMessagesReadUseCase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Implements [MarkAllMessagesReadUseCase] on top of [MessageCenterService.markAllRead]. */
@ApplicationScoped
class MarkAllMessagesRead @Inject constructor(
    private val service: MessageCenterService,
) : MarkAllMessagesReadUseCase {
    override fun markAllRead(userId: String) = service.markAllRead(userId)
}
