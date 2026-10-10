package de.littletrotter.application.reference

import de.littletrotter.application.dto.HotelRateDto
import de.littletrotter.application.dto.MileageRateDto
import de.littletrotter.application.dto.PerDiemRateDto
import de.littletrotter.application.reference.port.inbound.HotelRateQueryPort
import de.littletrotter.application.reference.port.inbound.MileageRateQueryPort
import de.littletrotter.application.reference.port.inbound.PerDiemRateQueryPort
import de.littletrotter.domain.reference.HotelRate
import de.littletrotter.domain.reference.MileageRate
import de.littletrotter.domain.reference.PerDiemRate
import de.littletrotter.domain.shared.SortDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** The hotel, mileage and per-diem rate reads delegate to their services with the port's defaults. */
class RateCatalogueQueriesTest {

    @Test
    fun `HotelRateQueries delegates lookup, paging and counts to the service`() {
        val service = mock<HotelRateService>()
        val rate = HotelRate()
        whenever(service.findHotelRateById(1L)).thenReturn(rate)
        whenever(service.findAllHotelRates(0, 60, null, "country", SortDirection.ASC)).thenReturn(listOf(rate))
        whenever(service.countHotelRates(null)).thenReturn(3L)
        whenever(service.countInactiveHotelRates(null)).thenReturn(1L)
        val queries: HotelRateQueryPort = HotelRateQueries(service)

        assertEquals(HotelRateDto(version = 0L), queries.findHotelRateById(1L))
        assertEquals(listOf(HotelRateDto(version = 0L)), queries.findHotelRates())
        assertEquals(3L, queries.countHotelRates())
        assertEquals(1L, queries.countInactiveHotelRates())
    }

    @Test
    fun `MileageRateQueries delegates lookup, paging and counts to the service`() {
        val service = mock<MileageRateService>()
        val rate = MileageRate()
        whenever(service.findMileageRateById(2L)).thenReturn(rate)
        whenever(service.findAllMileageRates(1, 20, "x", "name", SortDirection.DESC)).thenReturn(listOf(rate))
        whenever(service.countMileageRates("x")).thenReturn(4L)
        whenever(service.countInactiveMileageRates("x")).thenReturn(2L)
        val queries: MileageRateQueryPort = MileageRateQueries(service)

        assertEquals(MileageRateDto(version = 0L), queries.findMileageRateById(2L))
        assertEquals(listOf(MileageRateDto(version = 0L)), queries.findMileageRates(1, 20, "x", "name", SortDirection.DESC))
        assertEquals(4L, queries.countMileageRates("x"))
        assertEquals(2L, queries.countInactiveMileageRates("x"))
    }

    @Test
    fun `PerDiemRateQueries delegates lookup, default, paging and counts to the service`() {
        val service = mock<PerDiemRateService>()
        val rate = PerDiemRate()
        whenever(service.findPerDiemRateById(3L)).thenReturn(rate)
        whenever(service.findDefaultPerDiemRate()).thenReturn(rate)
        whenever(service.findAllPerDiemRates(0, 60, null, PerDiemRateService.SORT_FIELD_COUNTRY, SortDirection.ASC))
            .thenReturn(listOf(rate))
        whenever(service.countPerDiemRates(null)).thenReturn(5L)
        whenever(service.countInactivePerDiemRates(null)).thenReturn(0L)
        val queries: PerDiemRateQueryPort = PerDiemRateQueries(service)

        assertEquals(PerDiemRateDto(version = 0L), queries.findPerDiemRateById(3L))
        assertEquals(PerDiemRateDto(version = 0L), queries.findDefaultPerDiemRate())
        assertEquals(listOf(PerDiemRateDto(version = 0L)), queries.findPerDiemRates())
        assertEquals(5L, queries.countPerDiemRates())
        assertEquals(0L, queries.countInactivePerDiemRates())
    }

    @Test
    fun `PerDiemRateQueries caps the full list at the configured lookup page size and falls back to 1000`() {
        val pageSizes = mutableListOf<Int>()
        val service = mock<PerDiemRateService> {
            on { findAllPerDiemRates(any(), any(), anyOrNull(), any(), any()) } doAnswer { invocation ->
                pageSizes += invocation.getArgument<Int>(1)
                emptyList<PerDiemRate>()
            }
        }

        PerDiemRateQueries(service, configuredReferenceLookupPageSize = 250).findAllPerDiemRates()
        PerDiemRateQueries(service, configuredReferenceLookupPageSize = 0).findAllPerDiemRates()

        assertEquals(listOf(250, 1000), pageSizes)
    }
}
