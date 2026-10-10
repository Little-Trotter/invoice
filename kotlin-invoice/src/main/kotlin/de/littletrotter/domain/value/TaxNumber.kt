package de.littletrotter.domain.value

import de.littletrotter.domain.exception.InvalidParameterException
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * A German tax number (Steuernummer), format-validated by [GermanTaxNumberValidator].
 * `@JvmInline` over [String] so a nullable field (`TaxNumber?`) does not box (see
 * [TaxRatePercent]'s KDoc for why nullable reference-typed value classes are exempt).
 *
 * Only trims — never strips the `181/815/08155`-style slash separators [GermanTaxNumberValidator]
 * merely tolerates, so a stored value stays byte-identical to what the Anwender typed (existing
 * `SenderProfileFormData.applyTo`/golden-fixture behaviour). Construct via [of]/[ofNullable].
 *
 * [MAX_LENGTH] carries forward the `@Size(max = 50)`/`VARCHAR(50)` bound the raw
 * `String` fields (`sender_profile.tax_number`, `invoice_party_snapshot.tax_number`) used to
 * enforce via Bean Validation; the value object is now the single place that invariant lives.
 */
@JvmInline
value class TaxNumber(val value: String) {

    init {
        if (value.isBlank()) {
            throw InvalidParameterException("Steuernummer darf nicht leer sein.")
        }
        if (value.length > MAX_LENGTH) {
            throw InvalidParameterException("Steuernummer darf höchstens $MAX_LENGTH Zeichen lang sein.")
        }
        GermanTaxNumberValidator.validate(value).ifPresent { throw InvalidParameterException(it) }
    }

    override fun toString(): String = value

    companion object : BoundedStringValue {
        const val MAX_LENGTH = 50

        override val maxLength: Int = MAX_LENGTH

        fun of(raw: String): TaxNumber = TaxNumber(raw.trim())

        fun ofNullable(raw: String?): TaxNumber? = if (raw.isNullOrBlank()) null else of(raw)
    }
}

@Converter(autoApply = true)
class TaxNumberConverter : AttributeConverter<TaxNumber?, String?> {
    override fun convertToDatabaseColumn(attribute: TaxNumber?): String? = attribute?.value
    override fun convertToEntityAttribute(dbData: String?): TaxNumber? = TaxNumber.ofNullable(dbData)
}
