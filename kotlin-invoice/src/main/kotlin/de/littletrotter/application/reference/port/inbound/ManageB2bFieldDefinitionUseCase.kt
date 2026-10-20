package de.littletrotter.application.reference.port.inbound

import de.littletrotter.application.command.CommandEntryPoint
import de.littletrotter.application.dto.B2bProfileFieldDto

/**
 * Input port for maintaining the fields of a B2B profile: saves one edited field (its
 * [B2bProfileFieldDto.profileId] names the owning profile), deletes one.
 */
interface ManageB2bFieldDefinitionUseCase : CommandEntryPoint {
    fun saveB2bProfileField(field: B2bProfileFieldDto): B2bProfileFieldDto

    fun deleteB2bProfileField(id: Long)
}
