package de.littletrotter.ui.i18n

import com.vaadin.flow.component.Component
import de.littletrotter.application.settings.WordingSettingsGate
import de.littletrotter.shared.wording.WordingProfile
import jakarta.enterprise.inject.Instance
import jakarta.enterprise.inject.spi.CDI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.Locale
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * [UiTranslations.trOrNull] — added so a caller that wants to distinguish "resolved" from
 * "unknown key" (e.g. [de.littletrotter.ui.view.invoice.buildValidationReportMarkdown] falling
 * back to a finding's raw text) doesn't have to parse [UiTranslations.tr]'s `"!{key}!"`
 * placeholder itself.
 */
class UiTranslationsTest {

    @Test
    fun `trOrNull resolves a known key with no component, same as tr`() {
        assertEquals("OK", UiTranslations.trOrNull(null, "app.ok"))
    }

    @Test
    fun `trOrNull returns null for an unknown key instead of the placeholder`() {
        assertNull(UiTranslations.trOrNull(null, "this.key.does.not.exist.anywhere"))
    }

    @Test
    fun `tr still returns the placeholder for the same unknown key`() {
        assertEquals("!{this.key.does.not.exist.anywhere}!", UiTranslations.tr("this.key.does.not.exist.anywhere"))
    }
    @Test
    fun `catalogue and invoice service masks share the profile category term in both locales`() {
        for (profile in de.littletrotter.shared.wording.WordingProfile.entries) {
            val service = TranslationService(de.littletrotter.application.settings.WordingSettingsGate(
                de.littletrotter.application.settings.InMemorySystemSettingRepository(), profile.name, time = FIXED_TIME_SOURCE,
            ))
            for (locale in listOf(java.util.Locale.GERMAN, java.util.Locale.ENGLISH)) {
                val expected = de.littletrotter.shared.wording.TermResolver.resolve(
                    profile, locale, de.littletrotter.shared.wording.Term.SERVICE_CATEGORY,
                )!!
                assertEquals(expected, service.getTranslation("serviceCatalog.category.service", locale))
                assertEquals(expected, service.getTranslation("item.category.service", locale))
                assertEquals(
                    if (locale == java.util.Locale.GERMAN) "$expected erfassen" else "Add $expected",
                    service.getTranslation("invoiceDetail.service.dialog.title", locale),
                )
                assertEquals(
                    if (locale == java.util.Locale.GERMAN) "$expected anlegen" else "Add $expected",
                    service.getTranslation("serviceCatalog.action.new", locale, expected),
                )
            }
        }
    }

    @Test
    fun `happy path - tr with a component that resolves the key uses the component's own translation`() {
        val component: Component = mock()
        whenever(component.getTranslation("app.ok")).thenReturn("Component-OK")

        assertEquals("Component-OK", UiTranslations.tr(component, "app.ok"))
    }

    @Test
    fun `boundary - tr with a component falls back when the component returns a blank translation`() {
        val component: Component = mock()
        whenever(component.getTranslation("app.ok")).thenReturn("")

        assertEquals(UiTranslations.tr("app.ok"), UiTranslations.tr(component, "app.ok"))
    }

    @Test
    fun `boundary - tr with a component falls back when the component returns the missing-key placeholder`() {
        val component: Component = mock()
        whenever(component.getTranslation("app.ok")).thenReturn("!{app.ok}!")

        assertEquals(UiTranslations.tr("app.ok"), UiTranslations.tr(component, "app.ok"))
    }

    @Test
    fun `boundary - trOrNullForLocale resolves a known key for an explicit locale without touching the JVM default`() {
        assertEquals("OK", UiTranslations.trOrNullForLocale(Locale.ENGLISH, "app.ok"))
        assertEquals("OK", UiTranslations.trOrNullForLocale(Locale.GERMAN, "app.ok"))
    }

    @Test
    fun `boundary - trOrNullForLocale returns null instead of the placeholder for an unknown key`() {
        assertNull(UiTranslations.trOrNullForLocale(Locale.ENGLISH, "this.key.does.not.exist.for.locale"))
    }

    @Test
    fun `boundary - tr substitutes a MessageFormat parameter into the resolved key text`() {
        assertEquals("Widget anlegen", UiTranslations.tr("serviceCatalog.action.new", "Widget"))
    }

    @Test
    fun `boundary - trOrNullForLocale resolves a locale with no dedicated bundle via the root (English) bundle`() {
        // messages.properties (the unsuffixed root) is a complete English translation, and Java's
        // candidate-locale search always includes the root as the final candidate even under
        // getNoFallbackControl (that control only disables falling further back to the JVM
        // default locale's own bundle) — so an unsupported locale like FRENCH still resolves.
        assertEquals("OK", UiTranslations.trOrNullForLocale(Locale.FRENCH, "app.ok"))
    }

    @Test
    fun `boundary - resolving a term-substituted key outside a CDI container falls back to the default wording profile`() {
        val resolved = UiTranslations.tr("serviceCatalog.category.service")

        assertTrue(resolved.isNotBlank())
        assertTrue(!resolved.startsWith("!"))
    }

    @Test
    fun `boundary - resolving a term-substituted key inside an active CDI container reads WordingSettingsGate's current profile`() {
        val gate = mock<WordingSettingsGate>()
        whenever(gate.currentProfile()).thenReturn(WordingProfile.DEFAULT)
        val instance = mock<Instance<WordingSettingsGate>>()
        whenever(instance.get()).thenReturn(gate)
        val cdi = mock<CDI<Any>>()
        whenever(cdi.select(WordingSettingsGate::class.java)).thenReturn(instance)

        Mockito.mockStatic(CDI::class.java).use { staticCdi ->
            staticCdi.`when`<CDI<Any>> { CDI.current() }.thenReturn(cdi)

            val resolved = UiTranslations.tr("serviceCatalog.category.service")

            assertTrue(resolved.isNotBlank())
            assertTrue(!resolved.startsWith("!"))
        }
    }

    @Test
    fun `number range catalogue wording is independent of industry in both locales`() {
        for (profile in de.littletrotter.shared.wording.WordingProfile.entries) {
            val service = TranslationService(de.littletrotter.application.settings.WordingSettingsGate(
                de.littletrotter.application.settings.InMemorySystemSettingRepository(), profile.name, time = FIXED_TIME_SOURCE,
            ))
            assertEquals("Leistungsverzeichnis", service.getTranslation("referenceData.numberRange.subject.CATALOGUE", java.util.Locale.GERMAN))
            assertEquals("Specifications", service.getTranslation("referenceData.numberRange.subject.CATALOGUE", java.util.Locale.ENGLISH))
            assertEquals("Positionskategorie", service.getTranslation("referenceData.numberRange.prefixDataField.CATALOGUE_CATEGORY", java.util.Locale.GERMAN))
            assertEquals("Position category", service.getTranslation("referenceData.numberRange.prefixDataField.CATALOGUE_CATEGORY", java.util.Locale.ENGLISH))
        }
    }

    @Test
    fun `boundary - a missing bundle base name loads as null with and without a control`() {
        assertNull(UiTranslations.loadBundleOrNull("de.littletrotter.does.not.exist", Locale.GERMAN))
        assertNull(
            UiTranslations.loadBundleOrNull(
                "de.littletrotter.does.not.exist",
                Locale.GERMAN,
                java.util.ResourceBundle.Control.getNoFallbackControl(java.util.ResourceBundle.Control.FORMAT_PROPERTIES),
            ),
        )
    }

    @Test
    fun `boundary - the placeholder bundle renders every key as its marker and lists no keys`() {
        val bundle = UiTranslations.placeholderBundle()
        assertEquals("!{any.key}!", bundle.getString("any.key"))
        assertTrue(!bundle.keys.hasMoreElements())
    }

    @Test
    fun `boundary - trOrNullForLocale treats a blank bundle value as unresolved instead of rendering it empty`() {
        // test-only i18n/messages_zz.properties declares this key with an empty value
        assertNull(UiTranslations.trOrNullForLocale(Locale.of("zz"), "uiTranslations.test.blankValue"))
    }

    @Test
    fun `boundary - a bundle missing from the classpath degrades to the placeholder bundle`() {
        val bundle = UiTranslations.bundleOrPlaceholder(null)

        assertEquals("!{app.ok}!", bundle.getString("app.ok"))
    }

    @Test
    fun `boundary - a loaded bundle is used as-is, never replaced by the placeholder`() {
        val loaded = UiTranslations.loadBundleOrNull("i18n.messages", Locale.GERMAN)!!

        assertTrue(UiTranslations.bundleOrPlaceholder(loaded) === loaded)
    }
}
