package de.littletrotter.application.dto

import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class InvoiceOverviewMapperRowTest {

    @Test
    fun `fromRow handles InvoiceNumber and Money`() {
        val invoiceNumber = InvoiceNumber.UNASSIGNED
        val money = Money.of("123.45")
        val row: Array<Any?> = arrayOf(42L, invoiceNumber, null, "Seller A", null, "Recipient", money, "Net 30", "text", 3, LocalDate.of(2026, 1, 2), null, null, 30, Currency.CHF)

        val dto = InvoiceOverviewMapper.fromRow(row)
        assertEquals(42L, dto.id)
        assertEquals(invoiceNumber.value, dto.invoiceNumber)
        assertEquals("Seller A", dto.sellerName)
        assertEquals("Recipient", dto.recipientName)
        assertEquals(Money.of("123.45"), dto.grossAmount)
        assertEquals("Net 30", dto.paymentLabel)
        assertEquals(3, dto.itemsCount)
        assertEquals(LocalDate.of(2026, 1, 2), dto.invoiceDate)
        assertEquals(30, row[13] as Int)
        assertEquals(Currency.CHF, dto.currency)
    }

    @Test
    fun `fromRow falls back on types and defaults`() {
        val row: Array<Any?> = arrayOf(null, "", null, null, "Sender B", null, BigDecimal("9.99"), null, null, null, null, null, null, 7, null)
        val dto = InvoiceOverviewMapper.fromRow(row)
        assertNull(dto.id)
        assertEquals("", dto.invoiceNumber)
        assertEquals("Sender B", dto.sellerName)
        assertEquals(Money.of("9.99"), dto.grossAmount)
        assertEquals("7", dto.paymentLabel)
        assertEquals(Currency.EUR, dto.currency, "missing currency column falls back to EUR")
    }

    @Test
    fun `boundary - fromRow falls back to blank invoiceNumber for a raw value of neither type`() {
        val row: Array<Any?> = arrayOf(1L, 12345L, null, "Seller C", null, "Recipient", null, null, null, null, null, null, null, null, null)
        val dto = InvoiceOverviewMapper.fromRow(row)
        assertEquals("", dto.invoiceNumber)
    }

    @Test
    fun `fromRow maps the document generation failure flag and defaults to false for a short row`() {
        val base: Array<Any?> = arrayOf(1L, "RE-1", null, "S", null, "R", null, null, null, 1, null, null, null, null, null, false)

        assertTrue(InvoiceOverviewMapper.fromRow(base + true).documentGenerationFailed)
        assertFalse(InvoiceOverviewMapper.fromRow(base + false).documentGenerationFailed)
        assertFalse(InvoiceOverviewMapper.fromRow(base).documentGenerationFailed, "projection without the column")
        assertFalse(InvoiceOverviewMapper.fromRow(base + (null as Any?)).documentGenerationFailed)
    }
}
