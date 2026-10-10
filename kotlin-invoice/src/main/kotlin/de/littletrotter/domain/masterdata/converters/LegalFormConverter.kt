package de.littletrotter.domain.masterdata.converters

import de.littletrotter.domain.masterdata.LegalForm
import de.littletrotter.domain.shared.converters.OrdinalEnumConverter
import jakarta.persistence.Converter

@Converter(autoApply = false)
class LegalFormConverter : OrdinalEnumConverter<LegalForm>(LegalForm.entries)
