package de.littletrotter.application.reference.port.inbound

import de.littletrotter.domain.reference.ValidationCapabilityId
import de.littletrotter.application.command.CommandEntryPoint

/** Input port of the reference-data screen: reads the validation switches and changes them. */
interface ManageValidationSettingsUseCase : CommandEntryPoint {
    /** Whether [capability] is switched on. */
    fun isEnabled(capability: ValidationCapabilityId): Boolean

    /** Every capability with its switch state. */
    fun allSettings(): Map<ValidationCapabilityId, Boolean>

    /** Switches [capability] on or off. */
    fun setEnabled(capability: ValidationCapabilityId, enabled: Boolean)
}
