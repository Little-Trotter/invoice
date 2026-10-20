package de.littletrotter.application.settings

import de.littletrotter.application.settings.port.inbound.SettingChangeInfo
import de.littletrotter.domain.settings.FeatureSetting
import de.littletrotter.domain.settings.SystemSetting
import de.littletrotter.fixture.editAudit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class SettingChangeInfoMapperTest {

    private val moment = OffsetDateTime.parse("2026-10-01T10:15:30+02:00")

    @Test
    fun `a feature setting maps timestamp and actor`() {
        val setting = FeatureSetting().editAudit { updatedAt = moment; updatedBy = "stefan" }

        assertEquals(SettingChangeInfo(moment, "stefan"), setting.toChangeInfo())
    }

    @Test
    fun `a system setting maps timestamp and actor`() {
        val setting = SystemSetting().editAudit { updatedAt = moment; updatedBy = "anna" }

        assertEquals(SettingChangeInfo(moment, "anna"), setting.toChangeInfo())
    }

    @Test
    fun `missing audit values stay null`() {
        assertEquals(SettingChangeInfo(null, null), FeatureSetting().toChangeInfo())
        assertEquals(SettingChangeInfo(moment, null), SystemSetting().editAudit { updatedAt = moment }.toChangeInfo())
        assertEquals(SettingChangeInfo(null, "x"), SystemSetting().editAudit { updatedBy = "x" }.toChangeInfo())
    }
}
