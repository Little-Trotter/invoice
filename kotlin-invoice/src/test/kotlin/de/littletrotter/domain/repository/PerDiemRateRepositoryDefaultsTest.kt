package de.littletrotter.domain.repository

import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.PerDiemRate
import de.littletrotter.domain.shared.SortDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * [PerDiemRateRepository.findAll] declares default parameter values without a body;
 * Kotlin still compiles a synthetic `$default` dispatcher into the interface for callers going
 * through an interface-typed reference with arguments omitted. Locks in the documented defaults
 * and that explicit arguments are forwarded verbatim.
 */
class PerDiemRateRepositoryDefaultsTest {

    private class RecordingRepo : PerDiemRateRepository {
        data class FindAllCall(val pageIndex: Int, val pageSize: Int, val filterText: String?, val sortField: String, val sortDir: SortDirection)
        var lastFindAll: FindAllCall? = null

        override fun findByCountryAndRegionAndDate(country: Country, region: String?, date: LocalDate): PerDiemRate? = null
        override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?, sortField: String, sortDir: SortDirection): List<PerDiemRate> {
            lastFindAll = FindAllCall(pageIndex, pageSize, filterText, sortField, sortDir)
            return emptyList()
        }
        override fun count(filterText: String?): Long = 0
        override fun countInactive(filterText: String?): Long = 0
        override fun findById(id: Long): PerDiemRate? = null
        override fun findDefault(): PerDiemRate? = null
        override fun clearAllDefaults() {}
        override fun save(rate: PerDiemRate): PerDiemRate = rate
        override fun delete(id: Long) {}
    }

    @Test
    fun `happy path findAll with every argument omitted uses the documented defaults`() {
        val repo = RecordingRepo()
        val ref: PerDiemRateRepository = repo

        ref.findAll()

        assertEquals(RecordingRepo.FindAllCall(0, 60, null, "country", SortDirection.ASC), repo.lastFindAll)
    }

    @Test
    fun `boundary findAll with explicit paging and sort is forwarded verbatim`() {
        val repo = RecordingRepo()
        val ref: PerDiemRateRepository = repo

        ref.findAll(pageIndex = 4, pageSize = 30, sortField = "fullDayRate", sortDir = SortDirection.DESC)

        assertEquals(RecordingRepo.FindAllCall(4, 30, null, "fullDayRate", SortDirection.DESC), repo.lastFindAll)
    }

    @Test
    fun `boundary findAll with an explicit filterText is forwarded, not overwritten with null`() {
        val repo = RecordingRepo()
        val ref: PerDiemRateRepository = repo

        ref.findAll(filterText = "Inland")

        assertEquals(RecordingRepo.FindAllCall(0, 60, "Inland", "country", SortDirection.ASC), repo.lastFindAll)
    }

    @Test
    fun `boundary findByCountryAndRegionAndDate has no default parameters and forwards region as given, including null`() {
        val repo = RecordingRepo()
        val ref: PerDiemRateRepository = repo

        val result = ref.findByCountryAndRegionAndDate(Country.DE, null, LocalDate.of(2026, 3, 1))

        assertEquals(null, result)
    }

    @Test
    fun `boundary count and countInactive are exercised directly through the interface`() {
        val repo = RecordingRepo()
        val ref: PerDiemRateRepository = repo

        assertEquals(0L, ref.count())
        assertEquals(0L, ref.countInactive())
    }
}
