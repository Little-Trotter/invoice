package de.littletrotter.domain.invoice.converters

import de.littletrotter.domain.invoice.DocumentGenerationStatus
import de.littletrotter.domain.shared.converters.OrdinalEnumConverter
import jakarta.persistence.Converter

@Converter(autoApply = false)
class DocumentGenerationStatusConverter : OrdinalEnumConverter<DocumentGenerationStatus>(DocumentGenerationStatus.entries)
