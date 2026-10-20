package de.littletrotter.domain.reference.converters

import de.littletrotter.domain.reference.NumberRangeSubject
import de.littletrotter.domain.shared.converters.OrdinalEnumConverter
import jakarta.persistence.Converter

@Converter(autoApply = false)
class NumberRangeSubjectConverter : OrdinalEnumConverter<NumberRangeSubject>(NumberRangeSubject.entries)
