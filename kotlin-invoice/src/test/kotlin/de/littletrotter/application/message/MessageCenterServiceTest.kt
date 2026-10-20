package de.littletrotter.application.message

import de.littletrotter.domain.message.UnreadWindow
import de.littletrotter.fixture.edit
import org.mockito.kotlin.mock
import de.littletrotter.domain.message.SystemMessageContent
import de.littletrotter.application.shared.port.inbound.AuditActorQueryPort
import de.littletrotter.application.shared.SystemAuditActorProvider
import de.littletrotter.domain.message.SystemMessage
import de.littletrotter.domain.message.SystemMessageFilter
import de.littletrotter.domain.message.SystemMessageReceipt
import de.littletrotter.domain.message.SystemMessageSeverity
import de.littletrotter.domain.message.SystemMessageSource
import de.littletrotter.domain.repository.SystemMessageRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * [MessageCenterService] against an in-memory [SystemMessageRepository] fake, same
 * "private class in the test file" shape as [de.littletrotter.application.GenerationJobServiceTest].
 */
class MessageCenterServiceTest {

    private val fixedInstant: Instant = Instant.parse("2026-08-25T12:00:00Z")
    private val fixedClock: Clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
    private val clock: de.littletrotter.shared.time.TimeSource = de.littletrotter.shared.time.ClockTimeSource(fixedClock)
    private lateinit var repository: InMemorySystemMessageRepository
    private lateinit var actorProvider: AuditActorQueryPort
    private lateinit var service: MessageCenterService
    private val recorded = mock<jakarta.enterprise.event.Event<MessageRecorded>>()

    @BeforeEach
    fun setUp() {
        repository = InMemorySystemMessageRepository()
        actorProvider = SystemAuditActorProvider()
        service = MessageCenterService(repository, actorProvider, clock, 90, 24, 72, recorded)
    }

    // ── record ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `record stamps occurredAt from the clock and actor from the AuditActorQueryPort`() {
        val message = service.record(
            SystemMessageContent(
                severity = SystemMessageSeverity.INFO,
                source = SystemMessageSource.INVOICE,
                messageKey = "message.invoice.finalized",
                resolvedText = "Rechnung RE-2026-0001 wurde festgeschrieben.",
            ),
        )
        assertEquals(OffsetDateTime.ofInstant(fixedInstant, ZoneOffset.UTC), message.occurredAt)
        assertEquals("system", message.actor)
        assertEquals(1L, message.id)
    }

    private fun refContent(refId: String? = "ADDRESS", refType: String? = "ARCHIVE_HANDOVER") = SystemMessageContent(
        severity = SystemMessageSeverity.WARNING,
        source = SystemMessageSource.SYSTEM,
        messageKey = "message.center.archiveHandover.addressMissing",
        resolvedText = "x",
        refType = refType,
        refId = refId,
    )

    @Test
    fun `recordOnce records the first message and ignores a repeat about the same entity`() {
        assertEquals(1L, service.recordOnce(refContent())?.id)
        assertEquals(null, service.recordOnce(refContent()))
        assertEquals(2L, service.recordOnce(refContent(refId = "OTHER"))?.id)
        assertEquals(2, repository.messages.size)
    }

    @Test
    fun `recordOnce needs a reference to recognise a repeat`() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) { service.recordOnce(refContent(refType = null)) }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) { service.recordOnce(refContent(refId = null)) }
    }

    @Test
    fun `withdraw removes the message so the condition can be reported again`() {
        service.recordOnce(refContent())

        assertEquals(1, service.withdraw("message.center.archiveHandover.addressMissing", "ARCHIVE_HANDOVER", "ADDRESS"))
        assertEquals(0, repository.messages.size)
        assertEquals(2L, service.recordOnce(refContent())?.id)
    }

    @Test
    fun `record fires MessageRecorded once the message is saved`() {
        service.record(
            SystemMessageContent(
                severity = SystemMessageSeverity.INFO,
                source = SystemMessageSource.INVOICE,
                messageKey = "message.invoice.finalized",
                resolvedText = "x",
            ),
        )
        org.mockito.kotlin.verify(recorded).fire(MessageRecorded)
    }

    @Test
    fun `unread window is derived from the clock and the configured hours`() {
        val captured = mutableListOf<UnreadWindow>()
        val capturing = object : SystemMessageRepository by repository {
            override fun unreadCountFor(userId: String, window: UnreadWindow): Long {
                captured += window
                return 0
            }
        }
        MessageCenterService(capturing, actorProvider, clock, 90, 24, 72, recorded).unreadCountFor("alice")

        val now = OffsetDateTime.ofInstant(fixedInstant, ZoneOffset.UTC)
        assertEquals(UnreadWindow(now.minusHours(24), now.minusHours(72)), captured.single())
    }

    @Test
    fun `unread window covers a message just inside and not one just outside`() {
        val now = OffsetDateTime.ofInstant(fixedInstant, ZoneOffset.UTC)
        val window = UnreadWindow(now.minusHours(24), now.minusHours(72))

        assertTrue(window.covers(SystemMessageSeverity.WARNING, now.minusHours(23)))
        assertFalse(window.covers(SystemMessageSeverity.INFO, now.minusHours(25)))
        assertTrue(window.covers(SystemMessageSeverity.ERROR, now.minusHours(71)))
        assertFalse(window.covers(SystemMessageSeverity.ERROR, now.minusHours(73)))
    }

    @Test
    fun `record defaults recipientUserId to null (broadcast)`() {
        val message = service.record(
            SystemMessageContent(
                severity = SystemMessageSeverity.INFO,
                source = SystemMessageSource.SYSTEM,
                messageKey = "message.system.startup",
                resolvedText = "System gestartet.",
            ),
        )
        assertEquals(null, message.recipientUserId)
    }

    // ── unreadCountFor — broadcast/targeted-to-me/targeted-to-other × read/unread ───────────────

    private fun message(recipientUserId: String?): SystemMessage = repository.save(
        SystemMessage.of(
            SystemMessageContent(
                severity = SystemMessageSeverity.INFO,
                source = SystemMessageSource.SYSTEM,
                messageKey = "message.k",
                args = emptyList(),
                resolvedText = "text",
            ),
            actor = "system",
            occurredAt = OffsetDateTime.now(fixedClock),
            recipientUserId = recipientUserId,
        )
    )

    @Test
    fun `unreadCountFor counts an unread broadcast message`() {
        message(recipientUserId = null)
        assertEquals(1L, service.unreadCountFor("alice"))
    }

    @Test
    fun `unreadCountFor counts an unread message targeted at the user`() {
        message(recipientUserId = "alice")
        assertEquals(1L, service.unreadCountFor("alice"))
    }

    @Test
    fun `unreadCountFor excludes a message targeted at a different user`() {
        message(recipientUserId = "bob")
        assertEquals(0L, service.unreadCountFor("alice"))
    }

    @Test
    fun `unreadCountFor excludes a read broadcast message`() {
        val m = message(recipientUserId = null)
        service.markRead(m.id!!, "alice")
        assertEquals(0L, service.unreadCountFor("alice"))
    }

    @Test
    fun `unreadCountFor excludes a read targeted-to-me message`() {
        val m = message(recipientUserId = "alice")
        service.markRead(m.id!!, "alice")
        assertEquals(0L, service.unreadCountFor("alice"))
    }

    @Test
    fun `unreadCountFor is unaffected by another user's receipt on a broadcast message`() {
        val m = message(recipientUserId = null)
        service.markRead(m.id!!, "bob")
        assertEquals(1L, service.unreadCountFor("alice"))
    }

    @Test
    fun `the full broadcast-targeted x read-unread matrix`() {
        val broadcast = message(recipientUserId = null)
        val toMe = message(recipientUserId = "alice")
        val toOther = message(recipientUserId = "bob")

        // All three unread: broadcast + toMe count, toOther doesn't.
        assertEquals(2L, service.unreadCountFor("alice"))

        // Read the broadcast and the targeted-to-me message; toOther was never visible anyway.
        service.markRead(broadcast.id!!, "alice")
        service.markRead(toMe.id!!, "alice")
        assertEquals(0L, service.unreadCountFor("alice"))

        // Bob still sees his own targeted message and the (still-unread-for-him) broadcast.
        assertEquals(2L, service.unreadCountFor("bob"))
    }

    // ── markRead idempotency ──────────────────────────────────────────────────────────────────

    @Test
    fun `markRead is idempotent - a second call does not throw and does not duplicate the receipt`() {
        val m = message(recipientUserId = null)
        service.markRead(m.id!!, "alice")
        service.markRead(m.id!!, "alice")
        assertEquals(1, repository.receiptCountFor(m.id!!, "alice"))
    }

    @Test
    fun `markRead keeps the first receipt's readAt on a second call`() {
        val m = message(recipientUserId = null)
        service.markRead(m.id!!, "alice")
        val firstReadAt = repository.findReceipt(m.id!!, "alice")!!.readAt
        service.markRead(m.id!!, "alice")
        assertEquals(firstReadAt, repository.findReceipt(m.id!!, "alice")!!.readAt)
    }

    // ── markAllRead ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `markAllRead marks every visible unread message for the user, and only those`() {
        val broadcast = message(recipientUserId = null)
        val toMe = message(recipientUserId = "alice")
        val toOther = message(recipientUserId = "bob")

        service.markAllRead("alice")

        assertTrue(repository.receiptCountFor(broadcast.id!!, "alice") == 1)
        assertTrue(repository.receiptCountFor(toMe.id!!, "alice") == 1)
        assertEquals(0, repository.receiptCountFor(toOther.id!!, "alice"))
        assertEquals(0L, service.unreadCountFor("alice"))
        // Bob's own unread count is untouched by alice's markAllRead.
        assertEquals(2L, service.unreadCountFor("bob"))
    }

    // ── find/count with SystemMessageFilter ──────────────────────────────────────────────────

    @Test
    fun `find scopes to what the user may see`() {
        message(recipientUserId = null)
        message(recipientUserId = "alice")
        message(recipientUserId = "bob")

        assertEquals(2, service.find("alice").size)
        assertEquals(2L, service.count("alice", SystemMessageFilter.EMPTY))
    }

    @Test
    fun `boundary - count omitting the filter defaults it to EMPTY, same as an explicit SystemMessageFilter EMPTY`() {
        message(recipientUserId = null)
        message(recipientUserId = "alice")
        message(recipientUserId = "bob")

        assertEquals(2L, service.count("alice"))
    }

    @Test
    fun `boundary - unreadMessageIds returns the ids of the visible messages this user has not read yet`() {
        val toMe = message(recipientUserId = "alice")
        message(recipientUserId = "bob")

        val unread = service.unreadMessageIds("alice")

        assertEquals(setOf(toMe.id), unread)

        service.markRead(toMe.id!!, "alice")

        assertTrue(service.unreadMessageIds("alice").isEmpty())
    }

    @Test
    fun `find filters by severity and unreadOnly`() {
        val info = repository.save(
            SystemMessage.of(
                SystemMessageContent(
                    severity = SystemMessageSeverity.INFO,
                    source = SystemMessageSource.SYSTEM,
                    messageKey = "k",
                    args = emptyList(),
                    resolvedText = "t",
                ),
                actor = "system",
                occurredAt = OffsetDateTime.now(fixedClock),
            )
        )
        repository.save(
            SystemMessage.of(
                SystemMessageContent(
                    severity = SystemMessageSeverity.ERROR,
                    source = SystemMessageSource.SYSTEM,
                    messageKey = "k",
                    args = emptyList(),
                    resolvedText = "t",
                ),
                actor = "system",
                occurredAt = OffsetDateTime.now(fixedClock),
            )
        )
        service.markRead(info.id!!, "alice")

        val errorsOnly = service.find("alice", SystemMessageFilter(severity = setOf(SystemMessageSeverity.ERROR)))
        assertEquals(1, errorsOnly.size)
        assertEquals(SystemMessageSeverity.ERROR, errorsOnly.single().severity)

        val unreadOnly = service.find("alice", SystemMessageFilter(unreadOnly = true))
        assertEquals(1, unreadOnly.size)
        assertEquals(SystemMessageSeverity.ERROR, unreadOnly.single().severity)
    }

    // ── cleanup ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `cleanup deletes messages older than retentionDays and cascades their receipts`() {
        val old = repository.save(
            SystemMessage.of(
                SystemMessageContent(
                    severity = SystemMessageSeverity.INFO,
                    source = SystemMessageSource.SYSTEM,
                    messageKey = "k",
                    args = emptyList(),
                    resolvedText = "t",
                ),
                actor = "system",
                occurredAt = OffsetDateTime.now(fixedClock).minusDays(91),
            )
        )
        service.markRead(old.id!!, "alice")
        val young = repository.save(
            SystemMessage.of(
                SystemMessageContent(
                    severity = SystemMessageSeverity.INFO,
                    source = SystemMessageSource.SYSTEM,
                    messageKey = "k",
                    args = emptyList(),
                    resolvedText = "t",
                ),
                actor = "system",
                occurredAt = OffsetDateTime.now(fixedClock).minusDays(1),
            )
        )

        val deleted = service.cleanup()

        assertEquals(1, deleted)
        assertEquals(null, repository.findById(old.id!!))
        assertEquals(0, repository.receiptCountFor(old.id!!, "alice"), "receipt of a deleted message must cascade away")
        assertEquals(young, repository.findById(young.id!!))
    }

    @Test
    fun `cleanup keeps messages younger than retentionDays`() {
        repository.save(
            SystemMessage.of(
                SystemMessageContent(
                    severity = SystemMessageSeverity.INFO,
                    source = SystemMessageSource.SYSTEM,
                    messageKey = "k",
                    args = emptyList(),
                    resolvedText = "t",
                ),
                actor = "system",
                occurredAt = OffsetDateTime.now(fixedClock).minusDays(89),
            )
        )
        assertEquals(0, service.cleanup())
        assertFalse(repository.messages.isEmpty())
    }

    // ── in-memory fake ────────────────────────────────────────────────────────────────────────

    private class InMemorySystemMessageRepository : SystemMessageRepository {
        val messages = linkedMapOf<Long, SystemMessage>()
        private val receipts = linkedMapOf<Long, SystemMessageReceipt>()
        private var nextMessageId = 1L
        private var nextReceiptId = 1L

        override fun save(message: SystemMessage): SystemMessage {
            if (message.id == null) message.edit { id = nextMessageId++ }
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

        fun receiptCountFor(messageId: Long, userId: String): Int =
            receipts.values.count { it.messageId == messageId && it.userId == userId }

        override fun saveReceipt(receipt: SystemMessageReceipt): SystemMessageReceipt {
            if (receipt.id == null) receipt.edit { id = nextReceiptId++ }
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
                // Simulates V918's ON DELETE CASCADE — the real cascade is verified against
                // Postgres by SystemMessageMigrationIT.
                receipts.values.filter { it.messageId == id }.map { it.id!! }.forEach { receipts.remove(it) }
            }
            return toDelete.size
        }
    }
}
