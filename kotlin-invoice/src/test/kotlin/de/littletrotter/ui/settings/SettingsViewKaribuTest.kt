package de.littletrotter.ui.settings

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._setValue
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasText
import com.vaadin.flow.component.checkbox.Checkbox
import de.littletrotter.application.mail.MailAvailability
import de.littletrotter.application.settings.FeatureSettingsGate
import de.littletrotter.application.settings.InMemorySystemSettingRepository
import de.littletrotter.application.settings.WordingSettingsGate
import de.littletrotter.application.settings.featureGateWithDisabled
import de.littletrotter.application.shared.port.inbound.AuditActorQueryPort
import de.littletrotter.domain.settings.FeatureFlag
import de.littletrotter.fixture.editAudit
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.settings.SettingsView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * SFS — the Einstellungen view. Constructed directly (constructor-injected, like every panel in
 * this module's Karibu tests) against [featureGateWithDisabled]-style fakes; the real German copy
 * comes from the bundle (JVM default locale is pinned German by GermanDefaultLocaleExtension), so
 * assertions read like the user does — the same approach ValidationSettingsTab tests take.
 */
class SettingsViewKaribuTest {

    private class FakeActor(var actor: String = "stefan") : AuditActorQueryPort {
        override fun currentActor(): String = actor
    }

    @BeforeEach
    fun setup() {
        try {
            MockVaadin.setup()
        } catch (_: RuntimeException) {
            // Karibu's known "already set up" — same suppression idiom as ValidationSettingsTabTest.
        }
    }

    @AfterEach
    fun tearDown() {
        try {
            MockVaadin.tearDown()
        } catch (_: Exception) {
        }
    }

    private fun view(
        gate: FeatureSettingsGate,
        mailInstalled: Boolean = true,
        wordingGate: WordingSettingsGate = WordingSettingsGate(InMemorySystemSettingRepository(), "HANDWERK", time = FIXED_TIME_SOURCE),
    ): SettingsView =
        SettingsView(gate, MailAvailability(mailInstalled), FakeActor(), wordingGate, quoteCardsAbsent(), archiveHandoverCardsAbsent())

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    private fun byId(component: Component, id: String): Component? =
        flatten(component).firstOrNull { it.element.getAttribute("id") == id }

    private fun switchChip(view: Component, suffix: String): com.vaadin.flow.component.html.Span =
        byId(view, "settings-status-$suffix") as com.vaadin.flow.component.html.Span

    private fun metaOf(view: Component, suffix: String): com.vaadin.flow.component.html.Div =
        byId(view, "settings-meta-$suffix") as com.vaadin.flow.component.html.Div

    private fun switch(view: Component, suffix: String): Checkbox =
        byId(view, "settings-switch-$suffix") as Checkbox

    private fun text(view: Component, id: String): String? =
        (byId(view, id) as? HasText)?.text

    // ── Render ─────────────────────────────────────────────────────────────────

    @Test
    fun `both cards and switches render with the contractual ids`() {
        val view = view(featureGateWithDisabled())
        listOf("perdiem", "email", "extendedProfile", "receiptAutoReduce").forEach { suffix ->
            assertNotNull(byId(view, "settings-card-$suffix"), suffix)
            assertNotNull(byId(view, "settings-switch-$suffix"), suffix)
            assertNotNull(byId(view, "settings-status-$suffix"), suffix)
            assertNotNull(byId(view, "settings-meta-$suffix"), suffix)
        }
    }

    @Test
    fun `copy comes from the bundle, both languages of the flip appear`() {
        val view = view(featureGateWithDisabled(FeatureFlag.PER_DIEM_UI))
        val cards = flatten(view).filterIsInstance<HasText>().map { it.text }
        assertTrue(cards.any { it == UiTranslations.tr("settings.title") })
        assertTrue(cards.any { it == UiTranslations.tr("settings.flag.perdiem.label") })
        assertTrue(cards.any { it == UiTranslations.tr("settings.flag.email.label") })
        assertTrue(cards.any { it == UiTranslations.tr("settings.flag.extendedProfile.label") })
        assertEquals(
            UiTranslations.tr("settings.state.aktiv"),
            text(view, "settings-status-email"),
            "email row still on",
        )
        assertEquals(
            UiTranslations.tr("settings.state.inaktiv"),
            text(view, "settings-status-perdiem"),
        )
    }

    @Test
    fun `the email card is rendered only when the component is deployed`() {
        assertNull(byId(view(featureGateWithDisabled(), mailInstalled = false), "settings-card-email"))
        assertNotNull(byId(view(featureGateWithDisabled(), mailInstalled = true), "settings-card-email"))
    }

    @Test
    fun `meta line starts at not-yet-changed`() {
        val meta = byId(view(featureGateWithDisabled()), "settings-meta-perdiem")
        assertNotNull(meta)
        val texts = flatten(meta!!).filterIsInstance<HasText>().map { it.text }
        assertTrue(texts.any { it == UiTranslations.tr("settings.meta.nie") }, texts.toString())
    }

    // ── The flip ───────────────────────────────────────────────────────────────

    @Test
    fun `flipping the switch persists, updates chip and shows the last-change line`() {
        val gate = featureGateWithDisabled(FeatureFlag.PER_DIEM_UI)
        val view = view(gate)

        // Karibu-driven programmatic writes never set isFromClient — the extracted listener seam
        // is the unit-level equivalent exactly as ValidationSettingsTabTest documents.
        val toggle = switch(view, "perdiem")
        toggle.value = true // sets client-agnostic state; the guarded listener stays silent here.
        view.onSwitchChange(FeatureFlag.PER_DIEM_UI, true, SettingsView.CardWidgets(toggle, switchChip(view, "perdiem"), metaOf(view, "perdiem")))

        assertTrue(gate.isEnabled(FeatureFlag.PER_DIEM_UI), "der Klick geht bis ins Gate durch")
        assertEquals(UiTranslations.tr("settings.state.aktiv"), text(view, "settings-status-perdiem"))
        val changedBy = gate.lastChanged(FeatureFlag.PER_DIEM_UI)?.updatedBy
        assertEquals("stefan", changedBy, "Actor der persistence")
        val meta = flatten(byId(view, "settings-meta-perdiem")!!).filterIsInstance<HasText>().map { it.text }
        assertTrue(
            meta.any { it.startsWith(UiTranslations.tr("settings.meta.zuletzt").substringBefore("{0}")) },
            "Meta-Zeile nennt nach dem Flip Zeit und Bearbeiter: $meta",
        )
    }

    @Test
    fun `a failed persistence reverts the switch instead of lying about state`() {
        val repo = de.littletrotter.application.settings.InMemoryFeatureSettingRepository().apply { failWrites = true }
        val gate = FeatureSettingsGate(repo, time = FIXED_TIME_SOURCE)
        val view = view(gate)

        val toggle = switch(view, "email")
        val chip = switchChip(view, "email")
        val meta = metaOf(view, "email")
        assertTrue(toggle.value, "startet auf dem Default an")

        view.onSwitchChange(FeatureFlag.EMAIL_SEND, false, SettingsView.CardWidgets(toggle, chip, meta))

        assertTrue(toggle.value, "fehlgeschlagener Schreibversuch ⇒ Switch zurück auf den letzten guten Zustand")
        assertEquals(
            UiTranslations.tr("settings.state.aktiv"),
            text(view, "settings-status-email"),
            "der Chip lügt nicht",
        )
        val texts = flatten(meta).filterIsInstance<HasText>().map { it.text }.filter { it.isNotBlank() }
        assertEquals(UiTranslations.tr("settings.meta.nie"), texts.firstOrNull(), "Meta-Zeile unangetastet")
        assertEquals(UiTranslations.tr("settings.state.aktiv"), chip.text, "Chip bleibt auf dem guten Zustand")
        assertTrue(repo.rows.isEmpty(), "nichts wurde persistiert")
    }

    @Test
    fun `idSuffix is the pure contract behind the ids`() {
        assertEquals("perdiem", SettingsView.idSuffix(FeatureFlag.PER_DIEM_UI))
        assertEquals("email", SettingsView.idSuffix(FeatureFlag.EMAIL_SEND))
        assertEquals("extendedProfile", SettingsView.idSuffix(FeatureFlag.EXTENDED_PROFILE))
        assertEquals("receiptAutoReduce", SettingsView.idSuffix(FeatureFlag.RECEIPT_AUTO_REDUCE))
    }

    @Test
    fun `the receipt reduction card is on by default and shows label and help with the target size`() {
        val view = view(featureGateWithDisabled())
        val texts = flatten(view).filterIsInstance<HasText>().map { it.text }

        assertEquals(UiTranslations.tr("settings.state.aktiv"), text(view, "settings-status-receiptAutoReduce"))
        assertTrue(texts.any { it == UiTranslations.tr("settings.section.receipts") })
        assertTrue(texts.any { it == UiTranslations.tr("settings.flag.receiptAutoReduce.label") })
        val help = UiTranslations.tr(
            "settings.flag.receiptAutoReduce.description",
            de.littletrotter.ui.format.DocumentSizeFormatter.format(de.littletrotter.shared.receipt.ReceiptSizePolicy.TARGET_RECEIPT_SIZE),
        )
        assertTrue(texts.any { it == help }, texts.toString())
    }

    @Test
    fun `switching the receipt reduction off is persisted through the gate`() {
        val gate = featureGateWithDisabled()
        val view = view(gate)
        val toggle = switch(view, "receiptAutoReduce")
        toggle.value = false

        view.onSwitchChange(
            FeatureFlag.RECEIPT_AUTO_REDUCE, false,
            SettingsView.CardWidgets(toggle, switchChip(view, "receiptAutoReduce"), metaOf(view, "receiptAutoReduce")),
        )

        assertFalse(gate.isEnabled(FeatureFlag.RECEIPT_AUTO_REDUCE))
        assertEquals(UiTranslations.tr("settings.state.inaktiv"), text(view, "settings-status-receiptAutoReduce"))
    }

    // ── The guarded listener itself (`if (!event.isFromClient) return`), never exercised
    // by the direct-onSwitchChange tests above: `_setValue` (unlike a plain `.value =`) fires a
    // genuine client-originated ValueChangeEvent, so the listener's own pass-through to
    // onSwitchChange runs end to end instead of being called by the test in its place. ──────────

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
    fun `boundary - a failure with no exception message falls back to the exception class name in the toast`() {
        // check()/error() always require a message, so they cannot produce the null-message
        // exception this test needs; built as a value first (not `throw IllegalStateException()`
        // at the call site) so IllegalStateException stays the caught type without re-triggering
        // UseCheckOrError, which only matches a constructor call right at the throw site.
        val messageless: Throwable = IllegalStateException()
        val repo = object : de.littletrotter.domain.repository.FeatureSettingRepository {
            override fun findAll(): List<de.littletrotter.domain.settings.FeatureSetting> = emptyList()
            override fun findById(flag: FeatureFlag): de.littletrotter.domain.settings.FeatureSetting? = null
            override fun save(setting: de.littletrotter.domain.settings.FeatureSetting): de.littletrotter.domain.settings.FeatureSetting =
                throw messageless
        }
        val gate = FeatureSettingsGate(repo, time = FIXED_TIME_SOURCE)
        val view = view(gate)
        val toggle = switch(view, "perdiem")
        val chip = switchChip(view, "perdiem")
        val meta = metaOf(view, "perdiem")

        view.onSwitchChange(FeatureFlag.PER_DIEM_UI, true, SettingsView.CardWidgets(toggle, chip, meta))

        assertNotificationContains("IllegalStateException")
    }

    @Test
    fun `boundary - a missing editor name leaves the name part out of the last-change line`() {
        val repo = de.littletrotter.application.settings.InMemoryFeatureSettingRepository()
        repo.rows[FeatureFlag.PER_DIEM_UI.key] =
            de.littletrotter.domain.settings.FeatureSetting.of(FeatureFlag.PER_DIEM_UI, true).editAudit {
                updatedAt = java.time.OffsetDateTime.now()
                updatedBy = null
            }
        val gate = FeatureSettingsGate(repo, time = FIXED_TIME_SOURCE)
        val view = view(gate)

        val texts = flatten(metaOf(view, "perdiem")).filterIsInstance<HasText>().map { it.text }

        assertTrue(texts.none { it.contains("?") || it.contains(" von ") }, "updatedBy = null must leave the name out: $texts")
        assertTrue(texts.any { it.startsWith("Zuletzt geändert") }, "the date is still shown: $texts")
    }

    @Test
    fun `a genuine client toggle click runs the listener through to the gate`() {
        val gate = featureGateWithDisabled(FeatureFlag.PER_DIEM_UI)
        val view = view(gate)
        val toggle = switch(view, "perdiem")

        toggle._setValue(true)

        assertTrue(gate.isEnabled(FeatureFlag.PER_DIEM_UI), "der clientseitige Klick geht bis ins Gate durch")
        assertEquals(UiTranslations.tr("settings.state.aktiv"), text(view, "settings-status-perdiem"))
    }
}
