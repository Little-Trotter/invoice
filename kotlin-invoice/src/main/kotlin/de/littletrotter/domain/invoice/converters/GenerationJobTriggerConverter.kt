package de.littletrotter.domain.invoice.converters

import de.littletrotter.domain.invoice.GenerationJobTrigger
import de.littletrotter.domain.shared.converters.OrdinalEnumConverter
import jakarta.persistence.Converter

@Converter(autoApply = false)
class GenerationJobTriggerConverter : OrdinalEnumConverter<GenerationJobTrigger>(GenerationJobTrigger.entries)
