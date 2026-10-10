package de.littletrotter.domain.repository

import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.shared.SortDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * [B2bProfileRepository.findAll] declares default parameter values (`pageIndex = 0`,
 * `pageSize = 60`, `filterText = null`, `sortDir = ASC`) without a body: Kotlin still compiles a
 * synthetic `findAll$default` dispatcher into the interface for callers (through an interface-typed
 * reference) that omit trailing arguments — real bytecode a fake never exercises unless a test
 * actually calls through the interface type with arguments omitted. This locks in the documented
 * defaults and that explicit arguments are forwarded verbatim, never silently replaced.
 */
class B2bProfileRepositoryDefaultsTest {

    private class RecordingRepo : B2bProfileRepository {
        data class Call(val pageIndex: Int, val pageSize: Int, val filterText: String?, val sortDir: SortDirection)
        var lastCall: Call? = null
        var lastCountFilterText: String? = "untouched"

        override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?, sortDir: SortDirection): List<B2bProfile> {
            lastCall = Call(pageIndex, pageSize, filterText, sortDir)
            return emptyList()
        }
        override fun count(filterText: String?): Long {
            lastCountFilterText = filterText
            return 0
        }
        override fun countInactive(filterText: String?): Long = 0
        override fun findById(id: Long): B2bProfile? = null
        override fun findAllActive(): List<B2bProfile> = emptyList()
        override fun findByName(name: String): B2bProfile? = null
        override fun save(profile: B2bProfile): B2bProfile = profile
        override fun delete(id: Long) {}
    }

    @Test
    fun `happy path findAll with every argument omitted uses the documented defaults`() {
        val repo = RecordingRepo()
        val ref: B2bProfileRepository = repo

        ref.findAll()

        assertEquals(RecordingRepo.Call(0, 60, null, SortDirection.ASC), repo.lastCall)
    }

    @Test
    fun `boundary findAll with explicit paging is forwarded verbatim, not replaced by defaults`() {
        val repo = RecordingRepo()
        val ref: B2bProfileRepository = repo

        ref.findAll(pageIndex = 2, pageSize = 10)

        assertEquals(RecordingRepo.Call(2, 10, null, SortDirection.ASC), repo.lastCall)
    }

    @Test
    fun `boundary findAll with an explicit filterText is forwarded, not overwritten with null`() {
        val repo = RecordingRepo()
        val ref: B2bProfileRepository = repo

        ref.findAll(filterText = "Handwerk")

        assertEquals(RecordingRepo.Call(0, 60, "Handwerk", SortDirection.ASC), repo.lastCall)
    }

    @Test
    fun `boundary findAll with an explicit sortDir DESC is forwarded, not overwritten with ASC`() {
        val repo = RecordingRepo()
        val ref: B2bProfileRepository = repo

        ref.findAll(sortDir = SortDirection.DESC)

        assertEquals(RecordingRepo.Call(0, 60, null, SortDirection.DESC), repo.lastCall)
    }

    @Test
    fun `boundary count with filterText omitted defaults to null`() {
        val repo = RecordingRepo()
        val ref: B2bProfileRepository = repo

        ref.count()

        assertEquals(null, repo.lastCountFilterText)
    }

    @Test
    fun `boundary countInactive is exercised directly through the interface`() {
        val repo = RecordingRepo()
        val ref: B2bProfileRepository = repo

        assertEquals(0L, ref.countInactive())
    }
}
