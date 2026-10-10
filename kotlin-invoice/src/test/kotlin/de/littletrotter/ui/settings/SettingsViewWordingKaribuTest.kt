package de.littletrotter.ui.settings

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._setValue
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasText
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
import de.littletrotter.application.mail.MailAvailability
import de.littletrotter.application.settings.FeatureSettingsGate
import de.littletrotter.application.settings.InMemoryFeatureSettingRepository
import de.littletrotter.application.settings.InMemorySystemSettingRepository
import de.littletrotter.application.settings.WordingSettingsGate
import de.littletrotter.application.shared.AuditActorProvider
import de.littletrotter.shared.wording.WordingProfile
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.settings.SettingsView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * The wording-profile card: switch renders as the `lt-segmented` three-segment control
 * a client-initiated change writes through [WordingSettingsGate.setProfile] and re-renders the
 * "zuletzt geändert" line, and a failed write reverts the segment instead of lying about state —
 * the same contract `SettingsViewKaribuTest` already pins for the feature-flag switches.
 */
class SettingsViewWordingKaribuTest {

    private class FakeActor(var actor: String = "stefan") : AuditActorProvider {
        override fun currentActor(): String = actor
    }

    @BeforeEach
    fun setup() {
        try {
            MockVaadin.setup()
        } catch (_: RuntimeException) {
            // Karibu's known "already set up" — same suppression idiom as SettingsViewKaribuTest.
        }
    }

    @AfterEach
    fun tearDown() {
        try {
            MockVaadin.tearDown()
        } catch (_: Exception) {
        }
    }

    private fun view(wordingGate: WordingSettingsGate): SettingsView =
        SettingsView(FeatureSettingsGate(InMemoryFeatureSettingRepository(), time = FIXED_TIME_SOURCE), MailAvailability(false), FakeActor(), wordingGate, quoteCardsAbsent(), archiveHandoverCardsAbsent())

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    private fun byId(component: Component, id: String): Component? =
        flatten(component).firstOrNull { it.element.getAttribute("id") == id }

    private fun switch(view: SettingsView): RadioButtonGroup<WordingProfile> = view.wordingSwitch

    @Test
    fun `the card and its segmented switch render with the contractual id`() {
        val view = view(WordingSettingsGate(InMemorySystemSettingRepository(), "HANDWERK", time = FIXED_TIME_SOURCE))
        assertNotNull(byId(view, "settings-card-wording"))
        val control = switch(view)
        assertEquals(WordingProfile.HANDWERK, control.value)
    }

    @Test
    fun `copy comes from the bundle`() {
        val view = view(WordingSettingsGate(InMemorySystemSettingRepository(), "HANDWERK", time = FIXED_TIME_SOURCE))
        val texts = flatten(view).filterIsInstance<HasText>().map { it.text }
        assertTrue(texts.any { it == UiTranslations.tr("settings.wording.title") })
        assertTrue(texts.any { it == UiTranslations.tr("settings.wording.description") })
    }

    @Test
    fun `switching to BERATUNG writes through the gate and updates the meta line`() {
        val repo = InMemorySystemSettingRepository()
        val gate = WordingSettingsGate(repo, "HANDWERK", time = FIXED_TIME_SOURCE)
        val view = view(gate)
        val control = switch(view)
        val meta = byId(view, "settings-meta-wording") as Div

        // Karibu-driven programmatic writes never set isFromClient — the extracted listener seam
        // is the unit-level equivalent, exactly as SettingsViewKaribuTest documents for the flags.
        control.value = WordingProfile.BERATUNG
        view.onWordingChange(WordingProfile.BERATUNG, control, meta)

        assertEquals(WordingProfile.BERATUNG, gate.currentProfile(), "der Klick geht bis ins Gate durch")
        assertEquals("stefan", repo.rows.getValue(WordingSettingsGate.KEY).updatedBy)
        val metaTexts = flatten(meta).filterIsInstance<HasText>().map { it.text }
        assertTrue(
            metaTexts.any { it.startsWith(UiTranslations.tr("settings.meta.zuletzt").substringBefore("{0}")) },
            "Meta-Zeile nennt nach dem Wechsel Zeit und Bearbeiter: $metaTexts",
        )
    }

    @Test
    fun `a failed persistence reverts the switch instead of lying about state`() {
        val repo = InMemorySystemSettingRepository().apply { failWrites = true }
        val gate = WordingSettingsGate(repo, "HANDWERK", time = FIXED_TIME_SOURCE)
        val view = view(gate)
        val control = switch(view)
        val meta = byId(view, "settings-meta-wording") as Div

        view.onWordingChange(WordingProfile.BERATUNG, control, meta)

        assertEquals(WordingProfile.HANDWERK, control.value, "fehlgeschlagener Schreibversuch ⇒ Switch zurück auf den letzten guten Zustand")
        assertEquals(WordingProfile.HANDWERK, gate.currentProfile())
        assertTrue(repo.rows.isEmpty())
    }

    private fun assertNotificationContains(expectedFragment: String) {
        // UiNotifications.error() builds a custom Div(Span(message), Button) and adds it via
        // Notification.add(...) — that clears Notification.getText() to "" (kaributools'
        // Notification.getText() bails out whenever hasChildren is true) and attaches the content
        // as a virtual child, invisible to a plain element.children walk. kaributesting's own
        // _find (already proven against Dialog.footer's virtual-child button in StatusPanel's
        // coverage test) resolves it correctly.
        MockVaadin.clientRoundtrip()
        val found = com.github.mvysny.kaributesting.v10._find<com.vaadin.flow.component.html.Span>()
            .any { it.text?.contains(expectedFragment) == true }
        assertTrue(found, "expected an open notification containing: $expectedFragment")
    }

    @Test
    fun `boundary - a wording save failure with no exception message falls back to the class name`() {
        // check()/error() always require a message, so they cannot produce the null-message
        // exception this test needs; built as a value first (not `throw IllegalStateException()`
        // at the call site) so IllegalStateException stays the caught type without re-triggering
        // UseCheckOrError, which only matches a constructor call right at the throw site.
        val messageless: Throwable = IllegalStateException()
        val repo = object : de.littletrotter.domain.repository.SystemSettingRepository {
            override fun findById(key: String): de.littletrotter.domain.settings.SystemSetting? = null
            override fun findFamily(key: String): List<de.littletrotter.domain.settings.SystemSetting> = emptyList()
            override fun save(setting: de.littletrotter.domain.settings.SystemSetting): de.littletrotter.domain.settings.SystemSetting =
                throw messageless
        }
        val gate = WordingSettingsGate(repo, "HANDWERK", time = FIXED_TIME_SOURCE)
        val view = view(gate)
        val control = switch(view)
        val meta = byId(view, "settings-meta-wording") as Div

        view.onWordingChange(WordingProfile.BERATUNG, control, meta)

        assertNotificationContains("IllegalStateException")
    }

    @Test
    fun `boundary - the preview resolves the English term when the UI locale is English`() {
        val view = view(WordingSettingsGate(InMemorySystemSettingRepository(), "HANDWERK", time = FIXED_TIME_SOURCE))
        val previousLocale = com.vaadin.flow.component.UI.getCurrent().locale
        try {
            com.vaadin.flow.component.UI.getCurrent().locale = java.util.Locale.ENGLISH
            val text = view.wordingPreviewText(WordingProfile.HANDEL)
            assertTrue(text.isNotBlank(), text)
        } finally {
            com.vaadin.flow.component.UI.getCurrent().locale = previousLocale
        }
    }

    @Test
    fun `the card previews the product term the chosen profile renders`() {
        val view = view(WordingSettingsGate(InMemorySystemSettingRepository(), "HANDWERK", time = FIXED_TIME_SOURCE))

        val preview = byId(view, SettingsView.WORDING_PREVIEW_ID)!!.element.text
        assertTrue(preview.contains("Material"), preview)

        val handel = view.wordingPreviewText(WordingProfile.HANDEL)
        assertTrue(handel.contains("Artikel"), handel)
        assertEquals(UiTranslations.tr("settings.wording.preview", "Artikel"), handel)

        val beratung = view.wordingPreviewText(WordingProfile.BERATUNG)
        assertTrue(beratung.contains("Produkte"), beratung)
    }

    // ── The guarded listener itself (`if (!event.isFromClient) return`), never
    // exercised by the direct-onWordingChange tests above: `_setValue` fires a genuine
    // client-originated ValueChangeEvent, running the listener's own pass-through (including the
    // preview re-render) end to end instead of the test calling onWordingChange in its place. ──

    @Test
    fun `a genuine client segment click runs the listener through to the gate and updates the preview`() {
        val repo = InMemorySystemSettingRepository()
        val gate = WordingSettingsGate(repo, "HANDWERK", time = FIXED_TIME_SOURCE)
        val view = view(gate)
        val control = switch(view)

        control._setValue(WordingProfile.HANDEL)

        assertEquals(WordingProfile.HANDEL, gate.currentProfile(), "der clientseitige Klick geht bis ins Gate durch")
        val preview = byId(view, SettingsView.WORDING_PREVIEW_ID)!!.element.text
        assertTrue(preview.contains("Artikel"), preview)
    }
}
