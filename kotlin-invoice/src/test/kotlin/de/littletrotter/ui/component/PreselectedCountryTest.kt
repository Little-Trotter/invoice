package de.littletrotter.ui.component

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * IM2-B/b4 — pure-function tests for [preselectedCountry], independent of Vaadin/Karibu: the border cases for VAT variants.
 */
class PreselectedCountryTest {

    @Test
    fun `exactly one unused candidate is preselected`() {
        assertEquals("LU", preselectedCountry(listOf("LU"), usedCountries = emptySet()))
    }

    @Test
    fun `a candidate already used by an existing row is not preselected`() {
        assertNull(preselectedCountry(listOf("LU"), usedCountries = setOf("LU")))
    }

    @Test
    fun `no candidates at all yields no preselection`() {
        assertNull(preselectedCountry(emptyList(), usedCountries = emptySet()))
    }

    @Test
    fun `more than one unused candidate is ambiguous and yields no preselection`() {
        assertNull(preselectedCountry(listOf("LU", "FR"), usedCountries = emptySet()))
    }

    @Test
    fun `duplicated candidates count once`() {
        assertEquals("LU", preselectedCountry(listOf("LU", "LU"), usedCountries = emptySet()))
    }

    @Test
    fun `two candidates where one is already used leaves exactly one unused`() {
        assertEquals("FR", preselectedCountry(listOf("LU", "FR"), usedCountries = setOf("LU")))
    }
}
