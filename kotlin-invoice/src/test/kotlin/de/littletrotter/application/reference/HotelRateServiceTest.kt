package de.littletrotter.application.reference

import de.littletrotter.domain.reference.HotelRate
import de.littletrotter.domain.repository.HotelRateRepository
import de.littletrotter.domain.shared.SortDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * hotel-rate slice of the former `ReferenceDataServiceRatesTest`, split by
 * Referenzdaten domain alongside [HotelRateService]
 */
class HotelRateServiceTest {

    private val repository = mock<HotelRateRepository>()
    private fun service() = HotelRateService(repository)

    @Test
    fun `happy path - every read and write delegates to the repository`() {
        val svc = service()
        val hotel = HotelRate()
        whenever(repository.findById(5L)).thenReturn(hotel)
        whenever(repository.findAll(0, 60, null, "country", SortDirection.ASC)).thenReturn(listOf(hotel))
        whenever(repository.count(null)).thenReturn(9L)
        whenever(repository.countInactive(null)).thenReturn(2L)
        whenever(repository.save(hotel)).thenReturn(hotel)

        assertSame(hotel, svc.findHotelRateById(5L))
        assertEquals(listOf(hotel), svc.findAllHotelRates())
        assertEquals(9L, svc.countHotelRates())
        assertEquals(2L, svc.countInactiveHotelRates())
        assertSame(hotel, svc.saveHotelRate(hotel))
        svc.deleteHotelRate(5L)

        verify(repository).delete(5L)
    }

    @Test
    fun `boundary - unknown id resolves to null, empty page resolves to empty`() {
        val svc = service()

        assertNull(svc.findHotelRateById(404L))
        assertTrue(svc.findAllHotelRates(pageIndex = 3, filterText = "zzz").isEmpty())
    }
}
