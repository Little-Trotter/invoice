package de.littletrotter.application.export

import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class InvoiceTotalsRowMapperTest {

    private fun blankRow(): Array<Any?> = arrayOfNulls(75)
    private val seed = InvoiceHeaderExportRow(invoiceId = 1L, invoiceDate = LocalDate.now())

    @Test
    fun `maps currency, amounts, counts and the needs-recalculation flag`() {
        val row = blankRow()
        row[61] = Currency.CHF.ordinal
        row[63] = BigDecimal("100.00")
        row[64] = Money(BigDecimal("19.00"))
        row[65] = "119.00"
        row[66] = 3
        row[67] = 2
        row[68] = true

        val result = InvoiceTotalsRowMapper.apply(seed, row)

        assertThat(result.currency).isEqualTo(Currency.CHF)
        assertThat(result.netAmount).isEqualTo(Money(BigDecimal("100.00")))
        assertThat(result.vatAmount).isEqualTo(Money(BigDecimal("19.00")))
        assertThat(result.grossAmount).isEqualTo(Money(BigDecimal("119.00")))
        assertThat(result.itemsCount).isEqualTo(3)
        assertThat(result.receiptsCount).isEqualTo(2)
        assertThat(result.needsRecalculation).isTrue()
    }

    @Test
    fun `defaults to EUR, zero amounts, zero counts and false on a blank row`() {
        val result = InvoiceTotalsRowMapper.apply(seed, blankRow())

        assertThat(result.currency).isEqualTo(Currency.EUR)
        assertThat(result.netAmount).isEqualTo(Money.ZERO)
        assertThat(result.vatAmount).isEqualTo(Money.ZERO)
        assertThat(result.grossAmount).isEqualTo(Money.ZERO)
        assertThat(result.itemsCount).isEqualTo(0)
        assertThat(result.receiptsCount).isEqualTo(0)
        assertThat(result.needsRecalculation).isFalse()
    }

    @Test
    fun `decodes a currency supplied as its raw ordinal number`() {
        val row = blankRow()
        row[61] = Currency.CHF.ordinal

        val result = InvoiceTotalsRowMapper.apply(seed, row)

        assertThat(result.currency).isEqualTo(Currency.CHF)
    }

    @Test
    fun `does not disturb fields outside the totals group`() {
        val seeded = seed.copy(invoiceNumber = "IP-1")

        val result = InvoiceTotalsRowMapper.apply(seeded, blankRow())

        assertThat(result.invoiceNumber).isEqualTo("IP-1")
    }
}
