package de.littletrotter.domain.invoice

import de.littletrotter.shared.Money
import java.math.BigDecimal

/**
 * The "quantity x price (+ tax)" formula, factored out so the UI amount preview and the travel
 * dialog compute the same numbers a saved position will have, instead of maintaining their own
 * copy. Pure — no [InvoiceItem] instance required, so it works before one exists.
 */
object ItemAmounts {
    /** Returns net, VAT and gross, in that order. */
    fun calculate(quantity: BigDecimal, unitPriceNet: Money, taxRate: BigDecimal): Triple<Money, Money, Money> {
        // The price is rounded to Money's scale here, not just at persistence: a caller that
        // has not yet gone through a save round-trip (MoneyConverter) could still hold an
        // unrounded in-memory unitPriceNet (e.g. 33.3333). Rounding before the multiply
        // guarantees the calculation always matches what will end up printed and stored —
        // 3 x 33.33 = 99.99, not 3 x 33.3333 rounded afterwards to 100.00.
        val effectiveUnitPrice = Money.of(unitPriceNet.amount)
        val net = effectiveUnitPrice * quantity
        val vat = net.percentage(taxRate)
        val gross = net + vat
        return Triple(net, vat, gross)
    }
}
