package de.littletrotter.application.settings.port.inbound

import de.littletrotter.domain.settings.FeatureFlag

/** Input port for reading the administrator-switched feature flags. */
interface FeatureSettingsQueryPort {
    /** Whether [flag] is switched on. */
    fun isEnabled(flag: FeatureFlag): Boolean

    /** Who changed [flag] last and when; `null` when it was never changed. */
    fun lastChanged(flag: FeatureFlag): SettingChangeInfo?
}
