package de.littletrotter.application.travel

import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.HotelRate
import de.littletrotter.domain.repository.HotelRateRepository
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Ports the rate-resolution cases from the earlier `TravelItemDialogKaribuTest`
 * ("hotel rate prefill …", lines 228-256 before this class existed) onto [HotelRateResolver]
 * itself: the dialog only ever forwards to this class now, so the resolution logic is
 * tested here, at unit speed, without a Vaadin/Karibu harness.
 */
class HotelRateResolverTest {

    private val repository: HotelRateRepository = mock()
    private val resolver = HotelRateResolver(repository)

    @Test
    fun `resolves the active per-night rate for the given country and date`() {
        val rate = HotelRate().edit { country = Country.FR; ratePerNight = Money(BigDecimal("111.00")) }
        whenever(repository.findByCountryAndDate(eq(Country.FR), any(), anyOrNull())).thenReturn(rate)

        val resolved = resolver.resolveRatePerNight(Country.FR, LocalDate.of(2026, 9, 11))

        assertEquals(Money(BigDecimal("111.00")), resolved?.ratePerNight)
    }

    @Test
    fun `returns null when no hotel rate is configured for the country`() {
        whenever(repository.findByCountryAndDate(eq(Country.FR), any(), anyOrNull())).thenReturn(null)

        val resolved = resolver.resolveRatePerNight(Country.FR, LocalDate.of(2026, 9, 11))

        assertNull(resolved)
    }

    @Test
    fun `ratePerNight exposes the configured amount only`() {
        val rate = HotelRate().edit { country = Country.FR; ratePerNight = Money(BigDecimal("111.00")) }
        whenever(repository.findByCountryAndDate(eq(Country.FR), any(), anyOrNull())).thenReturn(rate)

        assertEquals(Money(BigDecimal("111.00")), resolver.ratePerNight(Country.FR, LocalDate.of(2026, 9, 11)))
    }

    @Test
    fun `ratePerNight is null when no hotel rate is configured`() {
        whenever(repository.findByCountryAndDate(eq(Country.FR), any(), anyOrNull())).thenReturn(null)

        assertNull(resolver.ratePerNight(Country.FR, LocalDate.of(2026, 9, 11)))
    }
}
