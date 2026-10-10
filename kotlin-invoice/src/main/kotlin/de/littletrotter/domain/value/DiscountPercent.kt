package de.littletrotter.domain.value

import de.littletrotter.domain.exception.InvalidParameterException
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import java.math.BigDecimal

/**
 * An early-payment discount rate in percentage points, matching
 * `PaymentTerm.discountPercent`/`InvoicePaymentTermSnapshot.discountPercent`. `@JvmInline` over
 * [BigDecimal] so a nullable field does not box (see [TaxRatePercent]'s KDoc for why).
 *
 * The 0..100 range invariant used to live in `PaymentTerm.validate()` (deferred, called just before
 * persist); it now throws at construction. The pairing rule (a non-zero discount percent requires
 * `discountDays` to be set, and vice versa) stays in `PaymentTerm.validate()` — it is a cross-field
 * rule, not an invariant of this single value.
 */
@JvmInline
value class DiscountPercent(override val value: BigDecimal) : BigDecimalValueObject {

    init {
        if (value.signum() < 0 || value > MAX) {
            throw InvalidParameterException("Skontoprozentsatz muss zwischen 0 und 100 liegen.")
        }
    }

    override fun toString(): String = value.toPlainString()

    companion object {
        private val MAX = BigDecimal("100")
        val ZERO = DiscountPercent(BigDecimal.ZERO)
    }
}

@Converter(autoApply = true)
class DiscountPercentConverter : AttributeConverter<DiscountPercent?, BigDecimal?> {
    override fun convertToDatabaseColumn(attribute: DiscountPercent?): BigDecimal? = attribute?.value
    override fun convertToEntityAttribute(dbData: BigDecimal?): DiscountPercent? =
        dbData?.let { DiscountPercent(it) }
}
