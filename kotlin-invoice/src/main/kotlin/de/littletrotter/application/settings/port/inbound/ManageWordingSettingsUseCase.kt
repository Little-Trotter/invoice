package de.littletrotter.application.settings.port.inbound

import de.littletrotter.shared.wording.WordingProfile
import de.littletrotter.application.command.CommandEntryPoint

/** Input port of the settings screen: reads the wording profile and switches it. */
interface ManageWordingSettingsUseCase : CommandEntryPoint {
    /** The wording profile currently in effect. */
    fun currentProfile(): WordingProfile

    /** Who changed the profile last and when; `null` when it was never changed. */
    fun lastChanged(): SettingChangeInfo?

    /** Switches the wording profile, recording [actor] as the author of the change. */
    fun setProfile(profile: WordingProfile, actor: String)
}
