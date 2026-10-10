package de.littletrotter.application.invoice.port.inbound

import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.domain.reference.B2bFieldOwnerType

/**
 * input port of the "update an invoice's B2B header/item field values" use
 * case: [de.littletrotter.application.invoice.InvoiceHeaderUpdateService] reads a profile's field
 * definitions (to know which free-text terms may merge into the header) and saves an owner's field
 * values, without taking the two underlying reference-data services
 * ([de.littletrotter.application.reference.B2bProfileFieldDefinitionService]/
 * [de.littletrotter.application.reference.B2bFieldValueService]) as its own collaborators — that
 * is what pushed [de.littletrotter.application.invoice.InvoiceLifecycleSupport]'s constructor over
 * detekt's `constructorThreshold=8`. Not marked as a
 * [de.littletrotter.application.command.CommandEntryPoint]: this port is an application-internal
 * collaborator (never called from the UI directly); like every input port it still returns
 * value types only, so [findFields] hands out [B2bProfileFieldDto] rows.
 */
interface InvoiceB2bFieldsQueryPort {
    /** The field definitions of the B2B profile [profileId] — see
     *  [de.littletrotter.application.reference.B2bProfileFieldDefinitionService.findFields]. */
    fun findFields(profileId: Long): List<B2bProfileFieldDto>

    /** Persists [values] for the given owner — see
     *  [de.littletrotter.application.reference.B2bFieldValueService.saveFieldValues]. */
    fun saveFieldValues(ownerType: B2bFieldOwnerType, ownerId: Long, values: Map<String, String?>)
}
