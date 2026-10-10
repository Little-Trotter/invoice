package de.littletrotter.domain.reference.converters

import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.domain.shared.converters.OrdinalEnumConverter
import jakarta.persistence.Converter

@Converter(autoApply = false)
class B2bPlacementConverter : OrdinalEnumConverter<B2bPlacement>(B2bPlacement.entries)
