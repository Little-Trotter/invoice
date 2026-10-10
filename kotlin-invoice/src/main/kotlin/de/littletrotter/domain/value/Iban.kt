package de.littletrotter.domain.value

import de.littletrotter.domain.exception.InvalidParameterException
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * Value Object representing a validated IBAN.
 * Normalizes to uppercase without whitespace and validates checksum + format.
 */
data class Iban(val value: String) {

    init {
        val normalized = value.trim().replace(Regex("\\s+"), "").uppercase()
        IbanValidator.validate(normalized)?.let { throw InvalidParameterException(it) }
    }

    override fun toString(): String = value

    companion object {
        fun ofNullable(value: String?): Iban? = if (value.isNullOrBlank()) null else Iban(value.trim().replace(Regex("\\s+"), "").uppercase())
    }
}

@Converter(autoApply = true)
class IbanConverter : AttributeConverter<Iban?, String?> {
    override fun convertToDatabaseColumn(attribute: Iban?): String? = attribute?.value
    override fun convertToEntityAttribute(dbData: String?): Iban? = if (dbData.isNullOrBlank()) null else Iban(dbData)
}
