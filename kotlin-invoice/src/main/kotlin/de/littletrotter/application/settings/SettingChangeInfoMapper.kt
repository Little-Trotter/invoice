package de.littletrotter.application.settings

import de.littletrotter.application.settings.port.inbound.SettingChangeInfo
import de.littletrotter.domain.shared.AuditableEntity

internal fun AuditableEntity.toChangeInfo(): SettingChangeInfo = SettingChangeInfo(updatedAt, updatedBy)
