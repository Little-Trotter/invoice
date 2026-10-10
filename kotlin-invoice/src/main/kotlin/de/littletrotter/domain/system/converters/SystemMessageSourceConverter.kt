package de.littletrotter.domain.system.converters

import de.littletrotter.domain.message.SystemMessageSource
import de.littletrotter.domain.shared.converters.OrdinalEnumConverter
import jakarta.persistence.Converter

@Converter(autoApply = false)
class SystemMessageSourceConverter : OrdinalEnumConverter<SystemMessageSource>(SystemMessageSource.entries)
