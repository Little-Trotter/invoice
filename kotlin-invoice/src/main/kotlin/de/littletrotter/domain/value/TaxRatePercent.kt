package de.littletrotter.domain.value

import de.littletrotter.domain.exception.InvalidParameterException
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import java.math.BigDecimal

/**
 * A VAT rate expressed in percentage points (19 means 19 %), matching
 * `TaxRate.rate`/`InvoiceItem.taxRateSnapshot`. Never a monetary amount ([de.littletrotter.shared.Money]
 * stays reserved for those); `@JvmInline` over [BigDecimal] so a nullable field (`TaxRatePercent?`)
 * does not box — the underlying reference type already represents `null` without an extra wrapper.
 *
 * The 0..100 range invariant used to live in a caller's `validate()` (deferred, called just before
 * persist); it now throws at construction instead — deliberately: illegal states become
 * unrepresentable, at the cost of existing tests that assumed the deferred timing needing a rewrite.
 */
@JvmInline
value class TaxRatePercent(override val value: BigDecimal) : BigDecimalValueObject {

    init {
        if (value.signum() < 0 || value > MAX) {
            throw InvalidParameterException("Steuersatz muss zwischen 0 und 100 liegen.")
        }
    }

    override fun toString(): String = value.toPlainString()

    companion object {
        private val MAX = BigDecimal("100")
    }
}

@Converter(autoApply = true)
class TaxRatePercentConverter : AttributeConverter<TaxRatePercent?, BigDecimal?> {
    override fun convertToDatabaseColumn(attribute: TaxRatePercent?): BigDecimal? = attribute?.value
    override fun convertToEntityAttribute(dbData: BigDecimal?): TaxRatePercent? =
        dbData?.let { TaxRatePercent(it) }
}
