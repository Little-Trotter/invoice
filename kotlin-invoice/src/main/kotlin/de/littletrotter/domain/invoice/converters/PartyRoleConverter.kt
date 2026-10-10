package de.littletrotter.domain.invoice.converters

import de.littletrotter.domain.invoice.PartyRole
import de.littletrotter.domain.shared.converters.OrdinalEnumConverter
import jakarta.persistence.Converter

@Converter(autoApply = false)
class PartyRoleConverter : OrdinalEnumConverter<PartyRole>(PartyRole.entries)
