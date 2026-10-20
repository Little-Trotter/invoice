package de.littletrotter.ui.i18n

import de.littletrotter.application.settings.InMemorySystemSettingRepository
import de.littletrotter.application.settings.WordingSettingsGate
import de.littletrotter.shared.wording.WordingProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * [TranslationService] — the [com.vaadin.flow.i18n.I18NProvider] every Vaadin component resolves
 * `getTranslation` calls through. [UiTranslationsTest] already pins the wording-profile substitution
 * this class shares with [de.littletrotter.ui.i18n.UiTranslations]; this class covers the plain
 * [com.vaadin.flow.i18n.I18NProvider] contract itself: provided locales, an unknown key's
 * placeholder fallback, and `{0}`-style parameter formatting.
 */
class TranslationServiceTest {

    private fun service() = TranslationService(WordingSettingsGate(InMemorySystemSettingRepository(), WordingProfile.DEFAULT.name, time = FIXED_TIME_SOURCE))

    @Test
    fun `happy path - a known key resolves to its own text in both provided locales`() {
        val service = service()

        assertEquals("OK", service.getTranslation("app.ok", TranslationService.ENGLISH))
        assertTrue(service.getTranslation("app.ok", TranslationService.GERMAN).isNotBlank())
    }

    @Test
    fun `boundary - the provider offers exactly German then English, German first`() {
        val locales = service().getProvidedLocales()

        assertEquals(listOf(TranslationService.GERMAN, TranslationService.ENGLISH), locales)
    }

    @Test
    fun `boundary - an unknown key renders the placeholder instead of throwing, for either locale`() {
        val service = service()
        val key = "this.key.does.not.exist.anywhere.ts"

        assertEquals("!{$key}!", service.getTranslation(key, TranslationService.GERMAN))
        assertEquals("!{$key}!", service.getTranslation(key, TranslationService.ENGLISH))
    }

    @Test
    fun `boundary - a repeated lookup of the same missing key keeps returning the placeholder (log-once guard is idempotent)`() {
        val service = service()
        val key = "this.key.is.also.missing.ts"

        val first = service.getTranslation(key, TranslationService.GERMAN)
        val second = service.getTranslation(key, TranslationService.GERMAN)

        assertEquals("!{$key}!", first)
        assertEquals(first, second)
    }

    @Test
    fun `boundary - a message-format parameter is substituted into the resolved text`() {
        val service = service()

        val withParam = service.getTranslation("serviceCatalog.action.new", TranslationService.ENGLISH, "Widget")

        assertEquals("Add Widget", withParam)
    }
}
