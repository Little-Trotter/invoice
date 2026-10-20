package de.littletrotter.application.reference.port.inbound

import de.littletrotter.domain.reference.ValidationCapabilityId

/** Input port for asking whether a validation capability is switched on. */
fun interface ValidationSettingsQueryPort {
    /** Whether [capability] is switched on. */
    fun isEnabled(capability: ValidationCapabilityId): Boolean
}
