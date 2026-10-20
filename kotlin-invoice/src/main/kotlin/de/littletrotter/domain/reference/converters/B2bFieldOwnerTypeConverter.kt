package de.littletrotter.domain.reference.converters

import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.shared.converters.OrdinalEnumConverter
import jakarta.persistence.Converter

@Converter(autoApply = false)
class B2bFieldOwnerTypeConverter : OrdinalEnumConverter<B2bFieldOwnerType>(B2bFieldOwnerType.entries)
