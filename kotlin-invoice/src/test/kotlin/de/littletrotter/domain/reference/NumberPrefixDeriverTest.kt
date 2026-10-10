package de.littletrotter.domain.reference

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Test cases for [NumberPrefixDeriver]. */
class NumberPrefixDeriverTest {

    @Test
    fun `N1-11 - NAME_INITIALS takes the first three letters, ignoring the umlaut expansion overflow`() {
        assertEquals("MUE", NumberPrefixDeriver.fromData(PrefixDataField.NAME_INITIALS, "Müller GmbH"))
    }

    @Test
    fun `NAME_INITIALS strips spaces and punctuation`() {
        assertEquals("ACM", NumberPrefixDeriver.fromData(PrefixDataField.NAME_INITIALS, "Acme, Inc."))
    }

    @Test
    fun `COUNTRY_CODE is normalized to two uppercase letters`() {
        assertEquals("DE", NumberPrefixDeriver.fromData(PrefixDataField.COUNTRY_CODE, "de"))
    }

    @Test
    fun `CATALOGUE_CATEGORY maps known categories to their short form`() {
        assertEquals("SVC", NumberPrefixDeriver.fromData(PrefixDataField.CATALOGUE_CATEGORY, "SERVICE"))
        assertEquals("PRO", NumberPrefixDeriver.fromData(PrefixDataField.CATALOGUE_CATEGORY, "PRODUCT"))
        // TRIP has no curated short form of its own, it falls back to the generic
        // truncated-uppercase rule like any other uncurated category.
        assertEquals("TRI", NumberPrefixDeriver.fromData(PrefixDataField.CATALOGUE_CATEGORY, "TRIP"))
    }

    @Test
    fun `CATALOGUE_CATEGORY falls back to a truncated uppercase form for an unknown category`() {
        assertEquals("MIS", NumberPrefixDeriver.fromData(PrefixDataField.CATALOGUE_CATEGORY, "miscellaneous"))
    }

    @Test
    fun `N1-12 - a blank source value yields no prefix rather than a placeholder`() {
        assertNull(NumberPrefixDeriver.fromData(PrefixDataField.NAME_INITIALS, null))
        assertNull(NumberPrefixDeriver.fromData(PrefixDataField.NAME_INITIALS, "   "))
    }

    @Test
    fun `NAME_INITIALS drops characters outside the A-Z range on both sides of the alphabet`() {
        // '_' (code 95) sits above 'Z' (90) but below 'a' (97) in ASCII — after uppercasing there
        // is no lowercase left, so this is the only way to exercise the "> Z" branch distinctly
        // from the "< A" branch that punctuation like ',' already covers.
        assertEquals("ABC", NumberPrefixDeriver.fromData(PrefixDataField.NAME_INITIALS, "A_B_C_D"))
    }

    @Test
    fun `COUNTRY_CODE with whitespace is trimmed and uppercased`() {
        assertEquals("FR", NumberPrefixDeriver.fromData(PrefixDataField.COUNTRY_CODE, "  fr  "))
    }

    @Test
    fun `COUNTRY_CODE longer than two characters takes only first two`() {
        assertEquals("DE", NumberPrefixDeriver.fromData(PrefixDataField.COUNTRY_CODE, "DEU"))
    }

    @Test
    fun `COUNTRY_CODE with null source yields no prefix`() {
        assertNull(NumberPrefixDeriver.fromData(PrefixDataField.COUNTRY_CODE, null))
    }

    @Test
    fun `CATALOGUE_CATEGORY with whitespace yields prefix`() {
        assertEquals("SVC", NumberPrefixDeriver.fromData(PrefixDataField.CATALOGUE_CATEGORY, "  SERVICE  "))
    }

    @Test
    fun `NAME_INITIALS with no alphabetic characters yields empty string`() {
        assertEquals("", NumberPrefixDeriver.fromData(PrefixDataField.NAME_INITIALS, "123 456"))
    }

    @Test
    fun `NAME_INITIALS with fewer than three letters returns what is available`() {
        assertEquals("AB", NumberPrefixDeriver.fromData(PrefixDataField.NAME_INITIALS, "AB"))
    }

    @Test
    fun `NAME_INITIALS with exactly three letters returns all three`() {
        assertEquals("ABC", NumberPrefixDeriver.fromData(PrefixDataField.NAME_INITIALS, "ABC"))
    }

    @Test
    fun `NAME_INITIALS with umlaut ö expands to oe`() {
        assertEquals("KOE", NumberPrefixDeriver.fromData(PrefixDataField.NAME_INITIALS, "König"))
    }

    @Test
    fun `NAME_INITIALS with umlaut ä expands to ae`() {
        assertEquals("BAE", NumberPrefixDeriver.fromData(PrefixDataField.NAME_INITIALS, "Bäcker"))
    }

    @Test
    fun `NAME_INITIALS with ß expands to ss`() {
        assertEquals("GRA", NumberPrefixDeriver.fromData(PrefixDataField.NAME_INITIALS, "Graß"))
    }

    @Test
    fun `CATALOGUE_CATEGORY UNIT_PRICE maps to UPE`() {
        assertEquals("UPE", NumberPrefixDeriver.fromData(PrefixDataField.CATALOGUE_CATEGORY, "UNIT_PRICE"))
    }

    @Test
    fun `fromData with null field value returns null`() {
        assertNull(NumberPrefixDeriver.fromData(PrefixDataField.COUNTRY_CODE, null))
    }

    @Test
    fun `NAME_INITIALS with mixed case and numbers preserves letter order`() {
        assertEquals("ABC", NumberPrefixDeriver.fromData(PrefixDataField.NAME_INITIALS, "A1b2C3"))
    }
}
