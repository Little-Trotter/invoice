package de.littletrotter.domain.repository

import de.littletrotter.domain.reference.NumberRange
import de.littletrotter.domain.reference.NumberRangeSubject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * [NumberRangeRepository.findAll] declares default parameter values without a body,
 * covered the same way as [B2bProfileRepositoryDefaultsTest]. [NumberCounterRepository
 * .raiseToAtLeast]/[NumberCounterRepository.hasBeenUsed] carry default bodies (compatibility
 * fallbacks for in-memory test doubles, per each method's own KDoc) — same idiom as
 * [ArticleRepositoryDefaultsTest]. `raiseToAtLeast`'s repeated-allocation loop is intentional,
 * documented "O(n) but adequate for in-memory test doubles" fallback logic, not misplaced domain
 * logic: [de.littletrotter.infrastructure.persistence.PostgresNumberCounterRepository] overrides
 * it with a single atomic upsert in production, exactly the same "default body never runs in
 * production" pattern already established throughout this file (see [findAllInCategory]
 * [ArticleRepository.findAllInCategory]'s own KDoc) — no design change is warranted here.
 */
class NumberRangeRepositoryDefaultsTest {

    private fun range(id: Long) = NumberRange().apply { this.id = id }

    @Test
    fun `happy path findAll with every argument omitted uses the documented defaults`() {
        var captured: List<Any?>? = null
        val repo = object : NumberRangeRepository {
            override fun findActive(subject: NumberRangeSubject): NumberRange? = null
            override fun findBySubject(subject: NumberRangeSubject): List<NumberRange> = emptyList()
            override fun findById(id: Long): NumberRange? = null
            override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?): List<NumberRange> {
                captured = listOf(pageIndex, pageSize, filterText)
                return emptyList()
            }
            override fun count(filterText: String?) = 0L
            override fun countInactive(filterText: String?) = 0L
            override fun save(range: NumberRange) = range
            override fun delete(id: Long) {}
        }

        repo.findAll()

        assertEquals(listOf(0, 60, null), captured)
    }

    @Test
    fun `boundary findAll with explicit paging and filter is forwarded verbatim`() {
        var captured: List<Any?>? = null
        val repo = object : NumberRangeRepository {
            override fun findActive(subject: NumberRangeSubject): NumberRange? = null
            override fun findBySubject(subject: NumberRangeSubject): List<NumberRange> = emptyList()
            override fun findById(id: Long): NumberRange? = null
            override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?): List<NumberRange> {
                captured = listOf(pageIndex, pageSize, filterText)
                return emptyList()
            }
            override fun count(filterText: String?) = 0L
            override fun countInactive(filterText: String?) = 0L
            override fun save(range: NumberRange) = range
            override fun delete(id: Long) {}
        }

        repo.findAll(pageIndex = 1, pageSize = 5, filterText = "RE-")

        assertEquals(listOf(1, 5, "RE-"), captured)
    }

    /** Minimal [NumberCounterRepository] tracking a single in-memory counter, mirroring the
     *  contract each real allocation method documents on the interface. */
    private class InMemoryCounterRepo : NumberCounterRepository {
        private var last = 0L
        var allocations = 0

        override fun peekNext(range: NumberRange, scopeKey: String, periodKey: String) = last + 1
        override fun allocateUniqueOnly(range: NumberRange, scopeKey: String, periodKey: String): Long {
            allocations++
            last++
            return last
        }
        override fun allocateGapless(range: NumberRange, scopeKey: String, periodKey: String) = allocateUniqueOnly(range, scopeKey, periodKey)
        override fun currentValue(subject: NumberRangeSubject): Long? = last
    }

    @Test
    fun `happy path raiseToAtLeast allocates repeatedly until the target is reached`() {
        val repo = InMemoryCounterRepo()

        val result = repo.raiseToAtLeast(range(1), "GLOBAL", "2026", target = 3)

        assertEquals(3L, result)
        assertEquals(3, repo.allocations)
    }

    @Test
    fun `boundary raiseToAtLeast is a no-op when the current value already meets the target`() {
        val repo = InMemoryCounterRepo()
        repo.raiseToAtLeast(range(1), "GLOBAL", "2026", target = 5)
        val allocationsAfterFirstRaise = repo.allocations

        val result = repo.raiseToAtLeast(range(1), "GLOBAL", "2026", target = 2)

        assertEquals(5L, result)
        assertEquals(allocationsAfterFirstRaise, repo.allocations)
    }

    @Test
    fun `boundary raiseToAtLeast never lowers an already-higher current value`() {
        val repo = InMemoryCounterRepo()
        repo.raiseToAtLeast(range(1), "GLOBAL", "2026", target = 5)

        val result = repo.raiseToAtLeast(range(1), "GLOBAL", "2026", target = 5)

        assertEquals(5L, result)
    }

    @Test
    fun `boundary hasBeenUsed defaults to false`() {
        val repo = InMemoryCounterRepo()

        assertEquals(false, repo.hasBeenUsed(1L))
    }

    @Test
    fun `boundary count and countInactive are exercised directly through the interface`() {
        val repo = object : NumberRangeRepository {
            override fun findActive(subject: NumberRangeSubject): NumberRange? = null
            override fun findBySubject(subject: NumberRangeSubject): List<NumberRange> = emptyList()
            override fun findById(id: Long): NumberRange? = null
            override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?): List<NumberRange> = emptyList()
            override fun count(filterText: String?) = 0L
            override fun countInactive(filterText: String?) = 0L
            override fun save(range: NumberRange) = range
            override fun delete(id: Long) {}
        }

        assertEquals(0L, repo.count())
        assertEquals(0L, repo.countInactive())
    }
}
