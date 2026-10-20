package de.littletrotter.domain.invoice

import de.littletrotter.domain.shared.MoneyConverter
import de.littletrotter.shared.Money
import jakarta.persistence.Convert
import jakarta.persistence.Embeddable

/**
 * W7 — the three invoice-level monetary totals grouped into one value object. Purely a structural
 * grouping on [Invoice]; every field keeps its exact pre-existing column name via
 * `@AttributeOverride` at the embedding site (`Invoice.totals`), so this is a zero-schema-change
 * refactor. [Invoice.recalculateTotals] remains the sole producer: it replaces the whole value.
 *
 * A plain class, not a `data class`: the constructor parameters all have defaults, so the JVM
 * still gets the no-arg constructor Hibernate needs, while the domain builds a complete, consistent
 * triple in one step instead of writing the three amounts one by one.
 *
 * `@Convert` for [Money] must live here, on the embeddable's own attribute — a converter cannot be
 * carried by `@AttributeOverride` at the embedding site, only the column mapping (name/precision/
 * scale) can.
 */
@Embeddable
class InvoiceTotals(
    netAmount: Money = Money.ZERO,
    vatAmount: Money = Money.ZERO,
    grossAmount: Money = Money.ZERO,
) {

    @Convert(converter = MoneyConverter::class)
    var netAmount: Money = netAmount
        private set

    @Convert(converter = MoneyConverter::class)
    var vatAmount: Money = vatAmount
        private set

    @Convert(converter = MoneyConverter::class)
    var grossAmount: Money = grossAmount
        private set
}
