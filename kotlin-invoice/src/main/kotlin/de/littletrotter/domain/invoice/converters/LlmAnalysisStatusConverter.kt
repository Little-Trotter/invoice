package de.littletrotter.domain.invoice.converters

import de.littletrotter.domain.invoice.LlmAnalysisStatus
import de.littletrotter.domain.shared.converters.OrdinalEnumConverter
import jakarta.persistence.Converter

@Converter(autoApply = false)
class LlmAnalysisStatusConverter : OrdinalEnumConverter<LlmAnalysisStatus>(LlmAnalysisStatus.entries)
