package de.littletrotter.domain.invoice.converters

import de.littletrotter.domain.invoice.TripExpenseKind
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * JPA converter for [TripExpenseKind], persisted as [TripExpenseKind.code], never the enum
 * ordinal (project-wide explicit-code convention, see that enum's own KDoc). Reads via
 * [TripExpenseKind.fromCodeOrNull]: a row whose code no longer names a known kind decodes to
 * `null` rather than crashing the read, mirroring
 * [de.littletrotter.domain.shared.converters.InvoiceItemCategoryConverter]'s defensive read.
 */
@Converter(autoApply = false)
class TripExpenseKindConverter : AttributeConverter<TripExpenseKind, Int> {
    override fun convertToDatabaseColumn(attribute: TripExpenseKind?): Int? = attribute?.code

    override fun convertToEntityAttribute(dbData: Int?): TripExpenseKind? = dbData?.let { TripExpenseKind.fromCodeOrNull(it) }
}
