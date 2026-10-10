package de.littletrotter.domain.shared

import de.littletrotter.shared.Money
import de.littletrotter.shared.sum
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MoneyConverterTest {
    private val conv = MoneyConverter()

    @Test
    fun `convertToDatabaseColumn and back`() {
        assertNull(conv.convertToDatabaseColumn(null))
        val m = Money.of("12.34")
        val db = conv.convertToDatabaseColumn(m)
        assertEquals(BigDecimal("12.34"), db)
        val back = conv.convertToEntityAttribute(db)
        assertEquals(m, back)
    }

    @Test
    fun `values produced by the arithmetic operators pass through unchanged`() {
        // Q1: the operators already normalize to 2 decimals, so the converter has nothing left
        // to round — it must not alter the value on its way to the column.
        listOf(
            Money.of("10.00") * BigDecimal("2.5"),
            Money.of("10.00") / 3,
            Money.of("33.33").percentage(BigDecimal("19")),
            listOf(Money.of("1.10"), Money.of("2.20")).sum(),
        ).forEach { value ->
            val db = conv.convertToDatabaseColumn(value)
            assertEquals(value.amount, db)
            assertEquals(2, db!!.scale())
        }
    }

    @Test
    fun `unrounded intermediate values are still normalized on write`() {
        // Money.exact() deliberately keeps more decimals; the converter is the enforcement point.
        val db = conv.convertToDatabaseColumn(Money.exact(BigDecimal("1.2345")))
        assertEquals(BigDecimal("1.23"), db)
    }
}
