package de.littletrotter.application.settings

import de.littletrotter.domain.repository.SystemSettingRepository
import de.littletrotter.domain.settings.SystemSetting

/**
 * In-memory system-setting store for tests, mirroring `InMemoryFeatureSettingRepository`
 * next door.
 */
class InMemorySystemSettingRepository(
    initial: Map<String, String> = emptyMap(),
) : SystemSettingRepository {

    val rows = linkedMapOf<String, SystemSetting>().apply {
        initial.forEach { (key, value) -> put(key, SystemSetting.of(key, value)) }
    }

    var failReads = false
    var failWrites = false

    override fun findById(key: String): SystemSetting? {
        check(!failReads) { "system_setting unreadable" }
        return rows[key]
    }

    override fun findFamily(key: String): List<SystemSetting> {
        check(!failReads) { "system_setting unreadable" }
        return rows.values.filter { it.key == key || it.key.startsWith("$key.") }.sortedBy { it.key }
    }

    override fun save(setting: SystemSetting): SystemSetting {
        check(!failWrites) { "system_setting unwritable" }
        // Emulate the JPA @PrePersist/@PreUpdate hooks from AuditableEntity, same as
        // InMemoryFeatureSettingRepository.save.
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
