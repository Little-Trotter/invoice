package de.littletrotter.domain.shared.converters

import de.littletrotter.shared.Currency
import jakarta.persistence.Converter

@Converter(autoApply = false)
class CurrencyConverter : OrdinalEnumConverter<Currency>(Currency.entries)
