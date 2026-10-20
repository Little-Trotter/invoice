package de.littletrotter.domain.masterdata.converters

import de.littletrotter.domain.masterdata.ReverseChargePreference
import de.littletrotter.domain.shared.converters.OrdinalEnumConverter
import jakarta.persistence.Converter

@Converter(autoApply = false)
class ReverseChargePreferenceConverter : OrdinalEnumConverter<ReverseChargePreference>(ReverseChargePreference.entries)
