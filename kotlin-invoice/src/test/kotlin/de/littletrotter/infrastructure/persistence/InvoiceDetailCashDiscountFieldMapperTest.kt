package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * [InvoiceDetailCashDiscountFieldMapper] reproduces
 * [de.littletrotter.domain.invoice.Invoice.cashDiscount]'s snapshot-wins-over-live rule from the
 * header tuple alone — every case here mirrors a branch of that domain function.
 */
class InvoiceDetailCashDiscountFieldMapperTest {

    private fun tuple(
        hasPaymentTermSnapshot: Boolean = false,
        snapshotDiscountPercent: BigDecimal? = null,
        snapshotDiscountDays: Int? = null,
        termDiscountPercent: BigDecimal? = null,
        termDiscountDays: Int? = null,
    ) = MapTuple(
        mapOf(
            "hasPaymentTermSnapshot" to hasPaymentTermSnapshot,
            "snapshotDiscountPercent" to snapshotDiscountPercent,
            "snapshotDiscountDays" to snapshotDiscountDays,
            "termDiscountPercent" to termDiscountPercent,
            "termDiscountDays" to termDiscountDays,
        ),
    )

    @Test
    fun `the frozen snapshot's discount pair wins over the live term's when both are present`() {
        val header = tuple(
            hasPaymentTermSnapshot = true,
            snapshotDiscountPercent = BigDecimal("2"),
            snapshotDiscountDays = 7,
            termDiscountPercent = BigDecimal("5"),
            termDiscountDays = 14,
        )

        val result = InvoiceDetailCashDiscountFieldMapper.map(
            header = header,
            typeCode = null,
            invoiceDate = LocalDate.of(2026, 1, 1),
            grossAmount = Money.of(BigDecimal("604.66")),
        )

        requireNotNull(result)
        assertEquals(0, BigDecimal("2").compareTo(result.percent))
        assertEquals(7, result.days)
        assertEquals("2026-01-08", result.deadline)
        assertEquals(Money.of(BigDecimal("12.09")), result.amount)
        assertEquals(Money.of(BigDecimal("592.57")), result.payableAmount)
    }

    @Test
    fun `the live term's pair is used when no snapshot was frozen`() {
        val header = tuple(
            hasPaymentTermSnapshot = false,
            termDiscountPercent = BigDecimal("2"),
            termDiscountDays = 7,
        )

        val result = InvoiceDetailCashDiscountFieldMapper.map(
            header = header,
            typeCode = null,
            invoiceDate = LocalDate.of(2026, 1, 1),
            grossAmount = Money.of(BigDecimal("604.66")),
        )

        requireNotNull(result)
        assertEquals(0, BigDecimal("2").compareTo(result.percent))
        assertEquals(7, result.days)
    }

    @Test
    fun `a frozen snapshot without a discount wins even when the live term has one`() {
        val header = tuple(
            hasPaymentTermSnapshot = true,
            snapshotDiscountPercent = BigDecimal.ZERO,
            snapshotDiscountDays = null,
            termDiscountPercent = BigDecimal("2"),
            termDiscountDays = 7,
        )

        val result = InvoiceDetailCashDiscountFieldMapper.map(
            header = header,
            typeCode = null,
            invoiceDate = LocalDate.of(2026, 1, 1),
            grossAmount = Money.of(BigDecimal("604.66")),
        )

        assertNull(result)
    }

    @Test
    fun `a storno never carries a discount even with a fully populated discount pair`() {
        val header = tuple(hasPaymentTermSnapshot = true, snapshotDiscountPercent = BigDecimal("2"), snapshotDiscountDays = 7)

        val result = InvoiceDetailCashDiscountFieldMapper.map(
            header = header,
            typeCode = InvoiceTypeCode.CORRECTED_INVOICE.ordinal,
            invoiceDate = LocalDate.of(2026, 1, 1),
            grossAmount = Money.of(BigDecimal("604.66")),
        )

        assertNull(result)
    }

    @Test
    fun `a missing invoice date yields no discount`() {
        val header = tuple(hasPaymentTermSnapshot = true, snapshotDiscountPercent = BigDecimal("2"), snapshotDiscountDays = 7)

        val result = InvoiceDetailCashDiscountFieldMapper.map(
            header = header,
            typeCode = null,
            invoiceDate = null,
            grossAmount = Money.of(BigDecimal("604.66")),
        )

        assertNull(result)
    }

    @Test
    fun `a missing gross amount yields no discount`() {
        val header = tuple(hasPaymentTermSnapshot = true, snapshotDiscountPercent = BigDecimal("2"), snapshotDiscountDays = 7)

        val result = InvoiceDetailCashDiscountFieldMapper.map(
            header = header,
            typeCode = null,
            invoiceDate = LocalDate.of(2026, 1, 1),
            grossAmount = null,
        )

        assertNull(result)
    }

    @Test
    fun `no live term at all (both pairs empty) yields no discount`() {
        val header = tuple(hasPaymentTermSnapshot = false)

        val result = InvoiceDetailCashDiscountFieldMapper.map(
            header = header,
            typeCode = null,
            invoiceDate = LocalDate.of(2026, 1, 1),
            grossAmount = Money.of(BigDecimal("604.66")),
        )

        assertNull(result)
    }
}
