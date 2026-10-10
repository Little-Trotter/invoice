package de.littletrotter.domain.reference.converters

import de.littletrotter.domain.reference.LocalizedTextPurpose
import de.littletrotter.domain.shared.converters.OrdinalEnumConverter
import jakarta.persistence.Converter

@Converter(autoApply = false)
class LocalizedTextPurposeConverter : OrdinalEnumConverter<LocalizedTextPurpose>(LocalizedTextPurpose.entries)
