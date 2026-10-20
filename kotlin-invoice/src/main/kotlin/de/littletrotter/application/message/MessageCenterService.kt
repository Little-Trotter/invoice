package de.littletrotter.application.message

import de.littletrotter.application.shared.port.inbound.AuditActorQueryPort
import de.littletrotter.domain.message.SystemMessage
import de.littletrotter.domain.message.SystemMessageContent
import de.littletrotter.domain.message.SystemMessageReceipt
import de.littletrotter.domain.message.SystemMessageFilter
import de.littletrotter.domain.message.UnreadWindow
import de.littletrotter.domain.repository.SystemMessageRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Event
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import de.littletrotter.shared.time.TimeSource

/**
 * The Message Center's application-layer API: producers call [record], the
 * UI calls everything else. Deliberately has no producers or UI code itself — that scope
 * boundary is by design, not an omission.
 *
 * [time] is the application's injected [TimeSource], so unit tests can construct this service
 * over a fixed clock and the stamped timestamps are always UTC.
 * `littletrotter.message-center.retention-days` (default 90, PO-approved default) is read
 * once at construction, unit tests pass it explicitly like every other constructor argument here.
 */
@ApplicationScoped
@Transactional
class MessageCenterService @Inject constructor(
    private val repository: SystemMessageRepository,
    private val actorProvider: AuditActorQueryPort,
    private val time: TimeSource,
    @param:ConfigProperty(name = "littletrotter.message-center.retention-days", defaultValue = "90")
    private val retentionDays: Int,
    @param:ConfigProperty(name = "littletrotter.message-center.auto-read-hours", defaultValue = "24")
    private val autoReadHours: Int,
    @param:ConfigProperty(name = "littletrotter.message-center.error-auto-read-hours", defaultValue = "72")
    private val errorAutoReadHours: Int,
    private val recorded: Event<MessageRecorded>,
) {

    /**
     * Records a new [SystemMessage]: stamps [SystemMessage.occurredAt] from [time] and
     * [SystemMessage.actor] from [actorProvider] — callers never set either.
     * [recipientUserId] `null` (the default) means broadcast to every user; a non-null value
     * scopes the message to that one user's [unreadCountFor]/[find]/[markRead], invisible to
     * everyone else.
     *
     * This parameter is the seam a future producer uses once Authentik supplies real user
     * ids: e.g. a failed [de.littletrotter.application.invoice.GenerationJobService] job could
     * target the user who triggered it —
     * `record(SystemMessageContent(ERROR, GENERATION, "message.generationJob.failed", resolvedText =
     * "...", refType = "INVOICE", refId = invoiceId), recipientUserId = triggeringUserId)` — rather than
     * broadcasting a personal failure to every user. No producer does this yet (the actor is
     * whatever [de.littletrotter.application.shared.port.inbound.AuditActorQueryPort] resolves for the current
     * request — the real OIDC username since TS-2, or `"system"` in the dev bypass); the API is
     * ready, wiring it up is future work.
     */
    fun record(content: SystemMessageContent, recipientUserId: String? = null): SystemMessage {
        val message = SystemMessage.of(
            content = content,
            actor = actorProvider.currentActor(),
            occurredAt = time.nowUtc(),
            recipientUserId = recipientUserId,
        )
        val saved = repository.save(message)
        // Synchronous fire: transactional observers (after-success) only apply to a synchronously
        // fired event, and they run once this method's transaction has committed, so a listener
        // that re-reads the unread count always sees the new row.
        recorded.fire(MessageRecorded)
        return saved
    }

    /**
     * Records [content] unless a message with the same key about the same referenced entity is
     * already there (read or not), so a condition that is reported again and again shows up once.
     * Returns the new message, or `null` when it was a repeat. [SystemMessageContent.refType] and
     * [SystemMessageContent.refId] are required: they are what identifies the repeat.
     */
    fun recordOnce(content: SystemMessageContent, recipientUserId: String? = null): SystemMessage? {
        val refType = requireNotNull(content.refType) { "recordOnce needs a refType" }
        val refId = requireNotNull(content.refId) { "recordOnce needs a refId" }
        if (repository.existsByReference(content.messageKey, refType, refId)) return null
        return record(content, recipientUserId)
    }

    /** Withdraws the messages [recordOnce] created for [messageKey] about [refType]/[refId], e.g. once the cause is fixed. */
    fun withdraw(messageKey: String, refType: String, refId: String): Int =
        repository.deleteByReference(messageKey, refType, refId)

    /** Info and warnings stay unread for `auto-read-hours` (default 24), errors for
     *  `error-auto-read-hours` (default 72); older messages count as read without a receipt. */
    private fun unreadWindow(): UnreadWindow {
        val now = time.nowUtc()
        return UnreadWindow(now.minusHours(autoReadHours.toLong()), now.minusHours(errorAutoReadHours.toLong()))
    }

    /** Messages visible to [userId] (broadcast or targeted at them) that carry no read receipt
     *  for that user yet and are not aged out (see [unreadWindow]). */
    fun unreadCountFor(userId: String): Long = repository.unreadCountFor(userId, unreadWindow())

    /** Ids backing [unreadCountFor], exposed so the message-center grid can style unread
     *  rows without an N+1 [markRead]-style lookup per row: the caller fetches this once per grid
     *  refresh (same idiom as [de.littletrotter.ui.view.referencedata.B2bProfilesTab]'s
     *  `usageCounts`) and checks membership per row instead of querying per message. */
    fun unreadMessageIds(userId: String): Set<Long> = repository.unreadMessageIdsFor(userId, unreadWindow()).toSet()

    /** Paged, scoped to what [userId] may see; [filter] narrows by severity/source/time range/
     *  unread-only on top of that visibility scope. */
    fun find(userId: String, filter: SystemMessageFilter = SystemMessageFilter.EMPTY, pageIndex: Int = 0, pageSize: Int = 60): List<SystemMessage> =
        repository.find(userId, withWindow(filter), pageIndex, pageSize)

    /** Row count matching [find] — required for correct paging in the message center grid. */
    fun count(userId: String, filter: SystemMessageFilter = SystemMessageFilter.EMPTY): Long =
        repository.count(userId, withWindow(filter))

    private fun withWindow(filter: SystemMessageFilter): SystemMessageFilter =
        if (filter.unreadOnly) filter.copy(unreadWindow = unreadWindow()) else filter

    /** Idempotent: a message already marked read for [userId] is left untouched (first read_at
     *  wins), no constraint violation on a second call. */
    fun markRead(messageId: Long, userId: String) {
        if (repository.findReceipt(messageId, userId) != null) return
        repository.saveReceipt(SystemMessageReceipt.of(messageId, userId, time.nowUtc()))
    }

    /** Marks every message currently visible-and-unread for [userId] as read in one pass. Only
     *  touches messages [userId] may actually see — [SystemMessageRepository.unreadMessageIdsFor]
     *  already applies the same visibility scope [unreadCountFor] does. */
    fun markAllRead(userId: String) {
        val now = time.nowUtc()
        repository.unreadMessageIdsFor(userId, unreadWindow()).forEach { messageId ->
            repository.saveReceipt(SystemMessageReceipt.of(messageId, userId, now))
        }
    }

    /**
     * Retention cleanup — deletes every message older than `retentionDays`
     * (`littletrotter.message-center.retention-days`, default 90). Receipts of a deleted message
     * cascade at the DB (V918's `ON DELETE CASCADE`), not here. Triggered daily by
     * [de.littletrotter.infrastructure.persistence.SystemMessageRetentionScheduler]. Returns the
     * number of messages deleted.
     */
    fun cleanup(): Int {
        val cutoff = time.nowUtc().minusDays(retentionDays.toLong())
        return repository.deleteOlderThan(cutoff)
    }
}
