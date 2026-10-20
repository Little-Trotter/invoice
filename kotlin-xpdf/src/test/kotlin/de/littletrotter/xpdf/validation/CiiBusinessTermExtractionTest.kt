package de.littletrotter.xpdf.validation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [extractBusinessTerms] in isolation, covering every branch the real Schematron fixtures
 * in [CiiSchematronValidatorTest] cannot cheaply exercise on their own (an unknown code, a
 * duplicate mention, no mention at all) without engineering a dedicated Schematron mutation for
 * each — this is the "SVRL parsing fully covered" requirement's unit-level half.
 */
class CiiBusinessTermExtractionTest {

    @Test
    fun `a known BT code resolves to its canonical officialCode via B2bTerm`() {
        // BT-27 (Seller name) is a real B2bTerm reference constant.
        assertEquals(listOf("BT-27"), extractBusinessTerms("[BR-06]-An Invoice shall contain the Seller name (BT-27)."))
    }

    @Test
    fun `an unknown BT code is kept raw, never dropped`() {
        // BT-9999 is not a B2bTerm constant — fromCodeOrNull must return null, and the raw token
        // must still appear in the result ("unknown BTs stay raw strings, never dropped").
        val result = extractBusinessTerms("Some future assert mentioning (BT-9999) that we don't know yet.")
        assertEquals(listOf("BT-9999"), result)
    }

    @Test
    fun `a duplicated mention of the same code is de-duplicated`() {
        val result = extractBusinessTerms("BT-1 and again BT-1 in the same text.")
        assertEquals(listOf("BT-1"), result)
    }

    @Test
    fun `text with no BT reference at all yields an empty list`() {
        assertEquals(emptyList<String>(), extractBusinessTerms("Element 'ram:SellerTradeParty' must occur exactly 1 times."))
    }

    @Test
    fun `multiple distinct codes are extracted in order of first appearance`() {
        val result = extractBusinessTerms("References both BT-1 and BT-27 in the same assert text.")
        assertEquals(listOf("BT-1", "BT-27"), result)
    }

    @Test
    fun `a known code and an unknown code in the same text both survive`() {
        val result = extractBusinessTerms("BT-27 is known, BT-8888 is not.")
        assertTrue(result.containsAll(listOf("BT-27", "BT-8888")))
        assertEquals(2, result.size)
    }
}
