package de.littletrotter.ui.component

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.application.dto.B2bSellerFieldGroupDto
import de.littletrotter.shared.b2b.B2bTerm
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * [ScopedB2bProfileFieldsSection]'s "hiding
 * never deletes" contract: a country group's *visibility* toggles reactively via
 * [ScopedB2bProfileFieldsSection.applySellerCountries], but the widget tree itself is built once
 * ([ScopedB2bProfileFieldsSection.setFields]) and a value typed into a currently-hidden group
 * survives being hidden and shown again — mirrors [PartyExtensionsSectionTest]'s matching test.
 */
class ScopedB2bProfileFieldsSectionTest {

    private val facade: B2bFieldValueQueryPort = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun profileField(term: B2bTerm, defaultValue: String? = null) = B2bProfileFieldDto(
        term = term,
        placement = B2bPlacement.SELLER,
        businessDefinition = "Definition of ${term.officialCode}",
        defaultValue = defaultValue,
    )

    private fun buildSection(vararg groups: B2bSellerFieldGroupDto): ScopedB2bProfileFieldsSection =
        ScopedB2bProfileFieldsSection(facade, B2bFieldOwnerType.SENDER_PROFILE) { it }.apply {
            setFields(groups.toList())
        }

    @Test
    fun `hasContent is false when the union is empty`() {
        val section = buildSection()
        assertFalse(section.hasContent)
    }

    @Test
    fun `a group entry whose field has no term contributes no widget`() {
        val fieldWithoutTerm = B2bProfileFieldDto(businessDefinition = "no term set")
        val section = buildSection(B2bSellerFieldGroupDto(fieldWithoutTerm, group = null, triggerCountries = emptySet()))

        assertFalse(section.hasContent)
    }

    @Test
    fun `an unscoped field is always visible, no applySellerCountries call needed`() {
        val section = buildSection(B2bSellerFieldGroupDto(profileField(B2bTerm.BT_19), group = null, triggerCountries = emptySet()))
        assertTrue(section.hasContent)
        // The unscoped container is index 0, always visible (gated only on hasContent, not on any
        // seller countries) — see ScopedB2bProfileFieldsSection.setFields.
        assertTrue(section.children.toList()[0].isVisible)
    }

    @Test
    fun `a country group starts hidden and becomes visible once the seller matches`() {
        // BT-12 (contract reference) is FREE-bound — unlike BT-84/IBAN (BOUND, no widget here),
        // it actually renders a widget in this section (class KDoc).
        val section = buildSection(B2bSellerFieldGroupDto(profileField(B2bTerm.BT_12), group = Country.FR, triggerCountries = setOf(Country.FR)))
        val group = section.children.toList()[2] // [unscopedContainer, hintLabel, FR group]
        assertFalse(group.isVisible, "no applySellerCountries call yet — must start hidden")

        section.applySellerCountries(setOf(Country.FR))
        assertTrue(group.isVisible)

        section.applySellerCountries(setOf(Country.DE))
        assertFalse(group.isVisible, "seller no longer matches FR")
    }

    @Test
    fun `a value typed into a hidden group survives being hidden and shown again`() {
        val section = buildSection(B2bSellerFieldGroupDto(profileField(B2bTerm.BT_12), group = Country.FR, triggerCountries = setOf(Country.FR)))
        val group = section.children.toList()[2]
        val textField = group.children.toList().filterIsInstance<com.vaadin.flow.component.formlayout.FormLayout>()
            .single().children.toList().filterIsInstance<TextField>().single()

        // The anwender types a value while the group happens to be visible...
        section.applySellerCountries(setOf(Country.FR))
        textField.value = "VERTRAG-FR-42"

        // ...the seller's registration is then removed (group hides)...
        section.applySellerCountries(emptySet())
        assertFalse(group.isVisible)
        assertEquals("VERTRAG-FR-42", section.currentValues()["BT-12"], "hiding must never delete a typed value")

        // ...and re-added (group reappears, value untouched).
        section.applySellerCountries(setOf(Country.FR))
        assertTrue(group.isVisible)
        assertEquals("VERTRAG-FR-42", section.currentValues()["BT-12"])
    }

    @Test
    fun `the empty-state hint shows only when scoped groups exist and none is currently visible`() {
        val section = buildSection(B2bSellerFieldGroupDto(profileField(B2bTerm.BT_12), group = Country.FR, triggerCountries = setOf(Country.FR)))
        val hint = section.children.toList()[1]
        assertFalse(hint.isVisible, "not yet evaluated — starts hidden")

        section.applySellerCountries(setOf(Country.DE))
        assertTrue(hint.isVisible, "scoped groups exist but none match — hint must show")

        section.applySellerCountries(setOf(Country.FR))
        assertFalse(hint.isVisible, "FR group is now visible — no hint needed")
    }

    @Test
    fun `no hint when there are no scoped groups at all`() {
        val section = buildSection(B2bSellerFieldGroupDto(profileField(B2bTerm.BT_19), group = null, triggerCountries = emptySet()))
        section.applySellerCountries(emptySet())
        assertFalse(section.children.toList()[1].isVisible, "unscoped-only union never needs the hint")
    }

    // ── ScopedB2bProfileFieldsSection.applyDefaults ──────────────

    @Test
    fun `applyDefaults fills a blank unscoped widget with its field's own default value`() {
        val section = buildSection(B2bSellerFieldGroupDto(profileField(B2bTerm.BT_19, defaultValue = "KST-4711"), group = null, triggerCountries = emptySet()))

        section.applyDefaults()

        assertEquals("KST-4711", section.currentValues()["BT-19"])
    }

    @Test
    fun `applyDefaults never overwrites a value already loaded from storage`() {
        val section = buildSection(B2bSellerFieldGroupDto(profileField(B2bTerm.BT_19, defaultValue = "KST-4711"), group = null, triggerCountries = emptySet()))
        whenever(facade.findB2bFieldValues(B2bFieldOwnerType.SENDER_PROFILE, 7L)).thenReturn(mapOf("BT-19" to "KST-ANDERS"))
        section.readFrom(7L)

        section.applyDefaults()

        assertEquals("KST-ANDERS", section.currentValues()["BT-19"], "an already-stored value must survive applyDefaults")
    }

    @Test
    fun `applyDefaults is a no-op for a field without a default value`() {
        val section = buildSection(B2bSellerFieldGroupDto(profileField(B2bTerm.BT_19), group = null, triggerCountries = emptySet()))

        section.applyDefaults()

        assertEquals(null, section.currentValues()["BT-19"])
    }

    @Test
    fun `applyDefaults skips a field whose default value is blank`() {
        val section = buildSection(B2bSellerFieldGroupDto(profileField(B2bTerm.BT_19, defaultValue = "   "), group = null, triggerCountries = emptySet()))

        section.applyDefaults()

        assertEquals(null, section.currentValues()["BT-19"])
    }

    @Test
    fun `applyDefaults also fills a currently-hidden group's widget`() {
        val section = buildSection(B2bSellerFieldGroupDto(profileField(B2bTerm.BT_12, defaultValue = "VERTRAG-STANDARD"), group = Country.FR, triggerCountries = setOf(Country.FR)))
        // No applySellerCountries call — the FR group starts hidden, same as "hiding never deletes"
        // guarantees a typed value survives, a default must reach a hidden group's widget too.

        section.applyDefaults()

        assertEquals("VERTRAG-STANDARD", section.currentValues()["BT-12"])
    }
}
