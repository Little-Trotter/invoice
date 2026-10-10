package de.littletrotter.domain.invoice

import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.value.DiscountPercent
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Pins every vector of the Skonto calculation spec (section 4): the six amount rows (one rounding
 * step, HALF_UP, at the end), the four deadline rows (calendar days, including a leap-year and a
 * year-boundary case) and the five null cases, plus [Invoice.cashDiscount]'s own
 * snapshot-wins-over-live source rule.
 */
class InvoiceCashDiscountRulesTest {

    // amount rows — gross, percent -> amount, payable

    @Test
    fun `amount row - 1000,00 at 2 percent`() = assertAmountRow("1000.00", "2", "20.00", "980.00")

    @Test
    fun `amount row - 123,45 at 3 percent`() = assertAmountRow("123.45", "3", "3.70", "119.75")

    @Test
    fun `amount row - 999,99 at 2,5 percent`() = assertAmountRow("999.99", "2.5", "25.00", "974.99")

    @Test
    fun `amount row - 12,50 at 1 percent rounds half up`() = assertAmountRow("12.50", "1", "0.13", "12.37")

    @Test
    fun `amount row - 0,01 at 2 percent rounds down to zero`() = assertAmountRow("0.01", "2", "0.00", "0.01")

    @Test
    fun `amount row - 604,66 at 2 percent`() = assertAmountRow("604.66", "2", "12.09", "592.57")

    private fun assertAmountRow(gross: String, percent: String, expectedAmount: String, expectedPayable: String) {
        val result = cashDiscountFor(
            invoiceDate = LocalDate.of(2026, 1, 1),
            discountPercent = DiscountPercent(BigDecimal(percent)),
            discountDays = 14,
            grossAmount = Money.of(BigDecimal(gross)),
        )

        assertEquals(Money.of(BigDecimal(expectedAmount)), result?.amount)
        assertEquals(Money.of(BigDecimal(expectedPayable)), result?.payableAmount)
    }

    // deadline rows — invoice date + n calendar days

    @Test
    fun `deadline row - 2026-02-27 plus 3 days`() = assertDeadlineRow(LocalDate.of(2026, 2, 27), 3, LocalDate.of(2026, 3, 2))

    @Test
    fun `deadline row - 2026-12-30 plus 7 days crosses year boundary`() =
        assertDeadlineRow(LocalDate.of(2026, 12, 30), 7, LocalDate.of(2027, 1, 6))

    @Test
    fun `deadline row - 2028-02-28 plus 1 day lands on leap day`() =
        assertDeadlineRow(LocalDate.of(2028, 2, 28), 1, LocalDate.of(2028, 2, 29))

    @Test
    fun `deadline row - 2026-09-28 plus 0 days stays on the invoice date`() =
        assertDeadlineRow(LocalDate.of(2026, 9, 28), 0, LocalDate.of(2026, 9, 28))

    private fun assertDeadlineRow(invoiceDate: LocalDate, days: Int, expectedDeadline: LocalDate) {
        val result = cashDiscountFor(
            invoiceDate = invoiceDate,
            discountPercent = DiscountPercent(BigDecimal("2")),
            discountDays = days,
            grossAmount = Money.of(BigDecimal("100.00")),
        )

        assertEquals(expectedDeadline, result?.deadline)
    }

    // null cases (section 4)

    @Test
    fun `null case - zero percent yields no discount`() {
        val result = cashDiscountFor(
            invoiceDate = LocalDate.of(2026, 1, 1),
            discountPercent = DiscountPercent.ZERO,
            discountDays = 14,
            grossAmount = Money.of(BigDecimal("100.00")),
        )

        assertNull(result)
    }

    @Test
    fun `null case - no discount days maintained yields no discount`() {
        val result = cashDiscountFor(
            invoiceDate = LocalDate.of(2026, 1, 1),
            discountPercent = DiscountPercent(BigDecimal("2")),
            discountDays = null,
            grossAmount = Money.of(BigDecimal("100.00")),
        )

        assertNull(result)
    }

    @Test
    fun `null case - zero gross amount yields no discount`() {
        val result = cashDiscountFor(
            invoiceDate = LocalDate.of(2026, 1, 1),
            discountPercent = DiscountPercent(BigDecimal("2")),
            discountDays = 14,
            grossAmount = Money.of(BigDecimal("0.00")),
        )

        assertNull(result)
    }

    @Test
    fun `null case - negative gross amount yields no discount`() {
        val result = cashDiscountFor(
            invoiceDate = LocalDate.of(2026, 1, 1),
            discountPercent = DiscountPercent(BigDecimal("2")),
            discountDays = 14,
            grossAmount = Money.of(BigDecimal("-10.00")),
        )

        assertNull(result)
    }

    @Test
    fun `null case - storno invoice yields no discount regardless of a live payment term`() {
        val invoice = invoiceWith(
            typeCode = InvoiceTypeCode.CORRECTED_INVOICE,
            paymentTerm = paymentTermWith(percent = "2", days = 14),
            gross = "100.00",
        )

        assertNull(invoice.cashDiscount())
    }

    // Invoice.cashDiscount() — source resolution

    @Test
    fun `Invoice cashDiscount - a frozen snapshot wins over the live payment term`() {
        val invoice = invoiceWith(
            typeCode = InvoiceTypeCode.COMMERCIAL_INVOICE,
            paymentTerm = paymentTermWith(percent = "9", days = 99),
            gross = "100.00",
        ).apply {
            paymentTermSnapshot = InvoicePaymentTermSnapshot().apply {
                discountPercent = DiscountPercent(BigDecimal("2"))
                discountDays = 14
            }
        }

        val result = invoice.cashDiscount()

        assertEquals(DiscountPercent(BigDecimal("2")), result?.percent)
        assertEquals(14, result?.days)
    }

    @Test
    fun `Invoice cashDiscount - a draft without a snapshot uses the live payment term`() {
        val invoice = invoiceWith(
            typeCode = InvoiceTypeCode.COMMERCIAL_INVOICE,
            paymentTerm = paymentTermWith(percent = "3", days = 7),
            gross = "100.00",
        )

        val result = invoice.cashDiscount()

        assertEquals(DiscountPercent(BigDecimal("3")), result?.percent)
        assertEquals(7, result?.days)
    }

    @Test
    fun `Invoice cashDiscount - a storno document has no discount`() {
        val invoice = invoiceWith(
            typeCode = InvoiceTypeCode.CORRECTED_INVOICE,
            paymentTerm = null,
            gross = "100.00",
        )

        assertNull(invoice.cashDiscount())
    }

    @Test
    fun `Invoice cashDiscount - no payment term at all has no discount`() {
        val invoice = invoiceWith(
            typeCode = InvoiceTypeCode.COMMERCIAL_INVOICE,
            paymentTerm = null,
            gross = "100.00",
        )

        assertNull(invoice.cashDiscount())
    }

    private fun paymentTermWith(percent: String, days: Int): PaymentTerm = PaymentTerm().apply {
        discountPercent = DiscountPercent(BigDecimal(percent))
        discountDays = days
    }

    private fun invoiceWith(typeCode: InvoiceTypeCode, paymentTerm: PaymentTerm?, gross: String): Invoice = Invoice().apply {
        this.typeCode = typeCode
        this.paymentTerm = paymentTerm
        invoiceDate = LocalDate.of(2026, 1, 1)
        totals.grossAmount = Money.of(BigDecimal(gross))
    }
}
