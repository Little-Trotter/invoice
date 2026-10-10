package de.littletrotter.domain.repository

import de.littletrotter.domain.settings.SystemSetting

/**
 * Repository for [SystemSetting] rows. No delete: a setting is overwritten, not removed.
 */
interface SystemSettingRepository {
    fun findById(key: String): SystemSetting?

    /** The row [key] plus every row whose key extends it as `<key>.<suffix>`, ordered by key — for
     *  a setting that may hold more values than one 255-character row fits. */
    fun findFamily(key: String): List<SystemSetting>
    fun save(setting: SystemSetting): SystemSetting
}
