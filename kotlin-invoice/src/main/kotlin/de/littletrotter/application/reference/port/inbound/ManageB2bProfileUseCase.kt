package de.littletrotter.application.reference.port.inbound

import de.littletrotter.application.command.CommandEntryPoint
import de.littletrotter.application.dto.B2bProfileDto

/**
 * Input port for maintaining B2B profiles: saves one edited profile row, deletes one, copies one.
 */
interface ManageB2bProfileUseCase : CommandEntryPoint {
    fun saveB2bProfile(profile: B2bProfileDto): B2bProfileDto

    fun deleteB2bProfile(id: Long)

    /** Deep-copies [profileId] (all fields, country scope) into a new, independently editable
     *  profile with a de-duplicated name. */
    fun copyB2bProfile(profileId: Long): B2bProfileDto
}
