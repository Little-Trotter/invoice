package de.littletrotter.domain.invoice

import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ItemAmountsTest {

    @Test
    fun `net is quantity times the price rounded to currency scale first`() {
        val (net, vat, gross) = ItemAmounts.calculate(BigDecimal("3"), Money(BigDecimal("33.3333")), BigDecimal("19"))

        assertEquals(Money(BigDecimal("99.99")), net)
        assertEquals(net.percentage(BigDecimal("19")), vat)
        assertEquals(net + vat, gross)
    }

    @Test
    fun `a zero tax rate leaves gross equal to net`() {
        val (net, vat, gross) = ItemAmounts.calculate(BigDecimal("2"), Money(BigDecimal("10.00")), BigDecimal.ZERO)

        assertEquals(Money(BigDecimal("20.00")), net)
        assertEquals(Money.ZERO, vat)
        assertEquals(net, gross)
    }
}
