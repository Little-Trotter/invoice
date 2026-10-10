package de.littletrotter.application.settings

import de.littletrotter.domain.repository.FeatureSettingRepository
import de.littletrotter.domain.settings.FeatureFlag
import de.littletrotter.domain.settings.FeatureSetting
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * SFS — in-memory feature-state for tests, mirroring `InMemoryValidationSettingRepository` next
 * door: without this fixture every view/service test that merely wants a specific flag state
 * would need its own mock and stubbing.
 */
class InMemoryFeatureSettingRepository(
    initial: Map<FeatureFlag, Boolean> = emptyMap(),
) : FeatureSettingRepository {

    val rows = linkedMapOf<String, FeatureSetting>().apply {
        initial.forEach { (flag, enabled) -> put(flag.key, FeatureSetting.of(flag, enabled)) }
    }

    var failReads = false
    var failWrites = false

    override fun findAll(): List<FeatureSetting> {
        check(!failReads) { "feature_setting unreadable" }
        return rows.values.toList()
    }

    override fun findById(flag: FeatureFlag): FeatureSetting? {
        check(!failReads) { "feature_setting unreadable" }
        return rows[flag.key]
    }

    override fun save(setting: FeatureSetting): FeatureSetting {
        check(!failWrites) { "feature_setting unwritable" }
        // Emulate the JPA @PrePersist/@PreUpdate hooks from AuditableEntity — the production
        // repository would get them from the persistence provider; the in-memory rows need the
        // same stamps so lastChanged-dependent view code sees realistic rows.
        val now = java.time.OffsetDateTime.now()
        if (setting.createdAt == null) {
            setting.createdAt = now
            setting.updatedAt = now
            if (setting.createdBy.isNullOrBlank()) setting.createdBy = setting.updatedBy ?: "system"
            setting.updatedBy = setting.createdBy
        } else {
            setting.updatedAt = now
        }
        return setting.also { rows[it.key] = it }
    }
}

/** A gate (fresh/empty cache) with the named flags stored as `false`; every other flag reads its
 *  catalog default through the real fail-open logic. */
fun featureGateWithDisabled(vararg disabled: FeatureFlag): FeatureSettingsGate =
    FeatureSettingsGate(InMemoryFeatureSettingRepository(disabled.toSet().associateWith { false }), time = FIXED_TIME_SOURCE)
