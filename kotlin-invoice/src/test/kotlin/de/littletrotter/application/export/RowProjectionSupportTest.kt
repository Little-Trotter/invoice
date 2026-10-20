package de.littletrotter.application.export

import de.littletrotter.application.export.RowProjectionSupport.bigDecimal
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * [RowProjectionSupport.bigDecimal]'s `else -> null` fallback (a raw tuple value of a
 * type none of its known cases handle) had no direct coverage; [InvoiceHeaderRowMapperTest] and
 * [InvoicePositionRowMapperTest] only ever exercise it through realistic column values.
 */
class RowProjectionSupportTest {

    @Test
    fun `happy path - bigDecimal passes an already-typed BigDecimal through unchanged`() {
        val row: Array<Any?> = arrayOf(BigDecimal("19.99"))
        assertEquals(BigDecimal("19.99"), row.bigDecimal(0))
    }

    @Test
    fun `boundary - bigDecimal converts a Money projection to its underlying amount`() {
        val row: Array<Any?> = arrayOf(Money(BigDecimal("42.50")))
        assertEquals(BigDecimal("42.50"), row.bigDecimal(0))
    }

    @Test
    fun `boundary - bigDecimal unwraps a BigDecimalValueObject projection (QA-24)`() {
        val row: Array<Any?> = arrayOf(TaxRatePercent(BigDecimal("19.00")))
        assertEquals(BigDecimal("19.00"), row.bigDecimal(0))
    }

    @Test
    fun `boundary - bigDecimal returns null for a value of an unsupported type`() {
        val row: Array<Any?> = arrayOf<Any?>(LocalDate.of(2026, 1, 1))
        assertNull(row.bigDecimal(0))
    }

    @Test
    fun `boundary - bigDecimal returns null for a missing column`() {
        val row: Array<Any?> = arrayOfNulls(1)
        assertNull(row.bigDecimal(0))
    }
}
