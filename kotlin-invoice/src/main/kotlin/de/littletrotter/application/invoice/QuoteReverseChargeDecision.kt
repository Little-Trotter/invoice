package de.littletrotter.application.invoice

import de.littletrotter.application.quote.port.inbound.QuoteItem
import de.littletrotter.shared.tax.VatCategory

/**
 * The offer cannot be asked whether Reverse Charge applies, but its positions already carry the
 * answer: they were taxed as Reverse Charge or they were not. A mix has no single header
 * decision, so it is refused instead of guessed.
 */
internal object QuoteReverseChargeDecision {

    /** `true` if every position is Reverse Charge, `false` if none is (or there are none), `null` for a mix. */
    fun derive(items: List<QuoteItem>): Boolean? {
        val reverseCharge = items.count { it.vatCategory == VatCategory.REVERSE_CHARGE }
        return when (reverseCharge) {
            0 -> false
            items.size -> true
            else -> null
        }
    }
}
