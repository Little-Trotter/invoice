package de.littletrotter.application.reference

import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.shared.SortDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate

/**
 * [TaxRateQueries.findAllTaxRates]'s configured-lookup-page-size defaulting,
 * the same pattern as [PaymentTermQueriesTest] one domain over; the tax-rate Valkey cache path
 * itself is covered at its actual owner, [TaxRateServiceTest].
 */
class TaxRateQueriesTest {

    @Test
    fun `findAllTaxRates uses configured lookup page size`() {
        var capturedPageSize = -1
        val service = mock<TaxRateService> {
            on {
                findAllTaxRates(any(), any(), anyOrNull(), anyOrNull(), any())
            } doAnswer { invocation ->
                capturedPageSize = invocation.getArgument(1)
                emptyList<TaxRate>()
            }
        }
        val queries = TaxRateQueries(service, configuredReferenceLookupPageSize = 250)

        queries.findAllTaxRates()

        assertEquals(250, capturedPageSize)
    }

    @Test
    fun `findAllTaxRates falls back to 1000 when configured lookup page size is invalid`() {
        var capturedPageSize = -1
        val service = mock<TaxRateService> {
            on {
                findAllTaxRates(any(), any(), anyOrNull(), anyOrNull(), any())
            } doAnswer { invocation ->
                capturedPageSize = invocation.getArgument(1)
                emptyList<TaxRate>()
            }
        }
        val queries = TaxRateQueries(service, configuredReferenceLookupPageSize = -5)

        queries.findAllTaxRates()

        assertEquals(1000, capturedPageSize)
    }

    @Test
    fun `findTaxRateById maps the stored row`() {
        val service = mock<TaxRateService>()
        val rate = TaxRate().edit { id = 3L; version = 2L; name = "Regelsatz"; code = "DE_STANDARD_19"; category = "S" }
        whenever(service.findTaxRateById(3L)).thenReturn(rate)
        val queries = TaxRateQueries(service)

        assertEquals(
            TaxRateDto(id = 3L, version = 2L, name = "Regelsatz", code = "DE_STANDARD_19", category = "S"),
            queries.findTaxRateById(3L),
        )
        assertNull(queries.findTaxRateById(4L))
    }

    @Test
    fun `findReferenceTaxRate delegates to the service`() {
        val service = mock<TaxRateService>()
        val rate = TaxRate().edit { id = 3L; isReference = true }
        val date = LocalDate.of(2026, 1, 1)
        whenever(service.findReferenceTaxRate(Country.DE, date)).thenReturn(rate)
        val queries = TaxRateQueries(service)

        assertEquals(TaxRateDto(id = 3L, version = 0L, isReference = true, category = "S"), queries.findReferenceTaxRate(Country.DE, date))
    }

    @Test
    fun `findTaxRates delegates the given page index and sort unchanged`() {
        val service = mock<TaxRateService>()
        whenever(service.findAllTaxRates(1, 20, "x", "name", SortDirection.DESC)).thenReturn(listOf(TaxRate()))
        val queries = TaxRateQueries(service)

        assertEquals(1, queries.findTaxRates(1, 20, "x", "name", SortDirection.DESC).size)
    }

    @Test
    fun `countTaxRates delegates to the service`() {
        val service = mock<TaxRateService>()
        whenever(service.countTaxRates("x")).thenReturn(6L)
        val queries = TaxRateQueries(service)

        assertEquals(6L, queries.countTaxRates("x"))
    }

    @Test
    fun `countInactiveTaxRates delegates to the service`() {
        val service = mock<TaxRateService>()
        whenever(service.countInactiveTaxRates("x")).thenReturn(2L)
        val queries = TaxRateQueries(service)

        assertEquals(2L, queries.countInactiveTaxRates("x"))
    }

    @Test
    fun `isTaxRateInUse delegates to the service`() {
        val service = mock<TaxRateService>()
        whenever(service.isTaxRateInUse(4L)).thenReturn(true)
        val queries = TaxRateQueries(service)

        assertTrue(queries.isTaxRateInUse(4L))
    }

    @Test
    fun `findTaxRates, countTaxRates and countInactiveTaxRates accept their default arguments through the interface type`() {
        val service = mock<TaxRateService>()
        whenever(service.findAllTaxRates(0, 60, null, null, SortDirection.ASC)).thenReturn(emptyList())
        whenever(service.countTaxRates(null)).thenReturn(0L)
        whenever(service.countInactiveTaxRates(null)).thenReturn(0L)
        val queries: TaxRateQueryPort = TaxRateQueries(service)

        assertEquals(emptyList<TaxRateDto>(), queries.findTaxRates())
        assertEquals(0L, queries.countTaxRates())
        assertEquals(0L, queries.countInactiveTaxRates())
    }

    @Test
    fun `findTaxRateUsageCounts delegates to the service`() {
        val service = mock<TaxRateService>()
        whenever(service.findTaxRateUsageCounts()).thenReturn(mapOf(1L to 2L))
        val queries = TaxRateQueries(service)

        assertEquals(mapOf(1L to 2L), queries.findTaxRateUsageCounts())
    }
}
