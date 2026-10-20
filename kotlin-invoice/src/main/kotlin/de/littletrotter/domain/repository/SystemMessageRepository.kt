package de.littletrotter.domain.repository

import de.littletrotter.domain.message.SystemMessage
import de.littletrotter.domain.message.SystemMessageReceipt
import de.littletrotter.domain.message.SystemMessageFilter
import de.littletrotter.domain.message.UnreadWindow
import java.time.OffsetDateTime

interface SystemMessageRepository {
    fun save(message: SystemMessage): SystemMessage
    fun findById(id: Long): SystemMessage?

    /** Messages visible to [userId] (broadcast or targeted at them), narrowed by [filter], newest
     *  first, paged. */
    fun find(
        userId: String,
        filter: SystemMessageFilter = SystemMessageFilter.EMPTY,
        pageIndex: Int = 0,
        pageSize: Int = 60,
    ): List<SystemMessage>

    /** Row count for the same visibility scope and [filter] [find] applies. */
    fun count(userId: String, filter: SystemMessageFilter = SystemMessageFilter.EMPTY): Long

    /** Messages visible to [userId] that carry no receipt for that user yet and are still inside
     *  [window] — one query, no N+1. */
    fun unreadCountFor(userId: String, window: UnreadWindow): Long

    /** Ids backing [unreadCountFor] — same predicate, used by `markAllRead` to insert exactly the
     *  missing receipts. */
    fun unreadMessageIdsFor(userId: String, window: UnreadWindow): List<Long>

    fun findReceipt(messageId: Long, userId: String): SystemMessageReceipt?
    /** Idempotent: an existing receipt for the same message and user is kept, never duplicated. */
    fun saveReceipt(receipt: SystemMessageReceipt): SystemMessageReceipt

    /** Deletes every message with `occurredAt < cutoff`; receipts cascade at the DB
     *  (`ON DELETE CASCADE`, V918). Returns the number of messages deleted. */
    fun deleteOlderThan(cutoff: OffsetDateTime): Int

    /** Whether a message with this [messageKey] about the entity [refType]/[refId] exists, read or not. */
    fun existsByReference(messageKey: String, refType: String, refId: String): Boolean

    /** Deletes every message with this [messageKey] about [refType]/[refId]; receipts cascade. Returns the count. */
    fun deleteByReference(messageKey: String, refType: String, refId: String): Int
}
