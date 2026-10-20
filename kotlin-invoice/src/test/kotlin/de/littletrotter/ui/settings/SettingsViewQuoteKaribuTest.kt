package de.littletrotter.ui.settings

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasText
import de.littletrotter.application.mail.MailAvailability
import de.littletrotter.ui.view.settings.ArchiveHandoverSettingsCardFactory
import de.littletrotter.application.quote.QuoteAvailability
import de.littletrotter.application.quote.port.inbound.QuoteSettings
import de.littletrotter.application.quote.port.inbound.QuoteSettingsQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteXmlFormat
import de.littletrotter.application.quote.port.inbound.UpdateQuoteSettingsUseCase
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.port.inbound.AppRoleQueryPort
import de.littletrotter.application.settings.FeatureSettingsGate
import de.littletrotter.application.settings.InMemorySystemSettingRepository
import de.littletrotter.application.settings.WordingSettingsGate
import de.littletrotter.application.settings.featureGateWithDisabled
import de.littletrotter.application.shared.port.inbound.AuditActorQueryPort
import de.littletrotter.domain.settings.FeatureFlag
import de.littletrotter.test.FIXED_TIME_SOURCE
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.settings.QuoteSettingsCard
import de.littletrotter.ui.view.settings.QuoteSettingsCardFactory
import de.littletrotter.ui.view.quote.notificationTexts
import de.littletrotter.ui.view.settings.SettingsView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

/** A factory for a deployment without the quote component: the settings page renders no quote card. */
internal fun quoteCardsAbsent(): QuoteSettingsCardFactory =
    QuoteSettingsCardFactory(QuoteAvailability(false), mock(), mock(), mock(), featureGateWithDisabled(), mock())

/** A factory for a deployment without the mail component: the settings page renders no archive card. */
internal fun archiveHandoverCardsAbsent(): ArchiveHandoverSettingsCardFactory =
    ArchiveHandoverSettingsCardFactory(MailAvailability(false), mock(), mock(), mock())

class SettingsViewQuoteKaribuTest {

    private val query: QuoteSettingsQueryPort = mock()
    private val update: UpdateQuoteSettingsUseCase = mock()
    private val roles: AppRoleQueryPort = mock()

    @BeforeEach
    fun setup() {
        try {
            MockVaadin.setup()
        } catch (_: RuntimeException) {
            // Karibu's known "already set up".
        }
        whenever(query.settings()).thenReturn(QuoteSettings(30, QuoteXmlFormat.UBL))
        whenever(roles.hasPermission(any())).thenAnswer { it.getArgument<AppPermission>(0) == AppPermission.QUOTE_SETTINGS_EDIT }
    }

    @AfterEach
    fun tearDown() {
        try {
            MockVaadin.tearDown()
        } catch (_: Exception) {
        }
    }

    private val gate = featureGateWithDisabled()

    private val actor = object : AuditActorQueryPort {
        override fun currentActor(): String = "stefan"
    }

    private fun view(enabled: Boolean = true, quoteGate: FeatureSettingsGate = gate): SettingsView = SettingsView(
        featureGateWithDisabled(),
        MailAvailability(false),
        object : AuditActorQueryPort {
            override fun currentActor(): String = "stefan"
        },
        WordingSettingsGate(InMemorySystemSettingRepository(), "HANDWERK", time = FIXED_TIME_SOURCE),
        QuoteSettingsCardFactory(QuoteAvailability(enabled), query, update, roles, quoteGate, actor),
        archiveHandoverCardsAbsent(),
    )

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    private fun card(view: Component): QuoteSettingsCard? = flatten(view).filterIsInstance<QuoteSettingsCard>().firstOrNull()

    private fun texts(component: Component): List<String> = flatten(component).filterIsInstance<HasText>().map { it.text }

    @Test
    fun `the card and its section render only when the component is deployed`() {
        val on = view(true)
        assertNotNull(card(on))
        assertEquals("settings-card-quote", card(on)!!.id.orElse(""))
        assertTrue(texts(on).contains(UiTranslations.tr("settings.section.angebot")))
        assertTrue(texts(on).contains(UiTranslations.tr("settings.state.aktiv")))

        val off = view(false)
        assertNull(card(off))
        assertFalse(texts(off).contains(UiTranslations.tr("settings.section.angebot")))
    }

    @Test
    fun `the fields show the current settings`() {
        val card = card(view())!!
        assertEquals(30, card.validityDays.value)
        assertEquals(QuoteXmlFormat.UBL, card.xmlFormat.value)
        assertTrue(card.validityDays.isEnabled)
        assertTrue(card.xmlFormat.isEnabled)
    }

    @Test
    fun `a change of the validity writes both values without a toast`() {
        val card = card(view())!!

        card.onValidityChange(45)

        verify(update).update(45L, QuoteXmlFormat.UBL)
        assertTrue(notificationTexts().isEmpty(), notificationTexts().toString())
    }

    @Test
    fun `a change of the format writes the stored validity with the new format`() {
        val card = card(view())!!

        card.onFormatChange(QuoteXmlFormat.NONE)

        verify(update).update(30L, QuoteXmlFormat.NONE)
    }

    @Test
    fun `a failing write reverts the field and reports the failure`() {
        whenever(update.update(any(), any())).thenThrow(IllegalStateException("down"))
        val card = card(view())!!

        card.validityDays.value = 45
        card.onValidityChange(45)
        card.xmlFormat.value = QuoteXmlFormat.NONE
        card.onFormatChange(QuoteXmlFormat.NONE)

        assertEquals(30, card.validityDays.value)
        assertEquals(QuoteXmlFormat.UBL, card.xmlFormat.value)
        assertTrue(notificationTexts().any { it.startsWith(UiTranslations.tr("settings.saveFailed")) }, notificationTexts().toString())
    }

    @Test
    fun `an invalid validity is not written and is reverted`() {
        val card = card(view())!!

        listOf(null, 0, 366).forEach { value ->
            card.validityDays.value = value
            card.onValidityChange(value)
            assertEquals(30, card.validityDays.value)
        }
        card.onFormatChange(null)

        verify(update, never()).update(any(), any())
    }

    @Test
    fun `the last change is shown, or never when unknown`() {
        assertTrue(texts(view()).contains(UiTranslations.tr("settings.meta.nie")))

        whenever(query.settings()).thenReturn(QuoteSettings(30, QuoteXmlFormat.UBL, Instant.parse("2026-10-01T08:15:00Z")))
        val shown = texts(card(view())!!)
        assertTrue(shown.any { it.contains("01.10.2026 10:15") }, shown.toString())
    }

    @Test
    fun `without the permission the fields are read-only`() {
        whenever(roles.hasPermission(any())).thenReturn(false)

        val card = card(view())!!

        assertFalse(card.validityDays.isEnabled)
        assertFalse(card.xmlFormat.isEnabled)
    }

    @Test
    fun `an unreachable component leaves the defaults in place`() {
        whenever(query.settings()).thenThrow(IllegalStateException("down"))

        assertEquals(30, card(view())!!.validityDays.value)
    }

    @Test
    fun `the switch is on with the chip active by default and the card stays when the flag is off`() {
        val on = card(view())!!
        assertTrue(on.toggle.value)
        assertEquals(UiTranslations.tr("settings.state.aktiv"), on.chip.text)

        val off = card(view(quoteGate = featureGateWithDisabled(FeatureFlag.QUOTE)))!!
        assertFalse(off.toggle.value)
        assertEquals(UiTranslations.tr("settings.state.inaktiv"), off.chip.text)
        assertTrue(off.validityDays.isEnabled)
    }

    @Test
    fun `switching off persists the flag, updates the chip without a toast`() {
        val card = card(view())!!

        card.onSwitchChange(false)

        assertFalse(gate.isEnabled(FeatureFlag.QUOTE))
        assertEquals(UiTranslations.tr("settings.state.inaktiv"), card.chip.text)
        assertTrue(notificationTexts().isEmpty(), notificationTexts().toString())

        card.onSwitchChange(true)

        assertTrue(gate.isEnabled(FeatureFlag.QUOTE))
        assertEquals(UiTranslations.tr("settings.state.aktiv"), card.chip.text)
    }

    @Test
    fun `a failing switch write reverts the toggle and reports the failure`() {
        val failing = mock<FeatureSettingsGate>()
        whenever(failing.setEnabled(any(), any(), any())).thenThrow(IllegalStateException("down"))
        whenever(failing.isEnabled(FeatureFlag.QUOTE)).thenReturn(true)
        val card = card(view(quoteGate = failing))!!

        card.toggle.value = false
        card.onSwitchChange(false)

        assertTrue(card.toggle.value)
        assertTrue(notificationTexts().any { it.startsWith(UiTranslations.tr("settings.saveFailed")) }, notificationTexts().toString())
    }
}
