package de.littletrotter.domain.shared.converters

import de.littletrotter.domain.shared.ValidationStatus
import jakarta.persistence.Converter

@Converter(autoApply = false)
class ValidationStatusConverter : OrdinalEnumConverter<ValidationStatus>(ValidationStatus.entries)
