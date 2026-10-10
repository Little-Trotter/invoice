package de.littletrotter.domain.value

import java.math.BigDecimal

/**
 * Marker for a `@JvmInline value class` wrapping a [BigDecimal] domain quantity that is not a
 * monetary amount (a rate, a percentage, a weighting factor — those stay [de.littletrotter.shared.Money]).
 *
 * Hibernate applies an attribute's [jakarta.persistence.AttributeConverter] to Criteria/JPQL scalar
 * tuple projections too (the same way it already does for [de.littletrotter.shared.Money] on a
 * `MoneyConverter`-backed column) — a raw `Array<Any?>` row-mapper reading such a projection sees the
 * converted value-class instance, not the DB column's plain `BigDecimal`. Implementing this marker
 * lets [de.littletrotter.application.export.RowProjectionSupport.bigDecimal] unwrap any current or
 * future `BigDecimal`-based value class generically, instead of growing one `is XyzPercent -> ...`
 * branch per type.
 */
interface BigDecimalValueObject {
    val value: BigDecimal
}
