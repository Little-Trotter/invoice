package de.littletrotter.domain.invoice.converters

import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.shared.converters.OrdinalEnumConverter
import jakarta.persistence.Converter

@Converter(autoApply = false)
class DocumentJobLaneConverter : OrdinalEnumConverter<DocumentJobLane>(DocumentJobLane.entries)
