package de.littletrotter.domain.system.converters

import de.littletrotter.domain.system.DatabaseMaintenanceType
import de.littletrotter.domain.shared.converters.OrdinalEnumConverter
import jakarta.persistence.Converter

@Converter(autoApply = false)
class DatabaseMaintenanceTypeConverter : OrdinalEnumConverter<DatabaseMaintenanceType>(DatabaseMaintenanceType.entries)
