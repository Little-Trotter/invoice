package de.littletrotter.domain.value

import de.littletrotter.domain.exception.InvalidParameterException
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * The national tax number a seller holds in the country of one of its foreign VAT registrations,
 * stored in the normalised form of [ForeignTaxNumberValidator.normalize].
 *
 * The aggregate checks only what holds for every country (not blank, [MAX_LENGTH], letters and
 * digits). Whether the structure matches the country's official layout is a warning in the UI and
 * never a reason to refuse the save, because a layout rule can lag behind an authority's change
 * while the seller still has to print the number it was issued.
 */
@JvmInline
value class ForeignTaxNumber(val value: String) {

    init {
        if (value.isBlank()) {
            throw InvalidParameterException("Steuernummer darf nicht leer sein.")
        }
        if (value.length > MAX_LENGTH) {
            throw InvalidParameterException("Steuernummer darf höchstens $MAX_LENGTH Zeichen lang sein.")
        }
        if (!value.all { it.isLetterOrDigit() }) {
            throw InvalidParameterException("Steuernummer darf nur Buchstaben und Ziffern enthalten.")
        }
    }

    override fun toString(): String = value

    companion object : BoundedStringValue {
        const val MAX_LENGTH = 50

        override val maxLength: Int = MAX_LENGTH

        /** Normalises first, so the stored value is independent of how the user typed it. */
        fun of(raw: String): ForeignTaxNumber = ForeignTaxNumber(ForeignTaxNumberValidator.normalize(raw))

        fun ofNullable(raw: String?): ForeignTaxNumber? = if (raw.isNullOrBlank()) null else of(raw)
    }
}

@Converter(autoApply = true)
class ForeignTaxNumberConverter : AttributeConverter<ForeignTaxNumber?, String?> {
    override fun convertToDatabaseColumn(attribute: ForeignTaxNumber?): String? = attribute?.value
    override fun convertToEntityAttribute(dbData: String?): ForeignTaxNumber? = ForeignTaxNumber.ofNullable(dbData)
}
