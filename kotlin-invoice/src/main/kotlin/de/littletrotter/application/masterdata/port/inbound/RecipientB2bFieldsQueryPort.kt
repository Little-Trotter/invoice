package de.littletrotter.application.masterdata.port.inbound

import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.application.dto.B2bProfileFieldDto

/**
 * Input port of the "maintain a customer's standing B2B profile" use case: the customer form's
 * profile selector and its "B2B-Felder" section read the selectable profiles and the profile's
 * `RECIPIENT`-placed fields, and load/save the customer's own values for them — nothing else.
 *
 * Not marked as a [de.littletrotter.application.command.CommandEntryPoint]: the one write,
 * [saveB2bRecipientFieldValues], completes the same form round trip on the same per-customer
 * value table as the reads, not a command boundary of its own (same shape as
 * [de.littletrotter.application.invoice.port.inbound.ItemEntryQueryPort]).
 */
interface RecipientB2bFieldsQueryPort {
    /** Profiles offered in the selector. */
    fun findAllActiveB2bProfiles(): List<B2bProfileDto>

    /** The customer's assigned profile, also when it has since been deactivated. */
    fun findB2bProfileById(id: Long): B2bProfileDto?

    /** The `RECIPIENT`-placed fields of [profileId]; empty for `null`. */
    fun findB2bRecipientFields(profileId: Long?): List<B2bProfileFieldDto>

    /** The customer's own stored values for [profileId]'s fields, keyed by `bt_code`. */
    fun findB2bRecipientFieldValues(recipientId: Long, profileId: Long): Map<String, String?>

    /** Replaces the customer's stored values for [profileId]'s fields. */
    fun saveB2bRecipientFieldValues(recipientId: Long, profileId: Long, values: Map<String, String?>)
}
