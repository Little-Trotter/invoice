package de.littletrotter.domain.invoice

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.TaxRate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate

class TripTaxSelectionTest {
    private val reverseChargeRate = TaxRate()
    private val reference = TaxRate()
    private val date = LocalDate.of(2026, 9, 16)

    @Test
    fun `every normal trip resolves the country reference`() {
        assertEquals(reference, selectTripTaxRate(TripTaxContext(false, Country.LU, date, reverseChargeRate)) { country, requestedDate ->
            assertEquals(Country.LU, country)
            assertEquals(date, requestedDate)
            reference
        })
    }

    @Test
    fun `reverse charge trip uses the resolved reverse charge rate`() {
        assertEquals(reverseChargeRate, selectTripTaxRate(TripTaxContext(true, Country.LU, date, reverseChargeRate)) { _, _ -> reference })
    }

    @Test
    fun `a normal trip with no seller country defaults it to Germany`() {
        assertEquals(reference, selectTripTaxRate(TripTaxContext(false, null, date, reverseChargeRate)) { country, requestedDate ->
            assertEquals(Country.DE, country)
            assertEquals(date, requestedDate)
            reference
        })
    }

    @Test
    fun `normal trip does not require a main service tax`() {
        assertEquals(reference, selectTripTaxRate(TripTaxContext(false, Country.DE, date, null)) { _, _ -> reference })
    }

    @Test
    fun `reverse charge trip requires the reverse charge item rate`() {
        assertThrows(InvalidParameterException::class.java) {
            selectTripTaxRate(TripTaxContext(true, Country.DE, date, null)) { _, _ -> reference }
        }
    }

    @Test
    fun `normal trip requires invoice date`() {
        assertThrows(InvalidParameterException::class.java) {
            selectTripTaxRate(TripTaxContext(false, Country.DE, null, reverseChargeRate)) { _, _ -> reference }
        }
    }
}
