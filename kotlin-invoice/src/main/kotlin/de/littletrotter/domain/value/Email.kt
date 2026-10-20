package de.littletrotter.domain.value

import de.littletrotter.domain.exception.InvalidParameterException
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * Value Object representing a validated e-mail address.
 * Validates format on construction using EmailValidator.
 */
data class Email(val value: String) {

    init {
        EmailValidator.validate(value)?.let { throw InvalidParameterException(it) }
    }

    override fun toString(): String = value

    companion object {
        /**
         * Returns null for blank/null input instead of throwing.
         * Use in UI layers where the user may not have entered an email yet.
         */
        fun ofNullable(raw: String?): Email? =
            if (raw.isNullOrBlank()) null else Email(raw.trim())
    }
}

@Converter(autoApply = true)
class EmailConverter : AttributeConverter<Email?, String?> {
    override fun convertToDatabaseColumn(attribute: Email?): String? = attribute?.value
    override fun convertToEntityAttribute(dbData: String?): Email? =
        if (dbData.isNullOrBlank()) null else Email(dbData)
}
