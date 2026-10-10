package de.littletrotter.domain.reference.converters

import de.littletrotter.domain.reference.Continuity
import de.littletrotter.domain.shared.converters.OrdinalEnumConverter
import jakarta.persistence.Converter

@Converter(autoApply = false)
class ContinuityConverter : OrdinalEnumConverter<Continuity>(Continuity.entries)
