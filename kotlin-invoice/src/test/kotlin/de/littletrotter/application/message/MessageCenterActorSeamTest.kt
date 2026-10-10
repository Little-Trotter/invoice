package de.littletrotter.application.message

import de.littletrotter.domain.message.UnreadWindow
import org.mockito.kotlin.mock
import de.littletrotter.domain.message.SystemMessageContent
import de.littletrotter.application.shared.AuditActorProvider
import de.littletrotter.domain.message.SystemMessage
import de.littletrotter.domain.message.SystemMessageFilter
import de.littletrotter.domain.message.SystemMessageReceipt
import de.littletrotter.domain.message.SystemMessageSeverity
import de.littletrotter.domain.message.SystemMessageSource
import de.littletrotter.domain.repository.SystemMessageRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Proves the message center is multi-user-ready *today*, without wiring Authentik:
 * every user-visible query goes through [AuditActorProvider.currentActor], never a hardcoded
 * actor id, so dropping in an `OidcAuditActorProvider` later (see the auth setup guide) scopes
 * messages, receipts and the unread badge per real user without touching [MessageCenterService]
 * or its callers.
 *
 * [aliceSession]/[bobSession] model the two call sites production code actually uses
 * ([de.littletrotter.ui.MainLayout.refreshBellBadge], [de.littletrotter.ui.view.message
 * .MessageCenterView.actorId]): `service.<query>(actorProvider.currentActor())`. Both sessions
 * share one [MessageCenterService]/[SystemMessageRepository] — same as production, where two
 * users hit the same CDI singletons through two different request-scoped
 * [AuditActorProvider] instances.
 */
class MessageCenterActorSeamTest {

    private val fixedInstant: Instant = Instant.parse("2026-08-25T12:00:00Z")
    private val clock: de.littletrotter.shared.time.TimeSource = de.littletrotter.shared.time.ClockTimeSource(Clock.fixed(fixedInstant, ZoneOffset.UTC))
    private lateinit var repository: InMemorySystemMessageRepository
    private lateinit var service: MessageCenterService
    private lateinit var aliceSession: UserSession
    private lateinit var bobSession: UserSession

    @BeforeEach
    fun setUp() {
        repository = InMemorySystemMessageRepository()
        service = MessageCenterService(repository, TestActorProvider("system"), clock, 90, 24, 72, mock())
        aliceSession = UserSession(service, TestActorProvider("alice"))
        bobSession = UserSession(service, TestActorProvider("bob"))
    }

    // ── broadcast: visible to both ───────────────────────────────────────────────────────────

    @Test
    fun `a broadcast message is visible to both alice and bob`() {
        service.record(
            SystemMessageContent(
                severity = SystemMessageSeverity.INFO,
                source = SystemMessageSource.SYSTEM,
                messageKey = "message.k",
                resolvedText = "text",
            ),
            // recipientUserId defaults to null: broadcast.
        )

        assertEquals(1L, aliceSession.unreadCount())
        assertEquals(1L, bobSession.unreadCount())
        assertEquals(1, aliceSession.find().size)
        assertEquals(1, bobSession.find().size)
    }

    // ── targeted recipient: invisible to the other user, in both unreadCountFor and find ──────

    @Test
    fun `a message targeted at alice is invisible to bob in unreadCountFor`() {
        service.record(
            SystemMessageContent(
                severity = SystemMessageSeverity.WARNING,
                source = SystemMessageSource.INVOICE,
                messageKey = "message.k",
                resolvedText = "text",
            ),
            recipientUserId = "alice",
        )

        assertEquals(1L, aliceSession.unreadCount())
        assertEquals(0L, bobSession.unreadCount(), "a message targeted at alice must not count toward bob's unread badge")
    }

    @Test
    fun `a message targeted at alice is invisible to bob in find`() {
        service.record(
            SystemMessageContent(
                severity = SystemMessageSeverity.WARNING,
                source = SystemMessageSource.INVOICE,
                messageKey = "message.k",
                resolvedText = "text",
            ),
            recipientUserId = "alice",
        )

        assertEquals(1, aliceSession.find().size)
        assertTrue(bobSession.find().isEmpty(), "a message targeted at alice must not appear in bob's message center grid")
    }

    // ── receipts are independent per user ───────────────────────────────────────────────────

    @Test
    fun `alice marking a broadcast message read does not mark bob's view read`() {
        service.record(
            SystemMessageContent(
                severity = SystemMessageSeverity.INFO,
                source = SystemMessageSource.SYSTEM,
                messageKey = "message.k",
                resolvedText = "text",
            ),
        )

        aliceSession.markAllRead()

        assertEquals(0L, aliceSession.unreadCount())
        assertEquals(1L, bobSession.unreadCount(), "alice's receipt must not be visible as bob's receipt")
    }

    @Test
    fun `alice marking one message read by id does not affect bob's receipt for the same message`() {
        val broadcast = service.record(
            SystemMessageContent(
                severity = SystemMessageSeverity.INFO,
                source = SystemMessageSource.SYSTEM,
                messageKey = "message.k",
                resolvedText = "text",
            ),
        )

        aliceSession.markRead(broadcast.id!!)

        assertEquals(0L, aliceSession.unreadCount())
        assertEquals(1L, bobSession.unreadCount())
    }

    // ── record's actor stamp also comes from the seam, not a hardcoded value ──────────────────

    @Test
    fun `record stamps the actor from whichever AuditActorProvider recorded it`() {
        val recordedByAlice = MessageCenterService(repository, TestActorProvider("alice"), clock, 90, 24, 72, mock()).record(
            SystemMessageContent(
                severity = SystemMessageSeverity.INFO,
                source = SystemMessageSource.SYSTEM,
                messageKey = "message.k",
                resolvedText = "text",
            ),
        )
        assertEquals("alice", recordedByAlice.actor)
    }

    // ── test fixtures ─────────────────────────────────────────────────────────────────────────

    /** A test-scoped seam: a fixed-return [AuditActorProvider], the constructor-injection
     *  equivalent of a CDI alternative that would later be `OidcAuditActorProvider`. */
    private class TestActorProvider(private val actor: String) : AuditActorProvider {
        override fun currentActor(): String = actor
    }

    /** Mirrors the two production call sites — [de.littletrotter.ui.MainLayout.refreshBellBadge]
     *  and [de.littletrotter.ui.view.message.MessageCenterView]'s `actorId()` — which always
     *  derive the user id from `actorProvider.currentActor()` right before calling into
     *  [MessageCenterService]. */
    private class UserSession(private val service: MessageCenterService, private val actorProvider: AuditActorProvider) {
        private fun actorId(): String = actorProvider.currentActor()
        fun unreadCount(): Long = service.unreadCountFor(actorId())
        fun find(filter: SystemMessageFilter = SystemMessageFilter.EMPTY): List<SystemMessage> = service.find(actorId(), filter)
        fun markRead(messageId: Long) = service.markRead(messageId, actorId())
        fun markAllRead() = service.markAllRead(actorId())
    }

    /** Same in-memory fake shape as [MessageCenterServiceTest]'s — duplicated on purpose (that
     *  test's is `private`), not shared: keeps this seam test readable as a single self-contained
     *  file. */
    private class InMemorySystemMessageRepository : SystemMessageRepository {
        private val messages = linkedMapOf<Long, SystemMessage>()
        private val receipts = linkedMapOf<Long, SystemMessageReceipt>()
        private var nextMessageId = 1L
        private var nextReceiptId = 1L

        override fun save(message: SystemMessage): SystemMessage {
            if (message.id == null) message.id = nextMessageId++
            messages[message.id!!] = message
            return message
        }

        override fun findById(id: Long): SystemMessage? = messages[id]

        private fun visible(userId: String): List<SystemMessage> =
            messages.values.filter { it.recipientUserId == null || it.recipientUserId == userId }

        private fun isRead(messageId: Long, userId: String): Boolean =
            receipts.values.any { it.messageId == messageId && it.userId == userId }

        override fun find(userId: String, filter: SystemMessageFilter, pageIndex: Int, pageSize: Int): List<SystemMessage> {
            var result = visible(userId)
            if (filter.severity.isNotEmpty()) result = result.filter { it.severity in filter.severity }
            if (filter.source.isNotEmpty()) result = result.filter { it.source in filter.source }
            filter.from?.let { f -> result = result.filter { !it.occurredAt.isBefore(f) } }
            filter.to?.let { t -> result = result.filter { !it.occurredAt.isAfter(t) } }
            if (filter.unreadOnly) result = result.filter { !isRead(it.id!!, userId) && filter.unreadWindow!!.covers(it.severity, it.occurredAt) }
            return result
                .sortedWith(compareByDescending<SystemMessage> { it.occurredAt }.thenByDescending { it.id })
                .drop(pageIndex * pageSize)
                .take(pageSize)
        }

        override fun count(userId: String, filter: SystemMessageFilter): Long =
            find(userId, filter, 0, Int.MAX_VALUE).size.toLong()

        override fun unreadCountFor(userId: String, window: UnreadWindow): Long = unreadIdsFor(userId, window).size.toLong()

        override fun unreadMessageIdsFor(userId: String, window: UnreadWindow): List<Long> = unreadIdsFor(userId, window)

        private fun unreadIdsFor(userId: String, window: UnreadWindow): List<Long> =
            visible(userId).filter { !isRead(it.id!!, userId) && window.covers(it.severity, it.occurredAt) }.map { it.id!! }

        override fun findReceipt(messageId: Long, userId: String): SystemMessageReceipt? =
            receipts.values.find { it.messageId == messageId && it.userId == userId }

        override fun saveReceipt(receipt: SystemMessageReceipt): SystemMessageReceipt {
            if (receipt.id == null) receipt.id = nextReceiptId++
            receipts[receipt.id!!] = receipt
            return receipt
        }

        override fun existsByReference(messageKey: String, refType: String, refId: String): Boolean =
            messages.values.any { it.messageKey == messageKey && it.refType == refType && it.refId == refId }

        override fun deleteByReference(messageKey: String, refType: String, refId: String): Int {
            val ids = messages.values.filter { it.messageKey == messageKey && it.refType == refType && it.refId == refId }.map { it.id!! }
            ids.forEach { messages.remove(it) }
            return ids.size
        }

        override fun deleteOlderThan(cutoff: OffsetDateTime): Int {
            val toDelete = messages.values.filter { it.occurredAt.isBefore(cutoff) }.map { it.id!! }
            toDelete.forEach { id ->
                messages.remove(id)
                receipts.values.filter { it.messageId == id }.map { it.id!! }.forEach { receipts.remove(it) }
            }
            return toDelete.size
        }
    }
}
