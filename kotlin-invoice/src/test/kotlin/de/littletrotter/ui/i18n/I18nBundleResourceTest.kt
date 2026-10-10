package de.littletrotter.ui.i18n

import de.littletrotter.application.settings.InMemorySystemSettingRepository
import de.littletrotter.application.settings.WordingSettingsGate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * Plain unit test of [I18nBundleResource] — the class is trivial by design (all merge/fallback
 * logic lives in [I18nBundleMerger], covered in [I18nBundleMergerTest]), so no `@QuarkusTest`
 * boot is needed to exercise it; this only pins that the resource actually delegates to the
 * merger and returns the same value shape a JSON serializer would emit. Term
 * substitution behaviour is covered separately (I18nBundleResourceWordingTest).
 */
class I18nBundleResourceTest {

    private val resource = I18nBundleResource(WordingSettingsGate(InMemorySystemSettingRepository(), "HANDWERK", time = FIXED_TIME_SOURCE))

    @Test
    fun `bundle for a known locale returns the resolved German value for a known key`() {
        assertEquals("Fehler", resource.bundle("de")["common.error"])
    }

    @Test
    fun `bundle for an unknown but valid locale falls back to the English default`() {
        assertEquals("Error", resource.bundle("fr")["common.error"])
    }

    @Test
    fun `bundle for an invalid locale pattern returns the English default only`() {
        assertEquals("Error", resource.bundle("de-DE")["common.error"])
    }
}
