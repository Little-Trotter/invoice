package de.littletrotter.domain.repository

import de.littletrotter.domain.reference.ValidationCapabilityId
import de.littletrotter.domain.reference.ValidationSetting

/**
 * T9 — access to the switch states. Deliberately without `delete`: a check is disabled, not
 * deleted. A missing row means "enabled" (fail-open) and is therefore not a state anyone should
 * deliberately create.
 */
interface ValidationSettingRepository {
    fun findAll(): List<ValidationSetting>
    fun findById(capability: ValidationCapabilityId): ValidationSetting?
    fun save(setting: ValidationSetting): ValidationSetting
}
