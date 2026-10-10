package de.littletrotter.domain.repository

import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.HotelRate
import de.littletrotter.domain.shared.SortDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * [HotelRateRepository.findByCountryAndDate] and [HotelRateRepository.findAll] declare
 * default parameter values without a body; Kotlin still compiles a synthetic `$default` dispatcher
 * into the interface for callers going through an interface-typed reference with arguments
 * omitted. Locks in the documented defaults (region = `null`, the sortField/sortDir/paging
 * defaults) and that explicit arguments are forwarded verbatim.
 */
class HotelRateRepositoryDefaultsTest {

    private class RecordingRepo : HotelRateRepository {
        data class FindAllCall(val pageIndex: Int, val pageSize: Int, val filterText: String?, val sortField: String, val sortDir: SortDirection)
        var lastFindAll: FindAllCall? = null
        var lastRegion: String? = "untouched"

        override fun countInactive(filterText: String?): Long = 0
        override fun findByCountryAndDate(country: Country, date: LocalDate, region: String?): HotelRate? {
            lastRegion = region
            return null
        }
        override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?, sortField: String, sortDir: SortDirection): List<HotelRate> {
            lastFindAll = FindAllCall(pageIndex, pageSize, filterText, sortField, sortDir)
            return emptyList()
        }
        override fun count(filterText: String?): Long = 0
        override fun findById(id: Long): HotelRate? = null
        override fun save(rate: HotelRate): HotelRate = rate
        override fun delete(id: Long) {}
    }

    @Test
    fun `happy path findByCountryAndDate with region omitted defaults to null`() {
        val repo = RecordingRepo()
        val ref: HotelRateRepository = repo

        ref.findByCountryAndDate(Country.DE, LocalDate.of(2026, 1, 1))

        assertEquals(null, repo.lastRegion)
    }

    @Test
    fun `boundary findByCountryAndDate with an explicit region is forwarded, not overwritten with null`() {
        val repo = RecordingRepo()
        val ref: HotelRateRepository = repo

        ref.findByCountryAndDate(Country.DE, LocalDate.of(2026, 1, 1), region = "Bayern")

        assertEquals("Bayern", repo.lastRegion)
    }

    @Test
    fun `boundary findAll with every argument omitted uses the documented defaults`() {
        val repo = RecordingRepo()
        val ref: HotelRateRepository = repo

        ref.findAll()

        assertEquals(RecordingRepo.FindAllCall(0, 60, null, "country", SortDirection.ASC), repo.lastFindAll)
    }

    @Test
    fun `boundary findAll with explicit sortField and paging is forwarded verbatim`() {
        val repo = RecordingRepo()
        val ref: HotelRateRepository = repo

        ref.findAll(pageIndex = 3, pageSize = 15, sortField = "ratePerNight", sortDir = SortDirection.DESC)

        assertEquals(RecordingRepo.FindAllCall(3, 15, null, "ratePerNight", SortDirection.DESC), repo.lastFindAll)
    }

    @Test
    fun `boundary count and countInactive are exercised directly through the interface`() {
        val repo = RecordingRepo()
        val ref: HotelRateRepository = repo

        assertEquals(0L, ref.count())
        assertEquals(0L, ref.countInactive())
    }
}
