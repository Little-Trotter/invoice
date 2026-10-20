package de.littletrotter.application.reference

import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.application.dto.B2bSellerFieldGroupDto
import de.littletrotter.application.dto.ResolvedB2bFieldValueDto
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.reference.B2bProfileDefinition
import de.littletrotter.domain.reference.B2bProfileField
import de.littletrotter.domain.reference.B2bProfileFieldDefinition
import de.littletrotter.domain.reference.B2bSellerFieldGroup
import de.littletrotter.domain.reference.ResolvedB2bFieldValue

/** Row view of a stored B2B profile, without its fields (those are read per profile). */
internal fun B2bProfile.toDto(): B2bProfileDto = B2bProfileDto(
    id = id,
    version = version,
    name = name,
    description = description,
    active = active,
    countries = countries.toSet(),
)

/** Copies every editable field of this row onto [profile]; id, revision and the profile's
 *  own fields stay the entity's. */
internal fun B2bProfileDto.applyTo(profile: B2bProfile) {
    profile.redefine(B2bProfileDefinition(name, description, active, countries))
}

/** Row view of a stored profile field; the owning profile is named by id only. */
internal fun B2bProfileField.toDto(): B2bProfileFieldDto = B2bProfileFieldDto(
    id = id,
    version = version,
    profileId = profile?.id,
    term = term,
    businessDefinition = businessDefinition,
    requirement = requirement,
    conditionTerm = conditionTerm,
    placement = placement,
    sortOrder = sortOrder,
    defaultValue = defaultValue,
)

/** Copies every editable field of this row onto [field] and points it at [profile], the
 *  already-resolved owner ([B2bProfileFieldDto.profileId]); id and revision stay the entity's. */
internal fun B2bProfileFieldDto.applyTo(field: B2bProfileField, profile: B2bProfile?) {
    field.redefine(
        profile,
        B2bProfileFieldDefinition(
            term = term,
            businessDefinition = businessDefinition,
            requirement = requirement,
            conditionTerm = conditionTerm,
            placement = placement,
            sortOrder = sortOrder,
            defaultValue = defaultValue,
        ),
    )
}

/** Seller-form view of one merged seller field. */
internal fun B2bSellerFieldGroup.toDto(): B2bSellerFieldGroupDto =
    B2bSellerFieldGroupDto(field = field.toDto(), group = group, triggerCountries = triggerCountries)

/** Guided-entry view of one resolved field value. */
internal fun ResolvedB2bFieldValue.toDto(): ResolvedB2bFieldValueDto =
    ResolvedB2bFieldValueDto(value = value, origin = origin, inheritedValue = inheritedValue)
