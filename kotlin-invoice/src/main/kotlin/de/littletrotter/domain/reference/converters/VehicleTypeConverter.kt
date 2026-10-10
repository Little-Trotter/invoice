package de.littletrotter.domain.reference.converters

import de.littletrotter.domain.reference.VehicleType
import de.littletrotter.domain.shared.converters.OrdinalEnumConverter
import jakarta.persistence.Converter

@Converter(autoApply = false)
class VehicleTypeConverter : OrdinalEnumConverter<VehicleType>(VehicleType.entries)
