package de.littletrotter.domain.value

import de.littletrotter.domain.exception.InvalidParameterException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class TaxRatePercentTest {

    @Test
    fun `happy path accepts a normal VAT rate`() {
        val rate = TaxRatePercent(BigDecimal("19.00"))
        assertEquals(BigDecimal("19.00"), rate.value)
        assertEquals("19.00", rate.toString())
    }

    @Test
    fun `boundary zero is accepted`() {
        assertEquals(BigDecimal.ZERO, TaxRatePercent(BigDecimal.ZERO).value)
    }

    @Test
    fun `boundary one hundred is accepted`() {
        assertEquals(BigDecimal("100"), TaxRatePercent(BigDecimal("100")).value)
    }

    @Test
    fun `boundary just below zero is rejected`() {
        assertThrows(InvalidParameterException::class.java) { TaxRatePercent(BigDecimal("-0.01")) }
    }

    @Test
    fun `boundary just above one hundred is rejected`() {
        assertThrows(InvalidParameterException::class.java) { TaxRatePercent(BigDecimal("100.01")) }
    }

    @Test
    fun `converter round-trips a value through the database column type`() {
        val converter = TaxRatePercentConverter()
        val rate = TaxRatePercent(BigDecimal("7.00"))
        assertEquals(BigDecimal("7.00"), converter.convertToDatabaseColumn(rate))
        assertEquals(rate, converter.convertToEntityAttribute(BigDecimal("7.00")))
    }

    @Test
    fun `converter is null-safe in both directions`() {
        val converter = TaxRatePercentConverter()
        assertNull(converter.convertToDatabaseColumn(null))
        assertNull(converter.convertToEntityAttribute(null))
    }
}
