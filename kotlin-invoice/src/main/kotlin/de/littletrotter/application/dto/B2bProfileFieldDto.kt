package de.littletrotter.application.dto

import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.shared.b2b.B2bTerm

/**
 * One field a B2B profile defines: the chosen business [term], the user's own
 * [businessDefinition], how strictly it is required ([requirement], with the triggering
 * [conditionTerm] for a conditional one), where it is captured ([placement]) and an optional
 * [defaultValue] that pre-fills the input on a new record.
 *
 * Read by the profile's rules grid and field editor, and by every form that renders a profile's
 * fields (seller, customer, invoice header, invoice item); also the field editor's save input.
 * [profileId] names the owning profile; [id] `null` marks a new field; [version] is the field's
 * optimistic-lock revision, checked on save.
 */
data class B2bProfileFieldDto(
    val id: Long? = null,
    val version: Long? = null,
    val profileId: Long? = null,
    val term: B2bTerm? = null,
    val businessDefinition: String = "",
    val requirement: B2bFieldRequirement = B2bFieldRequirement.OPTIONAL,
    val conditionTerm: B2bTerm? = null,
    val placement: B2bPlacement = B2bPlacement.INVOICE,
    val sortOrder: Int = 0,
    val defaultValue: String? = null,
)
