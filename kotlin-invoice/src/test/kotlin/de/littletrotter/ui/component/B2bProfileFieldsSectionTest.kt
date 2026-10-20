package de.littletrotter.ui.component

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.domain.reference.B2bFieldValueOrigin
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.application.dto.ResolvedB2bFieldValueDto
import de.littletrotter.shared.b2b.B2bTerm
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [B2bProfileFieldsSection.prefillIfBlank], the mechanism behind
 * BT-155 defaulting to the selected article's Stammdaten-Artikelnummer in the goods/service item
 * dialogs (prefill, not a strict read-only binding) while never clobbering
 * a value the user already typed or that was loaded from storage.
 *
 * [B2bProfileFieldsSection.setFields] reads each field's [B2bProfileFieldDto.term] directly, so a
 * section needs no reference-data lookups to build.
 */
class B2bProfileFieldsSectionTest {

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
        placement = B2bPlacement.INVOICE,
        businessDefinition = "Definition of ${term.officialCode}",
        defaultValue = defaultValue,
    )

    private fun buildSection(vararg terms: B2bTerm): B2bProfileFieldsSection =
        B2bProfileFieldsSection { it }.apply {
            setFields(terms.map { profileField(it) })
        }

    private fun buildSectionWithFields(fields: List<B2bProfileFieldDto>): B2bProfileFieldsSection =
        B2bProfileFieldsSection { it }.apply { setFields(fields) }

    @Test
    fun `fills a blank widget with the given value`() {
        val section = buildSection(B2bTerm.BT_155)

        section.prefillIfBlank("BT-155", "ART-4711")

        assertEquals("ART-4711", section.currentValues()["BT-155"])
    }

    @Test
    fun `never overwrites a value already typed or loaded`() {
        val section = buildSection(B2bTerm.BT_155)
        val field = section.children.toList().filterIsInstance<com.vaadin.flow.component.formlayout.FormLayout>()
            .single().children.toList().filterIsInstance<TextField>().single()
        field.value = "MANUAL-1"

        section.prefillIfBlank("BT-155", "ART-4711")

        assertEquals("MANUAL-1", section.currentValues()["BT-155"], "a value the user already has must survive a prefill call")
    }

    @Test
    fun `finds the target widget even when it is not the first one rendered`() {
        val section = buildSection(B2bTerm.BT_156, B2bTerm.BT_155)

        section.prefillIfBlank("BT-155", "ART-4711")

        assertEquals("ART-4711", section.currentValues()["BT-155"], "the search must not stop at the first, non-matching widget")
    }

    @Test
    fun `is a no-op for a term this section does not render`() {
        val section = buildSection(B2bTerm.BT_156)

        section.prefillIfBlank("BT-155", "ART-4711")

        assertEquals(null, section.currentValues()["BT-155"])
    }

    @Test
    fun `is a no-op for a blank or null value`() {
        val section = buildSection(B2bTerm.BT_155)

        section.prefillIfBlank("BT-155", null)
        section.prefillIfBlank("BT-155", "   ")

        assertEquals(null, section.currentValues()["BT-155"])
    }

    // ── B2bProfileFieldsSection.applyDefaults ────────────────────

    @Test
    fun `applyDefaults fills a blank widget with its field's own default value`() {
        val section = buildSectionWithFields(listOf(profileField(B2bTerm.BT_58, defaultValue = "rechnung@nekar-supermarkt.de")))

        section.applyDefaults()

        assertEquals("rechnung@nekar-supermarkt.de", section.currentValues()["BT-58"])
    }

    @Test
    fun `applyDefaults never overwrites a value already loaded from storage`() {
        val section = buildSectionWithFields(listOf(profileField(B2bTerm.BT_58, defaultValue = "rechnung@nekar-supermarkt.de")))
        section.readFrom(mapOf("BT-58" to "other@example.com"))

        section.applyDefaults()

        assertEquals("other@example.com", section.currentValues()["BT-58"], "an already-stored value must survive applyDefaults")
    }

    @Test
    fun `applyDefaults is a no-op for a field without a default value`() {
        val section = buildSectionWithFields(listOf(profileField(B2bTerm.BT_19)))

        section.applyDefaults()

        assertEquals(null, section.currentValues()["BT-19"])
    }

    @Test
    fun `applyDefaults skips a field whose default value is blank`() {
        val section = buildSectionWithFields(listOf(profileField(B2bTerm.BT_19, defaultValue = "   ")))

        section.applyDefaults()

        assertEquals(null, section.currentValues()["BT-19"])
    }

    // ── setFields: fields that never get a widget ───────────────────────────

    @Test
    fun `a field spec with no term contributes no widget`() {
        val fieldWithoutTerm = B2bProfileFieldDto(businessDefinition = "no term set")
        val section = buildSectionWithFields(listOf(fieldWithoutTerm))

        assertEquals(false, section.hasContent)
    }

    @Test
    fun `a BOUND term contributes no widget — its value already lives on its own bound property`() {
        // BT-11 is BOUND (project number is carried by the invoice's own projectNumber property).
        val section = buildSection(B2bTerm.BT_11)

        assertEquals(false, section.hasContent)
    }

    // ── readFrom(values) ─────────────────────────────────────────────────────

    @Test
    fun `readFrom with an empty map clears every widget`() {
        val section = buildSectionWithFields(listOf(profileField(B2bTerm.BT_58)))
        section.readFrom(mapOf("BT-58" to "kunde@example.com"))

        section.readFrom(emptyMap())

        assertEquals(null, section.currentValues()["BT-58"])
    }

    // ── enterAdvanceCandidates / addValueChangeListener ────────────────────────

    @Test
    fun `enterAdvanceCandidates includes the rendered text widgets`() {
        val section = buildSection(B2bTerm.BT_155)

        assertEquals(1, section.enterAdvanceCandidates().size)
    }

    @Test
    fun `a widget's value change fires every registered listener`() {
        val section = buildSection(B2bTerm.BT_155)
        var notified = 0
        section.addValueChangeListener { notified++ }
        val field = section.enterAdvanceCandidates().filterIsInstance<TextField>().single()

        field.value = "changed"

        assertEquals(1, notified)
    }

    // ── fix-einvoice-guided-profile-party-values, Required change 2 — setGroupedFields ─────────

    @Test
    fun `setGroupedFields renders a headingless group as a bare FormLayout, same shape as setFields`() {
        val section = B2bProfileFieldsSection { it }

        section.setGroupedFields(listOf(null to listOf(profileField(B2bTerm.BT_58))))

        val topLevel = section.children.toList()
        assertEquals(1, topLevel.size)
        assertTrue(topLevel.single() is com.vaadin.flow.component.formlayout.FormLayout, "no Div wrapper, no heading for a null group")
    }

    @Test
    fun `setGroupedFields renders a headed group under an eyebrow heading in its own container`() {
        val section = B2bProfileFieldsSection { it }

        section.setGroupedFields(listOf("Profilfelder · Anbieter" to listOf(profileField(B2bTerm.BT_58))))

        val container = section.children.toList().filterIsInstance<Div>().single()
        assertTrue(container.element.classList.contains("sp-bt-pair-group"))
        val heading = container.children.toList().filterIsInstance<Span>().single()
        assertEquals("Profilfelder · Anbieter", heading.text)
    }

    @Test
    fun `setGroupedFields renders nothing at all for a group with no FREE field`() {
        val section = B2bProfileFieldsSection { it }

        // BT-11 is BOUND — no widget, so the whole group (heading included) must not render.
        section.setGroupedFields(listOf("Profilfelder · Anbieter" to listOf(profileField(B2bTerm.BT_11))))

        assertTrue(section.children.toList().isEmpty())
        assertFalse(section.hasContent)
    }

    @Test
    fun `setGroupedFields renders multiple groups in the given order, each independently`() {
        val section = B2bProfileFieldsSection { it }

        section.setGroupedFields(
            listOf(
                "Profilfelder" to listOf(profileField(B2bTerm.BT_19)),
                "Profilfelder · Anbieter" to listOf(profileField(B2bTerm.BT_58)),
            ),
        )

        assertEquals(setOf("BT-19", "BT-58"), section.currentValues().keys)
        assertEquals(2, section.children.toList().size, "one headingless FormLayout + one headed Div group")
    }

    @Test
    fun `setGroupedFields rebuilds from scratch — a second call drops the previous groups`() {
        val section = B2bProfileFieldsSection { it }
        section.setGroupedFields(listOf("A" to listOf(profileField(B2bTerm.BT_19))))

        section.setGroupedFields(listOf("B" to listOf(profileField(B2bTerm.BT_58))))

        assertEquals(setOf("BT-58"), section.currentValues().keys)
    }

    // ── Required change 1/2/3 — readResolved (prefill, inherited hint, write-back target) ──────

    @Test
    fun `readResolved prefills each widget from its resolved value`() {
        val section = buildSectionWithFields(listOf(profileField(B2bTerm.BT_58)))

        section.readResolved(mapOf("BT-58" to ResolvedB2bFieldValueDto("kunde@example.com", B2bFieldValueOrigin.PARTY, "kunde@example.com")), "hint")

        val widget = section.enterAdvanceCandidates().filterIsInstance<TextField>().single()
        assertEquals("kunde@example.com", widget.value, "the widget itself must show the resolved value")
    }

    @Test
    fun `readResolved leaves a field this section does not render untouched`() {
        val section = buildSectionWithFields(listOf(profileField(B2bTerm.BT_58)))

        section.readResolved(mapOf("BT-19" to ResolvedB2bFieldValueDto("x", B2bFieldValueOrigin.INVOICE, null)), "hint")

        assertEquals(null, section.currentValues()["BT-58"])
    }

    @Test
    fun `readResolved appends the inherited hint to the helper text only for a PARTY-origin value`() {
        val section = buildSectionWithFields(listOf(profileField(B2bTerm.BT_58)))
        val widget = section.enterAdvanceCandidates().filterIsInstance<TextField>().single()
        val baseHelper = widget.helperText

        section.readResolved(mapOf("BT-58" to ResolvedB2bFieldValueDto("kunde@example.com", B2bFieldValueOrigin.PARTY, "kunde@example.com")), "Aus den Stammdaten übernommen")

        assertTrue(widget.helperText.contains("Aus den Stammdaten übernommen"))
        if (!baseHelper.isNullOrBlank()) assertTrue(widget.helperText.contains(baseHelper))
    }

    @Test
    fun `readResolved does not append the hint for an INVOICE-origin value`() {
        val section = buildSectionWithFields(listOf(profileField(B2bTerm.BT_58)))
        val widget = section.enterAdvanceCandidates().filterIsInstance<TextField>().single()
        val baseHelper = widget.helperText

        section.readResolved(mapOf("BT-58" to ResolvedB2bFieldValueDto("invoice@example.com", B2bFieldValueOrigin.INVOICE, "kunde@example.com")), "hint")

        assertEquals(baseHelper, widget.helperText)
    }

    @Test
    fun `readResolved does not append the hint for a DEFAULT-origin value - a profile default is not master data`() {
        val section = buildSectionWithFields(listOf(profileField(B2bTerm.BT_58)))
        val widget = section.enterAdvanceCandidates().filterIsInstance<TextField>().single()
        val baseHelper = widget.helperText

        section.readResolved(mapOf("BT-58" to ResolvedB2bFieldValueDto("default@example.com", B2bFieldValueOrigin.DEFAULT, null)), "hint")

        assertEquals(baseHelper, widget.helperText)
    }

    @Test
    fun `readResolved restores the base helper text when a later value is no longer PARTY-origin`() {
        val section = buildSectionWithFields(listOf(profileField(B2bTerm.BT_58)))
        val widget = section.enterAdvanceCandidates().filterIsInstance<TextField>().single()
        val baseHelper = widget.helperText
        section.readResolved(mapOf("BT-58" to ResolvedB2bFieldValueDto("kunde@example.com", B2bFieldValueOrigin.PARTY, "kunde@example.com")), "hint")

        section.readResolved(mapOf("BT-58" to ResolvedB2bFieldValueDto("invoice@example.com", B2bFieldValueOrigin.INVOICE, "kunde@example.com")), "hint")

        assertEquals(baseHelper, widget.helperText)
    }

    // ── The write-back rule: currentValues() nulls out an untouched inherited value ───────

    @Test
    fun `currentValues reports null for a widget left at its inherited PARTY value - no spurious invoice row`() {
        val section = buildSectionWithFields(listOf(profileField(B2bTerm.BT_58)))

        section.readResolved(mapOf("BT-58" to ResolvedB2bFieldValueDto("kunde@example.com", B2bFieldValueOrigin.PARTY, "kunde@example.com")), "hint")

        assertEquals(null, section.currentValues()["BT-58"], "unedited inherited value must not materialize as an invoice row")
    }

    @Test
    fun `currentValues reports the edited value once the user overrides an inherited value`() {
        val section = buildSectionWithFields(listOf(profileField(B2bTerm.BT_58)))
        section.readResolved(mapOf("BT-58" to ResolvedB2bFieldValueDto("kunde@example.com", B2bFieldValueOrigin.PARTY, "kunde@example.com")), "hint")
        val widget = section.enterAdvanceCandidates().filterIsInstance<TextField>().single()

        widget.value = "override@example.com"

        assertEquals("override@example.com", section.currentValues()["BT-58"])
    }

    @Test
    fun `currentValues reports null again once an override is edited back to the inherited value`() {
        val section = buildSectionWithFields(listOf(profileField(B2bTerm.BT_58)))
        section.readResolved(mapOf("BT-58" to ResolvedB2bFieldValueDto("kunde@example.com", B2bFieldValueOrigin.PARTY, "kunde@example.com")), "hint")
        val widget = section.enterAdvanceCandidates().filterIsInstance<TextField>().single()
        widget.value = "override@example.com"
        assertEquals("override@example.com", section.currentValues()["BT-58"])

        widget.value = "kunde@example.com"

        assertEquals(null, section.currentValues()["BT-58"], "editing back to the inherited value must revert to null (deletes the row)")
    }

    @Test
    fun `currentValues reports the resolved default when it is edited, even though there is no party tier`() {
        val section = buildSectionWithFields(listOf(profileField(B2bTerm.BT_58)))
        section.readResolved(mapOf("BT-58" to ResolvedB2bFieldValueDto("default@example.com", B2bFieldValueOrigin.DEFAULT, "default@example.com")), "hint")
        val widget = section.enterAdvanceCandidates().filterIsInstance<TextField>().single()

        widget.value = "override@example.com"

        assertEquals("override@example.com", section.currentValues()["BT-58"])
    }

    @Test
    fun `currentValues behaves exactly as before readResolved for a section that only ever used readFrom`() {
        // A caller that never calls readResolved (RecipientView, invoice item dialogs) must see no
        // behavior change at all: inheritedValue stays null, so the write-back comparison never
        // fires for it.
        val section = buildSectionWithFields(listOf(profileField(B2bTerm.BT_58)))

        section.readFrom(mapOf("BT-58" to "kunde@example.com"))

        assertEquals("kunde@example.com", section.currentValues()["BT-58"], "a plain readFrom-loaded value is never nulled out")
    }

    // ── B2bProfileFieldsSection.componentFor ────────────────────

    @Test
    fun `happy path - componentFor returns the widget for a rendered term`() {
        val section = buildSection(B2bTerm.BT_155)

        val component = section.componentFor("BT-155")

        assertTrue(component is TextField)
    }

    @Test
    fun `boundary - componentFor returns null for a term this section does not render`() {
        val section = buildSection(B2bTerm.BT_155)

        assertEquals(null, section.componentFor("BT-156"))
    }

    // ── B2bProfileFieldsSection.displayedValues ─────────────────────

    @Test
    fun `happy path - displayedValues shows the live widget value, unlike currentValues it never suppresses an inherited one`() {
        val section = buildSection(B2bTerm.BT_155)
        section.prefillIfBlank("BT-155", "ART-4711")

        assertEquals("ART-4711", section.displayedValues()["BT-155"])
    }

    @Test
    fun `boundary - displayedValues is blank for a widget nobody ever filled`() {
        val section = buildSection(B2bTerm.BT_155)

        assertEquals(null, section.displayedValues()["BT-155"])
    }
}
