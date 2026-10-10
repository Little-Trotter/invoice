package de.littletrotter.domain.shared

import de.littletrotter.shared.Money
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import java.math.BigDecimal

/**
 * JPA converter for [Money]. Stays in `kotlin-invoice` (unlike [Money] itself, which moved to
 * `kotlin-shared`) because it needs `jakarta.persistence`, a dependency `kotlin-shared` must not
 * gain — `kotlin-xpdf` also depends on `kotlin-shared` and has no JPA of its own.
 */
@Converter(autoApply = true)
class MoneyConverter : AttributeConverter<Money?, BigDecimal?> {
    // Every JPA-mapped Money field is normalized to 2 decimals on write, regardless of how its
    // in-memory value was produced — the actual enforcement point for "the DB only accepts two
    // decimal places" (task A).
    override fun convertToDatabaseColumn(attribute: Money?): BigDecimal? =
        attribute?.amount?.setScale(Money.SCALE, Money.ROUNDING)
    override fun convertToEntityAttribute(dbData: BigDecimal?): Money? = dbData?.let { Money(it) }
}
