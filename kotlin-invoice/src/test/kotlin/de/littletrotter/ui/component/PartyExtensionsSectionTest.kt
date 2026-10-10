package de.littletrotter.ui.component

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.masterdata.FacturXPartyFormExtension
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.spi.party.PartyFormExtension
import de.littletrotter.spi.party.PartyIdentifierSpec
import de.littletrotter.spi.party.PartyKind
import de.littletrotter.spi.party.PartyPairState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * T8-02: [PartyIdentifierSpec.hasScheme] controls whether [PartyExtensionsSection] renders a
 * second schema field. `false` (SIRET/NAF with no schema concept) shows only a value field; the
 * existing `true` default (electronic address) remains dual-field.
 */
class PartyExtensionsSectionTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun stubExtension(spec: PartyIdentifierSpec): PartyFormExtension = object : PartyFormExtension {
        override val id = "stub"
        override fun pairs(kind: PartyKind) = listOf(spec)
        override fun read(party: Any, kind: PartyKind, spec: PartyIdentifierSpec) = PartyPairState()
        override fun write(party: Any, kind: PartyKind, spec: PartyIdentifierSpec, state: PartyPairState) {}
    }

    private fun textFieldsOf(section: PartyExtensionsSection): List<TextField> {
        val found = mutableListOf<TextField>()
        fun walk(component: Component) {
            if (component is TextField) found += component
            component.children.forEach { walk(it) }
        }
        section.children.forEach { walk(it) }
        return found
    }

    private fun textFieldCount(section: PartyExtensionsSection): Int = textFieldsOf(section).size

    @Test
    fun `task12-b2b T8-02 - hasScheme false renders only the value field`() {
        val spec = PartyIdentifierSpec(id = "siret", i18nKeyPrefix = "recipient.facturX.siret", hasScheme = false)
        val section = PartyExtensionsSection(PartyKind.BUYER, listOf(spec to stubExtension(spec)), { it })

        assertEquals(1, textFieldCount(section))
        assertTrue(section.hasContent)
    }

    @Test
    fun `hasScheme true (default) still renders value and scheme fields, unchanged from before T8`() {
        val spec = PartyIdentifierSpec(id = "electronicAddress", i18nKeyPrefix = "recipient.electronicAddress")
        val section = PartyExtensionsSection(PartyKind.BUYER, listOf(spec to stubExtension(spec)), { it })

        assertEquals(2, textFieldCount(section))
    }

    @Test
    fun `nafCode field has maxLength of 5 to match VARCHAR(5) column`() {
        val section = PartyExtensionsSection(PartyKind.BUYER, listOf(FacturXPartyFormExtension.NAF_SPEC to realFacturXExtension), { it })

        val nafCodeField = textFieldsOf(section)[0]
        assertEquals(5, nafCodeField.maxLength)
    }

    @Test
    fun `hasContent is false when there are no pairs at all`() {
        val section = PartyExtensionsSection(PartyKind.BUYER, emptyList(), { it })

        assertFalse(section.hasContent)
    }

    // ── readFrom/writeTo on a hasScheme=true pair also populate/read the scheme field ──────────

    @Test
    fun `readFrom on a hasScheme pair loads both the value and the scheme field`() {
        val spec = PartyIdentifierSpec(id = "electronicAddress", i18nKeyPrefix = "recipient.electronicAddress")
        val extension = object : PartyFormExtension {
            override val id = "stub"
            override fun pairs(kind: PartyKind) = listOf(spec)
            override fun read(party: Any, kind: PartyKind, spec: PartyIdentifierSpec) = PartyPairState("EM:buyer@example.com", "EM")
            override fun write(party: Any, kind: PartyKind, spec: PartyIdentifierSpec, state: PartyPairState) {}
        }
        val section = PartyExtensionsSection(PartyKind.BUYER, listOf(spec to extension), { it })

        section.readFrom(Any())

        val fields = textFieldsOf(section)
        assertEquals("EM:buyer@example.com", fields[0].value)
        assertEquals("EM", fields[1].value)
    }

    @Test
    fun `writeTo on a hasScheme pair sends both the value and the scheme field`() {
        val spec = PartyIdentifierSpec(id = "electronicAddress", i18nKeyPrefix = "recipient.electronicAddress")
        var written: PartyPairState? = null
        val extension = object : PartyFormExtension {
            override val id = "stub"
            override fun pairs(kind: PartyKind) = listOf(spec)
            override fun read(party: Any, kind: PartyKind, spec: PartyIdentifierSpec) = PartyPairState()
            override fun write(party: Any, kind: PartyKind, spec: PartyIdentifierSpec, state: PartyPairState) { written = state }
        }
        val section = PartyExtensionsSection(PartyKind.BUYER, listOf(spec to extension), { it })
        val fields = textFieldsOf(section)
        fields[0].value = "EM:buyer@example.com"
        fields[1].value = "EM"

        section.writeTo(Any())

        assertEquals("EM:buyer@example.com", written?.value)
        assertEquals("EM", written?.schemeId)
    }

    // ── readFrom(Map<String, PartyPairState>), the recipient dialog's own read model ──

    @Test
    fun `readFrom on a pre-read state map loads both the value and the scheme field`() {
        val spec = PartyIdentifierSpec(id = "electronicAddress", i18nKeyPrefix = "recipient.electronicAddress")
        val section = PartyExtensionsSection(PartyKind.BUYER, listOf(spec to stubExtension(spec)), { it })

        section.readFrom(mapOf("electronicAddress" to PartyPairState("EM:buyer@example.com", "EM")))

        val fields = textFieldsOf(section)
        assertEquals("EM:buyer@example.com", fields[0].value)
        assertEquals("EM", fields[1].value)
    }

    @Test
    fun `readFrom on a pre-read state map treats a missing spec id as not set`() {
        val spec = PartyIdentifierSpec(id = "electronicAddress", i18nKeyPrefix = "recipient.electronicAddress")
        val section = PartyExtensionsSection(PartyKind.BUYER, listOf(spec to stubExtension(spec)), { it })

        section.readFrom(emptyMap())

        val fields = textFieldsOf(section)
        assertEquals("", fields[0].value)
        assertEquals("", fields[1].value)
    }

    // ── applyCountryContext, per-spec country visibility ─────────────

    // The real extension, not [stubExtension]: [stubExtension]'s read/write are no-ops (fine for
    // the field-count tests above), which would silently defeat the "a stored value survives
    // hiding" test below — that one needs read/write that actually round-trip through a
    // `Recipient`. Using the real SIRET spec also means its `countries = {FR}` is exactly what's
    // shipped, not a second copy of that config here.
    private val realFacturXExtension = de.littletrotter.application.masterdata.FacturXPartyFormExtension()
    private val siretSpec = de.littletrotter.application.masterdata.FacturXPartyFormExtension.SIRET_SPEC

    /** The single top-level group `Div` for [siretSpec] — the section adds exactly one child per
     *  pair, in [PartyExtensionsSection]'s pair order, so index 0 is this spec's group here. */
    private fun groupVisible(section: PartyExtensionsSection): Boolean = section.children.toList()[0].isVisible

    private fun siretSection(): PartyExtensionsSection =
        PartyExtensionsSection(PartyKind.BUYER, listOf(siretSpec to realFacturXExtension), { it })

    @Test
    fun `AP-6 - FR customer - SIRET is visible`() {
        val section = siretSection()

        section.applyCountryContext(Country.FR, null)

        assertTrue(groupVisible(section))
    }

    @Test
    fun `AP-6 - DE customer with FR vatId and DE address - SIRET is visible (decision 11)`() {
        val section = siretSection()

        section.applyCountryContext(Country.DE, "FR73282932000")

        assertTrue(groupVisible(section))
    }

    @Test
    fun `AP-6 - DE customer without a French VAT id - SIRET is hidden`() {
        val section = siretSection()

        section.applyCountryContext(Country.DE, "DE123456789")

        assertFalse(groupVisible(section))
    }

    @Test
    fun `AP-6 - LU customer - SIRET is hidden, SIRET is FR-only despite LU being in Factur-X scope`() {
        val section = siretSection()

        section.applyCountryContext(Country.LU, "LU12345678")

        assertFalse(groupVisible(section))
    }

    @Test
    fun `AP-6 - BE customer - SIRET is hidden, SIRET is FR-only despite BE being in Factur-X scope`() {
        val section = siretSection()

        section.applyCountryContext(Country.BE, "BE0123456789")

        assertFalse(groupVisible(section))
    }

    @Test
    fun `AP-6 - changing the country in the open form toggles visibility immediately`() {
        val section = siretSection()

        section.applyCountryContext(Country.DE, null)
        assertFalse(groupVisible(section), "should start hidden for a DE customer")

        section.applyCountryContext(Country.FR, null)
        assertTrue(groupVisible(section), "should become visible the moment the country becomes FR")

        section.applyCountryContext(Country.DE, null)
        assertFalse(groupVisible(section), "should hide again once the country leaves FR")
    }

    @Test
    fun `AP-6 - changing the VAT id in the open form toggles visibility immediately`() {
        val section = siretSection()

        section.applyCountryContext(Country.DE, "DE123456789")
        assertFalse(groupVisible(section))

        section.applyCountryContext(Country.DE, "FR73282932000")
        assertTrue(groupVisible(section), "an FR-prefixed VAT id alone is enough, decision 11")
    }

    @Test
    fun `AP-6 - a stored SIRET survives a country change away from FR and back`() {
        val section = siretSection()
        val buyer = Recipient().apply { siret = "73282932000074" }
        section.readFrom(buyer)
        section.applyCountryContext(Country.FR, null)
        assertTrue(groupVisible(section))

        // Country moves away from FR — the field disappears...
        section.applyCountryContext(Country.DE, null)
        assertFalse(groupVisible(section))

        // ...but writing the still-hidden section back must not have cleared the value.
        val target = Recipient()
        section.writeTo(target)
        assertEquals("73282932000074", target.siret, "hiding must never delete a stored value")

        // Country moves back to FR — the field reappears, still showing the original value.
        section.applyCountryContext(Country.FR, null)
        assertTrue(groupVisible(section))
        val targetAfterReturn = Recipient()
        section.writeTo(targetAfterReturn)
        assertEquals("73282932000074", targetAfterReturn.siret)
    }

    @Test
    fun `AP-6 - a country-independent spec (countries = null) is always visible regardless of context`() {
        val spec = PartyIdentifierSpec(id = "electronicAddress", i18nKeyPrefix = "recipient.electronicAddress")
        val section = PartyExtensionsSection(PartyKind.BUYER, listOf(spec to stubExtension(spec)), { it })

        section.applyCountryContext(Country.DE, "DE123456789")
        assertTrue(groupVisible(section))

        section.applyCountryContext(null, null)
        assertTrue(groupVisible(section), "no country context at all must not hide a country-independent pair")
    }
}
