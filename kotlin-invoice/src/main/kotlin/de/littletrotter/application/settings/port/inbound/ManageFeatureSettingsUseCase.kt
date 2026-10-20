package de.littletrotter.application.settings.port.inbound

import de.littletrotter.domain.settings.FeatureFlag
import de.littletrotter.application.command.CommandEntryPoint

/** Input port of the settings screen: reads the feature flags and switches them. */
interface ManageFeatureSettingsUseCase : CommandEntryPoint {
    /** Whether [flag] is switched on. */
    fun isEnabled(flag: FeatureFlag): Boolean

    /** Who changed [flag] last and when; `null` when it was never changed. */
    fun lastChanged(flag: FeatureFlag): SettingChangeInfo?

    /** Switches [flag] on or off, recording [actor] as the author of the change. */
    fun setEnabled(flag: FeatureFlag, enabled: Boolean, actor: String)
}
