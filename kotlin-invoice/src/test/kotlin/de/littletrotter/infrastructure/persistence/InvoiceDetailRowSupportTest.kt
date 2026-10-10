package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.invoice.DocumentEngine
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** direct unit tests for the tolerant per-cell parsing shared by every InvoiceDetail*
 *  field mapper, relocated out of InvoiceReadRepositoryImplTest's own boundary tests. */
class InvoiceDetailRowSupportTest {

    @Test
    fun `parseInvoiceNumber decodes null, InvoiceNumber, String and an unexpected type`() {
        assertEquals("", InvoiceDetailRowSupport.parseInvoiceNumber(null))
        assertEquals("RE-2026-0009", InvoiceDetailRowSupport.parseInvoiceNumber(InvoiceNumber("RE-2026-0009")))
        assertEquals("RE-2", InvoiceDetailRowSupport.parseInvoiceNumber("RE-2"))
        assertEquals("42", InvoiceDetailRowSupport.parseInvoiceNumber(42L))
    }

    @Test
    fun `parseInvoiceStatusOrdinal decodes null, Number, InvoiceStatus, a resolvable and an unresolvable name`() {
        assertEquals(InvoiceStatus.DRAFT.ordinal, InvoiceDetailRowSupport.parseInvoiceStatusOrdinal(null))
        assertEquals(1, InvoiceDetailRowSupport.parseInvoiceStatusOrdinal(1))
        assertEquals(InvoiceStatus.SENT.ordinal, InvoiceDetailRowSupport.parseInvoiceStatusOrdinal(InvoiceStatus.SENT))
        assertEquals(InvoiceStatus.SENT.ordinal, InvoiceDetailRowSupport.parseInvoiceStatusOrdinal("SENT"))
        assertEquals(InvoiceStatus.DRAFT.ordinal, InvoiceDetailRowSupport.parseInvoiceStatusOrdinal("NOT_A_STATUS"))
        assertEquals(InvoiceStatus.DRAFT.ordinal, InvoiceDetailRowSupport.parseInvoiceStatusOrdinal(true))
    }

    @Test
    fun `parseCurrencyOrdinal decodes null, Number, Currency, numeric-String and an unexpected type`() {
        assertNull(InvoiceDetailRowSupport.parseCurrencyOrdinal(null))
        assertEquals(2, InvoiceDetailRowSupport.parseCurrencyOrdinal(2))
        assertEquals(Currency.CHF.ordinal, InvoiceDetailRowSupport.parseCurrencyOrdinal(Currency.CHF))
        assertEquals(1, InvoiceDetailRowSupport.parseCurrencyOrdinal("1"))
        assertNull(InvoiceDetailRowSupport.parseCurrencyOrdinal(true))
    }

    @Test
    fun `parseOrdinal decodes null, Number, any Enum, numeric-String and an unexpected type`() {
        assertNull(InvoiceDetailRowSupport.parseOrdinal(null))
        assertEquals(3, InvoiceDetailRowSupport.parseOrdinal(3))
        assertEquals(DocumentEngine.FACTURX.ordinal, InvoiceDetailRowSupport.parseOrdinal(DocumentEngine.FACTURX))
        assertEquals(0, InvoiceDetailRowSupport.parseOrdinal("0"))
        assertNull(InvoiceDetailRowSupport.parseOrdinal(true))
    }

    @Test
    fun `parseBigDecimal decodes null, BigDecimal, Money, Number, String and an unexpected type`() {
        assertNull(InvoiceDetailRowSupport.parseBigDecimal(null))
        assertEquals(BigDecimal("1.5"), InvoiceDetailRowSupport.parseBigDecimal(BigDecimal("1.5")))
        assertEquals(BigDecimal("19.00"), InvoiceDetailRowSupport.parseBigDecimal(Money(BigDecimal("19.00"))))
        assertEquals(BigDecimal("7"), InvoiceDetailRowSupport.parseBigDecimal(7))
        assertEquals(BigDecimal("8.00"), InvoiceDetailRowSupport.parseBigDecimal("8.00"))
        assertNull(InvoiceDetailRowSupport.parseBigDecimal(true))
    }

    @Test
    fun `parseMoney wraps parseBigDecimal, null stays null`() {
        assertEquals(Money(BigDecimal("100")), InvoiceDetailRowSupport.parseMoney(BigDecimal("100")))
        assertNull(InvoiceDetailRowSupport.parseMoney(null))
    }

    @Test
    fun `parseString calls toString, null stays null`() {
        assertEquals("42", InvoiceDetailRowSupport.parseString(42L))
        assertNull(InvoiceDetailRowSupport.parseString(null))
    }
}
