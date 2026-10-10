package de.littletrotter.application.reference

import de.littletrotter.domain.reference.ValidationCapabilityId
import de.littletrotter.domain.reference.ValidationSetting
import de.littletrotter.domain.repository.ValidationSettingRepository
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * In-memory switch states for tests. Without this fixture, every view test would need a mock
 * plus stubbing for a switch it does not even care about.
 */
class InMemoryValidationSettingRepository(
    initial: Map<ValidationCapabilityId, Boolean> = emptyMap(),
) : ValidationSettingRepository {

    private val rows = linkedMapOf<String, ValidationSetting>().apply {
        initial.forEach { (capability, enabled) -> put(capability.name, ValidationSetting.of(capability, enabled)) }
    }

    override fun findAll(): List<ValidationSetting> = rows.values.toList()

    override fun findById(capability: ValidationCapabilityId): ValidationSetting? = rows[capability.name]

    override fun save(setting: ValidationSetting): ValidationSetting =
        setting.also { rows[it.capabilityId] = it }
}

/**
 * A gate where everything is enabled except the named checks. The default matches fail-open
 * behavior, so a test that does not care about the switch never notices it.
 */
internal fun validationSettingsGate(vararg disabled: ValidationCapabilityId): ValidationSettingsGate =
    ValidationSettingsGate(
        InMemoryValidationSettingRepository(
            ValidationCapabilityId.entries.associateWith { it !in disabled },
        ), time = FIXED_TIME_SOURCE,
    )
