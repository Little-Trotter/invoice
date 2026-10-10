package de.littletrotter.domain.masterdata.converters

import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.shared.converters.OrdinalEnumConverter
import jakarta.persistence.Converter

@Converter(autoApply = false)
class CustomerTypeConverter : OrdinalEnumConverter<CustomerType>(CustomerType.entries)
