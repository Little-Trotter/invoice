package de.littletrotter.domain.system.converters

import de.littletrotter.domain.message.SystemMessageSeverity
import de.littletrotter.domain.shared.converters.OrdinalEnumConverter
import jakarta.persistence.Converter

@Converter(autoApply = false)
class SystemMessageSeverityConverter : OrdinalEnumConverter<SystemMessageSeverity>(SystemMessageSeverity.entries)
