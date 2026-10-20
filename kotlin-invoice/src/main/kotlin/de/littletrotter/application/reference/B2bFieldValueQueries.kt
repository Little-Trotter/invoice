package de.littletrotter.application.reference

import de.littletrotter.application.dto.ResolvedB2bFieldValueDto
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.domain.reference.B2bFieldOwnerType
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Implements [B2bFieldValueQueryPort] on top of [B2bFieldValueService]. */
@ApplicationScoped
class B2bFieldValueQueries @Inject constructor(
    private val fieldValueService: B2bFieldValueService,
) : B2bFieldValueQueryPort {

    override fun findB2bFieldValues(ownerType: B2bFieldOwnerType, ownerId: Long): Map<String, String?> =
        fieldValueService.findFieldValues(ownerType, ownerId)

    override fun findB2bFieldValuesForOwners(ownerType: B2bFieldOwnerType, ownerIds: List<Long>): Map<Long, Map<String, String?>> =
        fieldValueService.findFieldValuesByOwners(ownerType, ownerIds)

    override fun findB2bRecipientFieldValues(recipientId: Long, profileId: Long): Map<String, String?> =
        fieldValueService.findRecipientFieldValues(recipientId, profileId)

    override fun findB2bProfileIdForInvoice(invoiceId: Long): Long? = fieldValueService.findProfileIdForInvoice(invoiceId)

    override fun findB2bGuidedFieldValues(invoiceId: Long): Map<String, ResolvedB2bFieldValueDto> =
        fieldValueService.resolvedGuidedFieldValues(invoiceId).mapValues { (_, resolved) -> resolved.toDto() }
}
