package de.littletrotter.application.invoice

import de.littletrotter.application.dto.PerDiemRateDto
import de.littletrotter.application.reference.port.inbound.PerDiemRateQueryPort
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.shared.country.Country
import de.littletrotter.application.dto.TaxRateDto
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** [TripEntryQueries] only delegates; each method must reach the facade that owns the data. */
class TripEntryQueriesTest {

    private val taxRateQueries: TaxRateQueryPort = mock()
    private val perDiemRateQueries: PerDiemRateQueryPort = mock()
    private val queries = TripEntryQueries(taxRateQueries, perDiemRateQueries)

    @Test
    fun `findAllTaxRates delegates to the tax-rate facade`() {
        val rate = TaxRateDto(id = 1L, category = "S")
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(rate))

        assertEquals(listOf(rate), queries.findAllTaxRates())
    }

    @Test
    fun `findReferenceTaxRate delegates to the tax-rate facade`() {
        val rate = TaxRateDto(id = 2L, category = "S")
        val date = LocalDate.of(2026, 6, 1)
        whenever(taxRateQueries.findReferenceTaxRate(Country.DE, date)).thenReturn(rate)

        assertEquals(rate, queries.findReferenceTaxRate(Country.DE, date))
    }

    @Test
    fun `findAllPerDiemRates delegates to the per-diem facade`() {
        val rate = PerDiemRateDto(id = 3L)
        whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(rate))

        assertEquals(listOf(rate), queries.findAllPerDiemRates())
    }

    @Test
    fun `findDefaultPerDiemRate delegates to the per-diem facade, null when none is marked default`() {
        val rate = PerDiemRateDto(id = 4L)
        whenever(perDiemRateQueries.findDefaultPerDiemRate()).thenReturn(rate)

        assertEquals(rate, queries.findDefaultPerDiemRate())

        whenever(perDiemRateQueries.findDefaultPerDiemRate()).thenReturn(null)
        assertNull(queries.findDefaultPerDiemRate())
    }
}
