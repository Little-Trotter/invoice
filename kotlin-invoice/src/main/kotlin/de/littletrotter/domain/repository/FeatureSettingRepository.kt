package de.littletrotter.domain.repository

import de.littletrotter.domain.settings.FeatureFlag
import de.littletrotter.domain.settings.FeatureSetting

/**
 * Repository for feature settings.
 *
 * No delete: a feature is disabled, not deleted.
 * Missing row means enabled (fail-open).
 */
interface FeatureSettingRepository {
    fun findAll(): List<FeatureSetting>
    fun findById(flag: FeatureFlag): FeatureSetting?
    fun save(setting: FeatureSetting): FeatureSetting
}
