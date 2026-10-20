package de.littletrotter.application.settings.port.inbound

import java.time.OffsetDateTime

/** When and by whom a setting was last changed, for the "zuletzt geändert" line of the settings screen. */
data class SettingChangeInfo(
    val updatedAt: OffsetDateTime?,
    val updatedBy: String?,
)
