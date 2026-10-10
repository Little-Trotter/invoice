package de.littletrotter.domain.invoice

import de.littletrotter.domain.shared.MoneyConverter
import de.littletrotter.shared.Money
import jakarta.persistence.Convert
import jakarta.persistence.Embeddable

/**
 * W7 — the three invoice-level monetary totals grouped into one value object. Purely a structural
 * grouping on [Invoice]; every field keeps its exact pre-existing column name via
 * `@AttributeOverride` at the embedding site (`Invoice.totals`), so this is a zero-schema-change
 * refactor. [Invoice.recalculateTotals] remains the sole writer.
 *
 * A plain class with body-level `var`s, not a constructor-parameter `data class` — same reason as
 * [de.littletrotter.domain.masterdata.PartyIdentifier]: no `kotlin-noarg` plugin here, so Hibernate
 * needs the implicit no-arg constructor a `data class` does not produce at the bytecode level.
 *
 * `@Convert` for [Money] must live here, on the embeddable's own attribute — a converter cannot be
 * carried by `@AttributeOverride` at the embedding site, only the column mapping (name/precision/
 * scale) can.
 */
@Embeddable
class InvoiceTotals {

    @Convert(converter = MoneyConverter::class)
    var netAmount: Money = Money.ZERO

    @Convert(converter = MoneyConverter::class)
    var vatAmount: Money = Money.ZERO

    @Convert(converter = MoneyConverter::class)
    var grossAmount: Money = Money.ZERO
}
