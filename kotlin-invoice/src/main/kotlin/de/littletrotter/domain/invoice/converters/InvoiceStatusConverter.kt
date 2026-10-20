package de.littletrotter.domain.invoice.converters

import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.shared.converters.OrdinalEnumConverter
import jakarta.persistence.Converter

@Converter(autoApply = false)
class InvoiceStatusConverter : OrdinalEnumConverter<InvoiceStatus>(InvoiceStatus.entries)
