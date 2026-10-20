package de.littletrotter.application.message

import de.littletrotter.application.dto.MessageCenterFilter
import de.littletrotter.application.dto.SystemMessageView
import de.littletrotter.application.message.port.inbound.MessageCenterQueryPort
import de.littletrotter.domain.message.SystemMessage
import de.littletrotter.domain.message.SystemMessageFilter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Implements [MessageCenterQueryPort] on top of [MessageCenterService]'s reads. */
@ApplicationScoped
class MessageCenterQueries @Inject constructor(
    private val service: MessageCenterService,
) : MessageCenterQueryPort {

    override fun find(userId: String, filter: MessageCenterFilter, pageIndex: Int, pageSize: Int): List<SystemMessageView> =
        service.find(userId, filter.toDomain(), pageIndex, pageSize).map { it.toView() }

    override fun count(userId: String, filter: MessageCenterFilter): Long = service.count(userId, filter.toDomain())

    override fun unreadMessageIds(userId: String): Set<Long> = service.unreadMessageIds(userId)

    override fun unreadCountFor(userId: String): Long = service.unreadCountFor(userId)

    private fun MessageCenterFilter.toDomain(): SystemMessageFilter = SystemMessageFilter(
        severity = severity,
        source = source,
        from = from,
        to = to,
        unreadOnly = unreadOnly,
    )

    private fun SystemMessage.toView(): SystemMessageView = SystemMessageView(
        id = id,
        occurredAt = occurredAt,
        severity = severity,
        source = source,
        messageKey = messageKey,
        args = argsList(),
        resolvedText = resolvedText,
        refType = refType,
        refId = refId,
    )
}
