package de.littletrotter.domain.value

import de.littletrotter.domain.exception.InvalidParameterException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class DiscountPercentTest {

    @Test
    fun `happy path accepts a normal discount rate`() {
        val discount = DiscountPercent(BigDecimal("2.00"))
        assertEquals(BigDecimal("2.00"), discount.value)
        assertEquals("2.00", discount.toString())
    }

    @Test
    fun `boundary zero is accepted and matches the ZERO constant`() {
        assertEquals(DiscountPercent.ZERO, DiscountPercent(BigDecimal.ZERO))
    }

    @Test
    fun `boundary one hundred is accepted`() {
        assertEquals(BigDecimal("100"), DiscountPercent(BigDecimal("100")).value)
    }

    @Test
    fun `boundary just below zero is rejected`() {
        assertThrows(InvalidParameterException::class.java) { DiscountPercent(BigDecimal("-0.01")) }
    }

    @Test
    fun `boundary just above one hundred is rejected`() {
        assertThrows(InvalidParameterException::class.java) { DiscountPercent(BigDecimal("100.01")) }
    }

    @Test
    fun `converter round-trips a value through the database column type`() {
        val converter = DiscountPercentConverter()
        val discount = DiscountPercent(BigDecimal("3.50"))
        assertEquals(BigDecimal("3.50"), converter.convertToDatabaseColumn(discount))
        assertEquals(discount, converter.convertToEntityAttribute(BigDecimal("3.50")))
    }

    @Test
    fun `converter is null-safe in both directions`() {
        val converter = DiscountPercentConverter()
        assertNull(converter.convertToDatabaseColumn(null))
        assertNull(converter.convertToEntityAttribute(null))
    }
}
