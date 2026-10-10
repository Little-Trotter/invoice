package de.littletrotter.domain.reference.converters

import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.domain.shared.converters.OrdinalEnumConverter
import jakarta.persistence.Converter

@Converter(autoApply = false)
class B2bFieldRequirementConverter : OrdinalEnumConverter<B2bFieldRequirement>(B2bFieldRequirement.entries)
