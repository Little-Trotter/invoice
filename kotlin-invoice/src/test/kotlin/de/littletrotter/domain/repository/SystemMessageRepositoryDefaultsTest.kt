package de.littletrotter.domain.repository

import de.littletrotter.domain.message.UnreadWindow
import de.littletrotter.domain.message.SystemMessage
import de.littletrotter.domain.message.SystemMessageFilter
import de.littletrotter.domain.message.SystemMessageReceipt
import de.littletrotter.domain.message.SystemMessageSeverity
import de.littletrotter.domain.message.SystemMessageSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * [SystemMessageRepository.find]/[SystemMessageRepository.count] declare default
 * parameter values (an empty [SystemMessageFilter], paging) without a body; Kotlin still compiles
 * a synthetic `$default` dispatcher into the interface for callers going through an
 * interface-typed reference with arguments omitted — covered the same way as
 * [B2bProfileRepositoryDefaultsTest].
 */
class SystemMessageRepositoryDefaultsTest {

    private class RecordingRepo : SystemMessageRepository {
        data class FindCall(
            val userId: String,
            val filter: SystemMessageFilter,
            val pageIndex: Int,
            val pageSize: Int,
        )
        var lastFind: FindCall? = null
        var lastCountFilter: SystemMessageFilter? = null

        override fun save(message: SystemMessage): SystemMessage = message
        override fun findById(id: Long): SystemMessage? = null
        override fun find(userId: String, filter: SystemMessageFilter, pageIndex: Int, pageSize: Int): List<SystemMessage> {
            lastFind = FindCall(userId, filter, pageIndex, pageSize)
            return emptyList()
        }
        override fun count(userId: String, filter: SystemMessageFilter): Long {
            lastCountFilter = filter
            return 0
        }
        override fun unreadCountFor(userId: String, window: UnreadWindow): Long = 0
        override fun unreadMessageIdsFor(userId: String, window: UnreadWindow): List<Long> = emptyList()
        override fun findReceipt(messageId: Long, userId: String): SystemMessageReceipt? = null
        override fun saveReceipt(receipt: SystemMessageReceipt): SystemMessageReceipt = receipt
        override fun deleteOlderThan(cutoff: OffsetDateTime): Int = 0
        override fun existsByReference(messageKey: String, refType: String, refId: String): Boolean = false
        override fun deleteByReference(messageKey: String, refType: String, refId: String): Int = 0
    }

    @Test
    fun `happy path find with every optional argument omitted uses the documented defaults`() {
        val repo = RecordingRepo()
        val ref: SystemMessageRepository = repo

        ref.find("user-1")

        assertEquals(RecordingRepo.FindCall("user-1", SystemMessageFilter.EMPTY, 0, 60), repo.lastFind)
    }

    @Test
    fun `boundary find with explicit severity and source sets is forwarded, not overwritten with empty`() {
        val repo = RecordingRepo()
        val ref: SystemMessageRepository = repo

        ref.find("user-1", SystemMessageFilter(severity = setOf(SystemMessageSeverity.ERROR), source = setOf(SystemMessageSource.SYSTEM)))

        assertEquals(setOf(SystemMessageSeverity.ERROR), repo.lastFind?.filter?.severity)
        assertEquals(setOf(SystemMessageSource.SYSTEM), repo.lastFind?.filter?.source)
    }

    @Test
    fun `boundary find with unreadOnly true is forwarded, not overwritten with false`() {
        val repo = RecordingRepo()
        val ref: SystemMessageRepository = repo

        ref.find("user-1", SystemMessageFilter(unreadOnly = true))

        assertEquals(true, repo.lastFind?.filter?.unreadOnly)
    }

    @Test
    fun `boundary find with explicit date bounds and paging is forwarded, not overwritten`() {
        val repo = RecordingRepo()
        val ref: SystemMessageRepository = repo
        val from = OffsetDateTime.parse("2026-01-01T00:00:00Z")
        val to = OffsetDateTime.parse("2026-01-31T23:59:59Z")

        ref.find("user-1", SystemMessageFilter(from = from, to = to), pageIndex = 3, pageSize = 7)

        assertEquals(from, repo.lastFind?.filter?.from)
        assertEquals(to, repo.lastFind?.filter?.to)
        assertEquals(3, repo.lastFind?.pageIndex)
        assertEquals(7, repo.lastFind?.pageSize)
    }

    @Test
    fun `boundary count with the filter omitted defaults to EMPTY`() {
        val repo = RecordingRepo()
        val ref: SystemMessageRepository = repo

        ref.count("user-1")

        assertSame(SystemMessageFilter.EMPTY, repo.lastCountFilter)
    }
}
