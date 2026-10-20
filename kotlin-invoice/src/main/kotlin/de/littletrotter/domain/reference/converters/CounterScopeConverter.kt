package de.littletrotter.domain.reference.converters

import de.littletrotter.domain.reference.CounterScope
import de.littletrotter.domain.shared.converters.OrdinalEnumConverter
import jakarta.persistence.Converter

@Converter(autoApply = false)
class CounterScopeConverter : OrdinalEnumConverter<CounterScope>(CounterScope.entries)
