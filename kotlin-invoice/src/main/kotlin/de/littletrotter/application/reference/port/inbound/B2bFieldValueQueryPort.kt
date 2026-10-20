package de.littletrotter.application.reference.port.inbound

import de.littletrotter.application.dto.ResolvedB2bFieldValueDto
import de.littletrotter.domain.reference.B2bFieldOwnerType

/**
 * Input port for reading stored B2B field values of an owner (seller, invoice, invoice item,
 * customer), keyed by `bt_code`.
 */
interface B2bFieldValueQueryPort {
    fun findB2bFieldValues(ownerType: B2bFieldOwnerType, ownerId: Long): Map<String, String?>

    /** Batched sibling of [findB2bFieldValues]: one query for a whole page of owners. */
    fun findB2bFieldValuesForOwners(ownerType: B2bFieldOwnerType, ownerIds: List<Long>): Map<Long, Map<String, String?>>

    /** The customer's stored values for [profileId]'s fields. */
    fun findB2bRecipientFieldValues(recipientId: Long, profileId: Long): Map<String, String?>

    /** Resolves [invoiceId]'s own B2B profile id server-side. */
    fun findB2bProfileIdForInvoice(invoiceId: Long): Long?

    /** The single resolved value (invoice override > party master data > profile default) of
     *  every `FREE` field the guided entry renders for [invoiceId]'s own profile, with the tier it
     *  came from. Empty when the invoice carries no B2B profile. */
    fun findB2bGuidedFieldValues(invoiceId: Long): Map<String, ResolvedB2bFieldValueDto>
}
