package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.message.UnreadWindow
import de.littletrotter.domain.message.SystemMessageContent
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.message.SystemMessage
import de.littletrotter.domain.message.SystemMessageFilter
import de.littletrotter.domain.message.SystemMessageReceipt
import de.littletrotter.domain.message.SystemMessageSeverity
import de.littletrotter.domain.message.SystemMessageSource
import de.littletrotter.domain.repository.SystemMessageRepository
import de.littletrotter.fixture.edit
import io.quarkus.test.TestTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * [PanacheSystemMessageRepository] against a real Testcontainers Postgres.
 *
 * `system_message`/`system_message_receipt` are shared with other `@QuarkusTest` classes in this
 * module (e.g. [SystemMessageRetentionSchedulerIT] commits a broadcast row of its own), and every
 * read here is `recipientUserId IS NULL OR recipientUserId = :userId` — a broadcast row from any
 * other test always matches every query regardless of `userId`. So absolute counts/lists are
 * never asserted: each test uses a fresh random `userId`, filters the returned rows down to its
 * own known ids before comparing order, or compares a before/after delta.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PanacheSystemMessageRepositoryIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var repository: SystemMessageRepository

    private fun message(
        key: String,
        severity: SystemMessageSeverity,
        source: SystemMessageSource,
        occurredAt: OffsetDateTime,
        recipientUserId: String?,
    ): SystemMessage =
        SystemMessage.of(
            SystemMessageContent(
                severity = severity,
                source = source,
                messageKey = key,
                args = emptyList(),
                resolvedText = "COV-09 $key",
            ),
            actor = "system",
            occurredAt = occurredAt,
            recipientUserId = recipientUserId,
        ).also { em.persist(it) }

    private fun freshUserId(): String = "zzcov9-${UUID.randomUUID()}"

    @Test
    @TestTransaction
    fun `happy path find returns matching messages newest-first and count agrees`() {
        val userId = freshUserId()
        val now = OffsetDateTime.now()
        val old = message("zzcov9.old", SystemMessageSeverity.INFO, SystemMessageSource.SYSTEM, now.minusHours(2), userId)
        val mid = message("zzcov9.mid", SystemMessageSeverity.WARNING, SystemMessageSource.SYSTEM, now.minusHours(1), userId)
        val newest = message("zzcov9.new", SystemMessageSeverity.ERROR, SystemMessageSource.SYSTEM, now, userId)
        em.flush()

        val all = repository.find(userId, SystemMessageFilter.EMPTY, 0, 1000)
        val relevant = all.filter { it.id in setOf(old.id, mid.id, newest.id) }
        assertEquals(listOf(newest.id, mid.id, old.id), relevant.map { it.id }, "newest first")
        // Broadcast messages (no recipient) from other ITs are visible to this user too, so the
        // count is compared with the page, not with an absolute number.
        assertEquals(all.size.toLong(), repository.count(userId, SystemMessageFilter.EMPTY))
        assertTrue(all.size >= 3)

        val errorsOnly = repository.find(userId, SystemMessageFilter(severity = setOf(SystemMessageSeverity.ERROR)), 0, 1000)
        assertEquals(listOf(newest.id), errorsOnly.filter { it.id in setOf(old.id, mid.id, newest.id) }.map { it.id })
    }

    @Test
    @TestTransaction
    fun `boundary find and count return empty for a window that matches nothing`() {
        val userId = freshUserId()
        message("zzcov9.window", SystemMessageSeverity.INFO, SystemMessageSource.SYSTEM, OffsetDateTime.now(), userId)
        em.flush()

        val farFuture = OffsetDateTime.of(3000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        assertTrue(repository.find(userId, SystemMessageFilter(from = farFuture), 0, 10).isEmpty())
        assertEquals(0L, repository.count(userId, SystemMessageFilter(from = farFuture)))
    }

    @Test
    @TestTransaction
    fun `boundary broadcast messages are visible to every user while targeted ones are scoped`() {
        val userA = freshUserId()
        val userB = freshUserId()
        val broadcast = message("zzcov9.broadcast", SystemMessageSeverity.INFO, SystemMessageSource.SYSTEM, OffsetDateTime.now(), null)
        val targeted = message("zzcov9.targeted", SystemMessageSeverity.INFO, SystemMessageSource.SYSTEM, OffsetDateTime.now(), userA)
        em.flush()

        val forA = repository.find(userA, SystemMessageFilter.EMPTY, 0, 5000).map { it.id }
        val forB = repository.find(userB, SystemMessageFilter.EMPTY, 0, 5000).map { it.id }

        assertTrue(broadcast.id in forA)
        assertTrue(targeted.id in forA)
        assertTrue(broadcast.id in forB, "broadcast visible to every user")
        assertFalse(targeted.id in forB, "targeted message stays scoped to its recipient")
    }

    @Test
    @TestTransaction
    fun `saveReceipt is idempotent and keeps the first read_at on a second call`() {
        val userId = freshUserId()
        val msg = message("zzcov9.twice", SystemMessageSeverity.INFO, SystemMessageSource.SYSTEM, OffsetDateTime.now(), userId)
        em.flush()
        val first = OffsetDateTime.now().minusHours(1)

        val a = repository.saveReceipt(SystemMessageReceipt.of(msg.id!!, userId, first))
        val b = repository.saveReceipt(SystemMessageReceipt.of(msg.id!!, userId, OffsetDateTime.now()))

        assertEquals(a.id, b.id)
        assertEquals(1L, (em.createNativeQuery("SELECT COUNT(*) FROM system_message_receipt WHERE message_id = :m AND user_id = :u")
            .setParameter("m", msg.id).setParameter("u", userId).singleResult as Number).toLong())
        assertEquals(first.toInstant().toEpochMilli(), b.readAt.toInstant().toEpochMilli())
    }

    private fun window(): UnreadWindow {
        val now = OffsetDateTime.now()
        return UnreadWindow(now.minusHours(24), now.minusHours(72))
    }

    @Test
    @TestTransaction
    fun `unread window ages out info after 24 h and errors after 72 h and ignores receipts`() {
        val userId = freshUserId()
        val now = OffsetDateTime.now()
        val baselineUnread = repository.unreadCountFor(userId, window())
        val oldInfo = message("zzaging.info25", SystemMessageSeverity.INFO, SystemMessageSource.SYSTEM, now.minusHours(25), userId)
        val freshWarning = message("zzaging.warn23", SystemMessageSeverity.WARNING, SystemMessageSource.SYSTEM, now.minusHours(23), userId)
        val freshError = message("zzaging.err71", SystemMessageSeverity.ERROR, SystemMessageSource.SYSTEM, now.minusHours(71), userId)
        val oldError = message("zzaging.err73", SystemMessageSeverity.ERROR, SystemMessageSource.SYSTEM, now.minusHours(73), userId)
        val readWarning = message("zzaging.read", SystemMessageSeverity.WARNING, SystemMessageSource.SYSTEM, now.minusHours(1), userId)
        em.flush()
        repository.saveReceipt(SystemMessageReceipt.of(readWarning.id!!, userId, now))
        em.flush()

        val ownIds = setOf(oldInfo.id, freshWarning.id, freshError.id, oldError.id, readWarning.id)
        val unread = repository.unreadMessageIdsFor(userId, window()).toSet()

        assertEquals(setOf(freshWarning.id, freshError.id), unread.filter { it in ownIds }.toSet())
        assertEquals(baselineUnread + 2L, repository.unreadCountFor(userId, window()))
        val unreadOnly = repository.find(userId, SystemMessageFilter(unreadOnly = true, unreadWindow = window()), 0, 5000).map { it.id }.toSet()
        assertEquals(unread, unreadOnly)
        assertFalse(oldInfo.id in unread)
        assertFalse(oldError.id in unread)
    }

    @Test
    @TestTransaction
    fun `boundary unread tracking flips to read once a receipt is saved`() {
        val userId = freshUserId()
        val msg = message("zzcov9.unread", SystemMessageSeverity.INFO, SystemMessageSource.SYSTEM, OffsetDateTime.now(), userId)
        em.flush()

        assertTrue(msg.id in repository.unreadMessageIdsFor(userId, window()))
        val before = repository.unreadCountFor(userId, window())
        assertNull(repository.findReceipt(msg.id!!, userId))

        repository.saveReceipt(SystemMessageReceipt.of(msg.id!!, userId, OffsetDateTime.now()))
        em.flush()

        assertNotNull(repository.findReceipt(msg.id!!, userId))
        assertFalse(msg.id in repository.unreadMessageIdsFor(userId, window()))
        assertEquals(before - 1, repository.unreadCountFor(userId, window()))
    }

    @Test
    @TestTransaction
    fun `boundary deleteOlderThan removes only messages strictly before the cutoff`() {
        val userId = freshUserId()
        val cutoff = OffsetDateTime.now()
        val old = message("zzcov9.old2", SystemMessageSeverity.INFO, SystemMessageSource.SYSTEM, cutoff.minusDays(1), userId)
        val kept = message("zzcov9.kept", SystemMessageSeverity.INFO, SystemMessageSource.SYSTEM, cutoff.plusDays(1), userId)
        em.flush()

        val deleted = repository.deleteOlderThan(cutoff)

        assertTrue(deleted >= 1)
        em.clear()
        assertNull(em.find(SystemMessage::class.java, old.id))
        assertNotNull(em.find(SystemMessage::class.java, kept.id))
    }

    @Test
    @TestTransaction
    fun `happy path save persists via the domain factory and findById round-trips`() {
        val msg = SystemMessage.of(
            SystemMessageContent(
                severity = SystemMessageSeverity.WARNING,
                source = SystemMessageSource.VALIDATION,
                messageKey = "zzcov9.saved",
                args = listOf("a"),
                resolvedText = "COV-09 saved",
            ),
            actor = "system",
            occurredAt = OffsetDateTime.now(),
        )

        val saved = repository.save(msg)

        assertNotNull(saved.id)
        assertEquals("zzcov9.saved", repository.findById(saved.id!!)?.messageKey)
        assertNull(repository.findById(Long.MAX_VALUE))
    }

    private fun referenced(key: String, refType: String, refId: String): SystemMessage =
        SystemMessage.of(
            SystemMessageContent(
                severity = SystemMessageSeverity.WARNING,
                source = SystemMessageSource.SYSTEM,
                messageKey = key,
                resolvedText = "reference $key",
                refType = refType,
                refId = refId,
            ),
            actor = "system",
            occurredAt = OffsetDateTime.now(),
            recipientUserId = null,
        ).also { em.persist(it) }

    @Test
    @TestTransaction
    fun `a message is found and deleted by key and referenced entity only`() {
        val key = "zzref.${UUID.randomUUID()}"
        referenced(key, "MAIL_JOURNAL", "41")
        referenced(key, "MAIL_JOURNAL", "42")
        em.flush()

        assertTrue(repository.existsByReference(key, "MAIL_JOURNAL", "41"))
        assertFalse(repository.existsByReference(key, "MAIL_JOURNAL", "43"))
        assertFalse(repository.existsByReference("other.$key", "MAIL_JOURNAL", "41"))

        assertEquals(1, repository.deleteByReference(key, "MAIL_JOURNAL", "41"))
        assertFalse(repository.existsByReference(key, "MAIL_JOURNAL", "41"))
        assertTrue(repository.existsByReference(key, "MAIL_JOURNAL", "42"))
    }

    @Test
    @TestTransaction
    fun `retention spares standing messages and removes other old ones`() {
        val old = OffsetDateTime.now().minusDays(400)
        val standing = referenced("zzstanding.${UUID.randomUUID()}", SystemMessage.STANDING_REF_TYPE, "ADDRESS")
            .edit { occurredAt = old }
        val plain = referenced("zzplain.${UUID.randomUUID()}", "MAIL_JOURNAL", "1").edit { occurredAt = old }
        em.flush()

        repository.deleteOlderThan(OffsetDateTime.now().minusDays(90))
        em.clear()

        assertNotNull(repository.findById(standing.id!!))
        assertNull(repository.findById(plain.id!!))
    }
}
