package de.littletrotter.domain.reference

import de.littletrotter.domain.reference.converters.NumberRangeSubjectConverter
import de.littletrotter.domain.reference.converters.PrefixDataFieldConverter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Renaming the catalogue vocabulary must preserve every persisted ordinal. */
class NumberRangeCatalogueOrdinalTest {
    @Test
    fun `subject database values remain invoice zero customer one catalogue two seller three`() {
        val converter = NumberRangeSubjectConverter()
        listOf(NumberRangeSubject.INVOICE, NumberRangeSubject.CUSTOMER, NumberRangeSubject.CATALOGUE, NumberRangeSubject.SELLER)
            .forEachIndexed { ordinal, subject ->
                assertEquals(ordinal, converter.convertToDatabaseColumn(subject))
                assertEquals(subject, converter.convertToEntityAttribute(ordinal))
            }
    }

    @Test
    fun `prefix source database values retain catalogue category at two`() {
        val converter = PrefixDataFieldConverter()
        listOf(PrefixDataField.NAME_INITIALS, PrefixDataField.COUNTRY_CODE, PrefixDataField.CATALOGUE_CATEGORY)
            .forEachIndexed { ordinal, field ->
                assertEquals(ordinal, converter.convertToDatabaseColumn(field))
                assertEquals(field, converter.convertToEntityAttribute(ordinal))
            }
    }
}
