package de.littletrotter.application.dto

import de.littletrotter.domain.reference.B2bFieldValueOrigin

/**
 * One B2B field's effective value on an invoice: [value] (invoice override, else the party's
 * master data, else the profile default), the [origin] tier it came from, and [inheritedValue] —
 * what the value would be without an invoice-level override, which the guided entry compares
 * against to tell a genuine override from an untouched inherited value.
 */
data class ResolvedB2bFieldValueDto(
    val value: String?,
    val origin: B2bFieldValueOrigin,
    val inheritedValue: String?,
)
