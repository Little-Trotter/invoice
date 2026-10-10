package de.littletrotter.domain.reference.converters

import de.littletrotter.shared.numbering.DatePart
import de.littletrotter.domain.shared.converters.OrdinalEnumConverter
import jakarta.persistence.Converter

@Converter(autoApply = false)
class DatePartConverter : OrdinalEnumConverter<DatePart>(DatePart.entries)
