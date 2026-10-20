package de.littletrotter.application.message

import de.littletrotter.application.message.port.inbound.MarkMessageReadUseCase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Implements [MarkMessageReadUseCase] on top of [MessageCenterService.markRead]. */
@ApplicationScoped
class MarkMessageRead @Inject constructor(
    private val service: MessageCenterService,
) : MarkMessageReadUseCase {
    override fun markRead(messageId: Long, userId: String) = service.markRead(messageId, userId)
}
