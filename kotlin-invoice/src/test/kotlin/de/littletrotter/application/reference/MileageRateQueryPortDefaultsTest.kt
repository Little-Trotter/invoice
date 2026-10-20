package de.littletrotter.application.reference

import de.littletrotter.application.reference.port.inbound.MileageRateQueryPort
import de.littletrotter.domain.shared.SortDirection
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

/** The catalogue tab calls the port without arguments for its first page; these defaults are that contract. */
class MileageRateQueryPortDefaultsTest {

    private val port: MileageRateQueryPort = mock()

    @Test
    fun `findMileageRates without arguments asks for the first page of 60 sorted by country`() {
        port.findMileageRates()

        verify(port).findMileageRates(0, 60, null, "country", SortDirection.ASC)
    }

    @Test
    fun `count methods without filter pass a null filter`() {
        port.countMileageRates()
        port.countInactiveMileageRates()

        verify(port).countMileageRates(null)
        verify(port).countInactiveMileageRates(null)
    }

    @Test
    fun `explicit arguments override the defaults`() {
        port.findMileageRates(pageIndex = 3, pageSize = 10, filterText = "DE", sortField = "ratePerKm", sortDir = SortDirection.DESC)

        verify(port).findMileageRates(3, 10, "DE", "ratePerKm", SortDirection.DESC)
    }
}
