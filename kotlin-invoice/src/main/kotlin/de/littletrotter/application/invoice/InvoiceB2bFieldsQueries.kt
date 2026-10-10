package de.littletrotter.application.invoice

import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.application.invoice.port.inbound.InvoiceB2bFieldsQueryPort
import de.littletrotter.application.reference.B2bFieldValueService
import de.littletrotter.application.reference.B2bProfileFieldDefinitionService
import de.littletrotter.application.reference.toDto
import de.littletrotter.domain.reference.B2bFieldOwnerType
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Implements [InvoiceB2bFieldsQueryPort] on top of
 *  [B2bProfileFieldDefinitionService]'s and [B2bFieldValueService]'s existing reads/writes. */
@ApplicationScoped
class InvoiceB2bFieldsQueries @Inject constructor(
    private val b2bFieldValueService: B2bFieldValueService,
    private val b2bProfileFieldDefinitionService: B2bProfileFieldDefinitionService,
) : InvoiceB2bFieldsQueryPort {

    override fun findFields(profileId: Long): List<B2bProfileFieldDto> =
        b2bProfileFieldDefinitionService.findFields(profileId).map { it.toDto() }

    override fun saveFieldValues(ownerType: B2bFieldOwnerType, ownerId: Long, values: Map<String, String?>) =
        b2bFieldValueService.saveFieldValues(ownerType, ownerId, values)
}
