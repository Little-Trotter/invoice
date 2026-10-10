package de.littletrotter.domain.reference.converters

import de.littletrotter.domain.reference.PrefixDataField
import de.littletrotter.domain.shared.converters.OrdinalEnumConverter
import jakarta.persistence.Converter

@Converter(autoApply = false)
class PrefixDataFieldConverter : OrdinalEnumConverter<PrefixDataField>(PrefixDataField.entries)
