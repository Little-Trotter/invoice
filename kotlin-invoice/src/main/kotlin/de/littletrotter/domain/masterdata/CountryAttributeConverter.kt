package de.littletrotter.domain.masterdata

import de.littletrotter.shared.country.Country
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

@Converter(autoApply = false)
class CountryAttributeConverter : AttributeConverter<Country, Int> {
    override fun convertToDatabaseColumn(attribute: Country?): Int? = attribute?.numeric

    override fun convertToEntityAttribute(dbData: Int?): Country? {
        return dbData?.let { Country.fromNumeric(it) ?: Country.DE }
    }
}
