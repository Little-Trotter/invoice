package de.littletrotter.domain.reference.converters

import de.littletrotter.domain.reference.PrefixSource
import de.littletrotter.domain.shared.converters.OrdinalEnumConverter
import jakarta.persistence.Converter

@Converter(autoApply = false)
class PrefixSourceConverter : OrdinalEnumConverter<PrefixSource>(PrefixSource.entries)
