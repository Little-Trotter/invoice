package de.littletrotter.application.message.port.inbound

import de.littletrotter.application.dto.MessageCenterFilter
import de.littletrotter.application.dto.SystemMessageView

/**
 * Input port for reading the message center: the paged, filtered rows a user may see and the
 * ids among them that are still unread for that user.
 */
interface MessageCenterQueryPort {
    /** Paged, scoped to what [userId] may see; [filter] narrows on top of that visibility scope. */
    fun find(userId: String, filter: MessageCenterFilter, pageIndex: Int, pageSize: Int): List<SystemMessageView>

    /** Row count matching [find] — required for correct paging. */
    fun count(userId: String, filter: MessageCenterFilter): Long

    /** Ids of the messages visible to [userId] that carry no read receipt yet and are not aged out. */
    fun unreadMessageIds(userId: String): Set<Long>

    /** Number of messages visible to [userId] that are still unread; drives the bell badge. */
    fun unreadCountFor(userId: String): Long
}
