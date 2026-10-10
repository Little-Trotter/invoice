package de.littletrotter.domain.repository

import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.shared.SortDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * [PaymentTermRepository.findAll] and [PaymentTermRepository.count] declare default
 * parameter values without a body; Kotlin still compiles a synthetic `$default` dispatcher into
 * the interface for callers going through an interface-typed reference with arguments omitted.
 * Locks in the documented defaults and that explicit arguments are forwarded verbatim.
 */
class PaymentTermRepositoryDefaultsTest {

    private class RecordingRepo : PaymentTermRepository {
        data class FindAllCall(val pageIndex: Int, val pageSize: Int, val filterText: String?, val sortField: String, val sortDir: SortDirection)
        var lastFindAll: FindAllCall? = null
        var lastCountFilterText: String? = "untouched"

        override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?, sortField: String, sortDir: SortDirection): List<PaymentTerm> {
            lastFindAll = FindAllCall(pageIndex, pageSize, filterText, sortField, sortDir)
            return emptyList()
        }
        override fun count(filterText: String?): Long {
            lastCountFilterText = filterText
            return 0
        }
        override fun countInactive(filterText: String?): Long = 0
        override fun findDefault(): PaymentTerm? = null
        override fun findDefaults(): List<PaymentTerm> = emptyList()
        override fun releaseDefaultsExcept(keepId: Long?) = Unit
        override fun findById(id: Long): PaymentTerm? = null
        override fun save(term: PaymentTerm): PaymentTerm = term
        override fun delete(id: Long) {}
        override fun isInUse(id: Long): Boolean = false
        override fun usageCounts(): Map<Long, Long> = emptyMap()
    }

    @Test
    fun `happy path findAll with every argument omitted uses the documented defaults`() {
        val repo = RecordingRepo()
        val ref: PaymentTermRepository = repo

        ref.findAll()

        assertEquals(RecordingRepo.FindAllCall(0, 60, null, "name", SortDirection.ASC), repo.lastFindAll)
    }

    @Test
    fun `boundary findAll with explicit paging and filter is forwarded verbatim`() {
        val repo = RecordingRepo()
        val ref: PaymentTermRepository = repo

        ref.findAll(pageIndex = 1, pageSize = 20, filterText = "netto", sortDir = SortDirection.DESC)

        assertEquals(RecordingRepo.FindAllCall(1, 20, "netto", "name", SortDirection.DESC), repo.lastFindAll)
    }

    @Test
    fun `boundary count with filterText omitted defaults to null`() {
        val repo = RecordingRepo()
        val ref: PaymentTermRepository = repo

        ref.count()

        assertEquals(null, repo.lastCountFilterText)
    }

    @Test
    fun `boundary count with an explicit filterText is forwarded, not overwritten with null`() {
        val repo = RecordingRepo()
        val ref: PaymentTermRepository = repo

        ref.count(filterText = "14 Tage")

        assertEquals("14 Tage", repo.lastCountFilterText)
    }

    @Test
    fun `boundary countInactive is exercised directly through the interface`() {
        val repo = RecordingRepo()
        val ref: PaymentTermRepository = repo

        assertEquals(0L, ref.countInactive())
    }
}
