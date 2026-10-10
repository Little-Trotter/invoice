package de.littletrotter.application.reference

import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.application.dto.B2bSellerFieldGroupDto
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.shared.b2b.B2bTerm
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Implements [B2bFieldDefinitionQueryPort] on top of [B2bProfileFieldDefinitionService]. */
@ApplicationScoped
class B2bFieldDefinitionQueries @Inject constructor(
    private val fieldDefinitionService: B2bProfileFieldDefinitionService,
) : B2bFieldDefinitionQueryPort {

    override fun findB2bProfileFields(profileId: Long): List<B2bProfileFieldDto> =
        fieldDefinitionService.findFields(profileId).map { it.toDto() }

    override fun findSelectableB2bCatalogEntries(profileId: Long?, currentFieldId: Long?): List<B2bTerm> =
        fieldDefinitionService.findSelectableCatalogEntries(profileId, currentFieldId)

    override fun findB2bSellerFields(profileId: Long): List<B2bProfileFieldDto> =
        fieldDefinitionService.findSellerFields(profileId).map { it.toDto() }

    override fun findB2bSellerFieldsUnion(): List<B2bSellerFieldGroupDto> =
        fieldDefinitionService.findSellerFieldsUnion().map { it.toDto() }

    override fun findB2bInvoiceHeaderFields(profileId: Long?): List<B2bProfileFieldDto> =
        if (profileId == null) emptyList() else fieldDefinitionService.findInvoiceHeaderFields(profileId).map { it.toDto() }

    override fun findB2bInvoiceItemFields(profileId: Long?): List<B2bProfileFieldDto> =
        if (profileId == null) emptyList() else fieldDefinitionService.findInvoiceItemFields(profileId).map { it.toDto() }

    override fun findB2bRecipientFields(profileId: Long?): List<B2bProfileFieldDto> =
        if (profileId == null) emptyList() else fieldDefinitionService.findRecipientFields(profileId).map { it.toDto() }
}
