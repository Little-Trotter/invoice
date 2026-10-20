package de.littletrotter.domain.shared.converters

import de.littletrotter.domain.shared.InvoiceFormat
import jakarta.persistence.Converter

@Converter(autoApply = false)
class InvoiceFormatConverter : OrdinalEnumConverter<InvoiceFormat>(InvoiceFormat.entries)
