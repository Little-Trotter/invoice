package de.littletrotter.domain.shared.converters

import jakarta.persistence.AttributeConverter

/**
 * Generic JPA converter that maps any enum to its ordinal (Int) in the database.
 * Subclasses only need to supply the enum entries via [entries].
 *
 *   @Converter(autoApply = false)
 *   class MyEnumConverter : OrdinalEnumConverter<MyEnum>(MyEnum.entries)
 */
abstract class OrdinalEnumConverter<E : Enum<E>>(private val entries: List<E>) : AttributeConverter<E, Int> {
    override fun convertToDatabaseColumn(attribute: E?): Int? = attribute?.ordinal
    override fun convertToEntityAttribute(dbData: Int?): E? = dbData?.let { ordinal ->
        entries.getOrNull(ordinal)
            ?: error("${this::class.simpleName}: no enum constant for persisted ordinal $ordinal (known 0..${entries.size - 1})")
    }
}
