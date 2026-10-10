package de.littletrotter.domain.reference.converters

import de.littletrotter.shared.numbering.ResetCycle
import de.littletrotter.domain.shared.converters.OrdinalEnumConverter
import jakarta.persistence.Converter

@Converter(autoApply = false)
class ResetCycleConverter : OrdinalEnumConverter<ResetCycle>(ResetCycle.entries)
