package de.littletrotter.domain.repository

import de.littletrotter.domain.reference.MileageRate
import de.littletrotter.domain.reference.VehicleType
import de.littletrotter.domain.shared.SortDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * [MileageRateRepository.findAll] declares default parameter values without a body;
 * Kotlin still compiles a synthetic `$default` dispatcher into the interface for callers going
 * through an interface-typed reference with arguments omitted. Locks in the documented defaults
 * and that explicit arguments are forwarded verbatim.
 */
class MileageRateRepositoryDefaultsTest {

    private class RecordingRepo : MileageRateRepository {
        data class FindAllCall(val pageIndex: Int, val pageSize: Int, val filterText: String?, val sortField: String, val sortDir: SortDirection)
        var lastFindAll: FindAllCall? = null

        override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?, sortField: String, sortDir: SortDirection): List<MileageRate> {
            lastFindAll = FindAllCall(pageIndex, pageSize, filterText, sortField, sortDir)
            return emptyList()
        }
        override fun count(filterText: String?): Long = 0
        override fun countInactive(filterText: String?): Long = 0
        override fun findById(id: Long): MileageRate? = null
        override fun save(rate: MileageRate): MileageRate = rate
        override fun delete(id: Long) {}
        override fun findByVehicleTypeAndDate(vehicleType: VehicleType, date: LocalDate): MileageRate? = null
    }

    @Test
    fun `happy path findAll with every argument omitted uses the documented defaults`() {
        val repo = RecordingRepo()
        val ref: MileageRateRepository = repo

        ref.findAll()

        assertEquals(RecordingRepo.FindAllCall(0, 60, null, "country", SortDirection.ASC), repo.lastFindAll)
    }

    @Test
    fun `boundary findAll with explicit paging and filter is forwarded verbatim`() {
        val repo = RecordingRepo()
        val ref: MileageRateRepository = repo

        ref.findAll(pageIndex = 2, pageSize = 5, filterText = "PKW", sortField = "ratePerKm", sortDir = SortDirection.DESC)

        assertEquals(RecordingRepo.FindAllCall(2, 5, "PKW", "ratePerKm", SortDirection.DESC), repo.lastFindAll)
    }

    @Test
    fun `boundary findAll with only filterText overridden keeps the remaining paging and sort defaults`() {
        val repo = RecordingRepo()
        val ref: MileageRateRepository = repo

        ref.findAll(filterText = "")

        assertEquals(RecordingRepo.FindAllCall(0, 60, "", "country", SortDirection.ASC), repo.lastFindAll)
    }

    @Test
    fun `boundary findByVehicleTypeAndDate takes vehicleType and date, never country`() {
        val repo = RecordingRepo()
        val ref: MileageRateRepository = repo

        val result = ref.findByVehicleTypeAndDate(VehicleType.CAR, LocalDate.of(2026, 6, 1))

        assertEquals(null, result)
    }

    @Test
    fun `boundary count and countInactive are exercised directly through the interface`() {
        val repo = RecordingRepo()
        val ref: MileageRateRepository = repo

        assertEquals(0L, ref.count())
        assertEquals(0L, ref.countInactive())
    }
}
