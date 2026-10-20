package de.littletrotter.domain.system.converters

import de.littletrotter.domain.system.DatabaseMaintenanceStatus
import de.littletrotter.domain.shared.converters.OrdinalEnumConverter
import jakarta.persistence.Converter

@Converter(autoApply = false)
class DatabaseMaintenanceStatusConverter : OrdinalEnumConverter<DatabaseMaintenanceStatus>(DatabaseMaintenanceStatus.entries)
