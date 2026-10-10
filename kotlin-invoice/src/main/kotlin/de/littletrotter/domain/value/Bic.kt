package de.littletrotter.domain.value

import de.littletrotter.domain.exception.InvalidParameterException
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * A SWIFT/BIC bank identifier code, ISO 9362 format-validated by [BicValidator].
 * `@JvmInline` over [String] so a nullable field (`Bic?`) does not box — the underlying
 * reference type already represents `null` without an extra wrapper (see [TaxRatePercent]'s
 * KDoc for the same point on a `BigDecimal` base).
 *
 * Always stores the normalised form (trimmed, upper-cased) — construct via [of]/[ofNullable].
 *
 * Carries forward the `@Size(max = 11)`/`VARCHAR(11)` bound `sender_profile.bic` used
 * to enforce via Bean Validation: [BicValidator]'s ISO 9362 shape (exactly 8 or 11 characters)
 * already caps the length at 11, so no separate length check is needed here — [maxLength] states
 * that same 11-character bound for [de.littletrotter.infrastructure.persistence
 * .EntityColumnSizeConsistencyIT].
 */
@JvmInline
value class Bic(val value: String) {

    init {
        if (value.isBlank()) {
            throw InvalidParameterException("BIC darf nicht leer sein.")
        }
        BicValidator.validate(value)?.let { throw InvalidParameterException(it) }
    }

    override fun toString(): String = value

    companion object : BoundedStringValue {
        /** ISO 9362's own longest shape (8 + 3-character branch code) — [BicValidator] already
         *  rejects anything longer, this just states the bound for the column-consistency IT. */
        override val maxLength: Int = 11

        fun of(raw: String): Bic = Bic(raw.trim().uppercase())

        fun ofNullable(raw: String?): Bic? = if (raw.isNullOrBlank()) null else of(raw)
    }
}

@Converter(autoApply = true)
class BicConverter : AttributeConverter<Bic?, String?> {
    override fun convertToDatabaseColumn(attribute: Bic?): String? = attribute?.value
    override fun convertToEntityAttribute(dbData: String?): Bic? = Bic.ofNullable(dbData)
}
