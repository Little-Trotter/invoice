package de.littletrotter.domain.shared.converters

import de.littletrotter.shared.catalogue.InvoiceItemUnit
import jakarta.persistence.Converter

@Converter(autoApply = false)
class InvoiceItemUnitConverter : OrdinalEnumConverter<InvoiceItemUnit>(InvoiceItemUnit.entries)
