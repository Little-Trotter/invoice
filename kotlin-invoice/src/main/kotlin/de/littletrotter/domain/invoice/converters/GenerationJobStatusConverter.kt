package de.littletrotter.domain.invoice.converters

import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.shared.converters.OrdinalEnumConverter
import jakarta.persistence.Converter

@Converter(autoApply = false)
class GenerationJobStatusConverter : OrdinalEnumConverter<GenerationJobStatus>(GenerationJobStatus.entries)
