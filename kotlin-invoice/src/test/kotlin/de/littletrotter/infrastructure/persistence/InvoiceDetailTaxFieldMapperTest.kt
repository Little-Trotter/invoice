package de.littletrotter.infrastructure.persistence

import de.littletrotter.shared.country.Country
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InvoiceDetailTaxFieldMapperTest {

    @Test
    fun `maps reverseCharge, payment term name, seller VAT country and payment term id`() {
        val tuple = MapTuple(
            mapOf(
                "reverseCharge" to true,
                "paymentTermName" to "30 Tage netto",
                "sellerVatRegistrationCountry" to Country.FR,
                "paymentTermId" to 7L,
            ),
        )

        val result = InvoiceDetailTaxFieldMapper.map(tuple)

        assertTrue(result.reverseCharge)
        assertEquals("30 Tage netto", result.paymentTermName)
        assertEquals("FR", result.sellerVatRegistrationCountry)
        assertEquals(7L, result.paymentTermId)
    }

    @Test
    fun `reverseCharge false and a null seller VAT country, payment term name and id`() {
        val tuple = MapTuple(
            mapOf(
                "reverseCharge" to false,
                "paymentTermName" to null,
                "sellerVatRegistrationCountry" to null,
                "paymentTermId" to null,
            ),
        )

        val result = InvoiceDetailTaxFieldMapper.map(tuple)

        assertFalse(result.reverseCharge)
        assertNull(result.paymentTermName)
        assertNull(result.sellerVatRegistrationCountry)
        assertNull(result.paymentTermId)
    }

    @Test
    fun `a non-Country sellerVatRegistrationCountry cell decodes to null`() {
        val tuple = MapTuple(
            mapOf("reverseCharge" to false, "paymentTermName" to null, "sellerVatRegistrationCountry" to "not-a-country", "paymentTermId" to null),
        )

        val result = InvoiceDetailTaxFieldMapper.map(tuple)

        assertNull(result.sellerVatRegistrationCountry)
    }
}
