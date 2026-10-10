package de.littletrotter.infrastructure.persistence

import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class InvoiceDetailTotalsFieldMapperTest {

    @Test
    fun `maps net, vat, gross amounts and currency`() {
        val tuple = MapTuple(
            mapOf(
                "netAmount" to BigDecimal("100"),
                "vatAmount" to BigDecimal("19"),
                "grossAmount" to BigDecimal("119"),
                "currency" to Currency.CHF.ordinal,
            ),
        )

        val result = InvoiceDetailTotalsFieldMapper.map(tuple)

        assertEquals(Money(BigDecimal("100")), result.netAmount)
        assertEquals(Money(BigDecimal("19")), result.vatAmount)
        assertEquals(Money(BigDecimal("119")), result.grossAmount)
        assertEquals(Currency.CHF.ordinal, result.currency)
    }

    @Test
    fun `a null cell decodes every field to null`() {
        val tuple = MapTuple(mapOf("netAmount" to null, "vatAmount" to null, "grossAmount" to null, "currency" to null))

        val result = InvoiceDetailTotalsFieldMapper.map(tuple)

        assertNull(result.netAmount)
        assertNull(result.vatAmount)
        assertNull(result.grossAmount)
        assertNull(result.currency)
    }

    @Test
    fun `a String amount cell decodes tolerantly via toBigDecimalOrNull`() {
        val tuple = MapTuple(mapOf("netAmount" to "100.00", "vatAmount" to "19.00", "grossAmount" to "119.00", "currency" to "1"))

        val result = InvoiceDetailTotalsFieldMapper.map(tuple)

        assertEquals(Money(BigDecimal("100.00")), result.netAmount)
        assertEquals(1, result.currency)
    }

    @Test
    fun `a Money-typed amount cell decodes through the shared Money branch`() {
        val tuple = MapTuple(mapOf("netAmount" to Money(BigDecimal("50.00")), "vatAmount" to null, "grossAmount" to null, "currency" to null))

        val result = InvoiceDetailTotalsFieldMapper.map(tuple)

        assertEquals(Money(BigDecimal("50.00")), result.netAmount)
    }
}
