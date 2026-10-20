package de.littletrotter.domain.masterdata.converters

import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.shared.converters.OrdinalEnumConverter
import jakarta.persistence.Converter

@Converter(autoApply = false)
class AddressTypeConverter : OrdinalEnumConverter<Address.AddressType>(Address.AddressType.entries)
