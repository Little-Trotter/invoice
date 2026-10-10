package de.littletrotter.domain.invoice.converters

import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.domain.shared.converters.OrdinalEnumConverter
import jakarta.persistence.Converter

@Converter(autoApply = false)
class InvoiceTypeCodeConverter : OrdinalEnumConverter<InvoiceTypeCode>(InvoiceTypeCode.entries)
