package de.littletrotter.domain.masterdata

import de.littletrotter.shared.country.Country
import de.littletrotter.domain.shared.InvoiceFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * PO order 04.09.2026 — the country-driven preselection for a customer's "deutsche oder
 * internationale Rechnung" choice. The rule itself is one line; what these cases pin is that it
 * reads [Country.GERMAN_BUSINESS_LANGUAGE_COUNTRIES] rather than a second, hand-kept DE/AT/CH list,
 * and that an unknown country leans German like every other default here.
 */
class DefaultInvoiceFormatTest {

    @Test
    fun `every German-business-language country preselects the German invoice`() {
        Country.GERMAN_BUSINESS_LANGUAGE_COUNTRIES.forEach { country ->
            assertEquals(InvoiceFormat.GERMAN, defaultInvoiceFormatFor(country), "$country")
        }
    }

    @Test
    fun `DE, AT and CH are exactly the countries that preselect German`() {
        assertEquals(setOf(Country.DE, Country.AT, Country.CH), Country.GERMAN_BUSINESS_LANGUAGE_COUNTRIES)
    }

    @Test
    fun `any other country preselects the international invoice`() {
        listOf(Country.FR, Country.BE, Country.NL, Country.LU).forEach { country ->
            assertEquals(InvoiceFormat.INTERNATIONAL, defaultInvoiceFormatFor(country), "$country")
        }
    }

    @Test
    fun `an unknown country falls back to the German invoice`() {
        assertEquals(InvoiceFormat.GERMAN, defaultInvoiceFormatFor(null))
    }
}
