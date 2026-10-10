package de.littletrotter.ui.view.referencedata

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._find
import com.vaadin.flow.component.ComponentUtil
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.textfield.TextArea
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldDefinitionUseCase
import de.littletrotter.application.reference.port.inbound.B2bProfileQueryPort
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Dedicated test for [B2bProfileRulesSection], split out of [B2bProfilesTabKaribuTest]
 * (which drove all of this indirectly through [B2bProfilesTab]'s own reflection helpers). This
 * suite instantiates the section directly, with its own [B2bFieldEditorPanel], without a hosting
 * [B2bProfilesTab] — the wiring between [B2bFieldEditorPanel]'s `onSaved` callback and this
 * section's own [B2bProfileRulesSection.refresh] is [B2bProfilesTab]'s own concern (see
 * `B2bProfilesTabKaribuTest`'s "saving a field from within the popup..." test) and stays there.
 */
class B2bProfileRulesSectionKaribuTest {

    private val b2bProfileQueries: B2bProfileQueryPort = mock()
    private val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = mock()
    private val manageB2bFieldDefinition: ManageB2bFieldDefinitionUseCase = mock()
    private var mutatedProfile: B2bProfileDto? = null
    private lateinit var panel: B2bFieldEditorPanel
    private lateinit var section: B2bProfileRulesSection

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        panel = B2bFieldEditorPanel(
            b2bFieldDefinitionQueries,
            manageB2bFieldDefinition,
            onSaved = {},
            onRequestDelete = { field -> section.confirmDeleteField(field) },
        )
        section = B2bProfileRulesSection(b2bProfileQueries, b2bFieldDefinitionQueries, manageB2bFieldDefinition, panel, onFieldMutated = { mutatedProfile = it })
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private inline fun <reified T> field(target: Any, name: String): T {
        val f = target.javaClass.getDeclaredField(name)
        f.isAccessible = true
        return f.get(target) as T
    }

    private fun privateMethod(target: Any, name: String, vararg paramTypes: Class<*>): java.lang.reflect.Method {
        val m = target.javaClass.getDeclaredMethod(name, *paramTypes)
        m.isAccessible = true
        return m
    }

    private fun requirementBadge(field: B2bProfileFieldDto): Span =
        privateMethod(section, "requirementBadge", B2bProfileFieldDto::class.java).invoke(section, field) as Span

    private fun defaultValueCell(field: B2bProfileFieldDto): Span =
        privateMethod(section, "defaultValueCell", B2bProfileFieldDto::class.java).invoke(section, field) as Span

    private fun businessDefinitionCell(field: B2bProfileFieldDto): Span =
        privateMethod(section, "businessDefinitionCell", B2bProfileFieldDto::class.java).invoke(section, field) as Span

    private fun placementLabel(placement: B2bPlacement): String =
        privateMethod(section, "placementLabel", B2bPlacement::class.java).invoke(section, placement) as String

    private fun fieldRowActions(f: B2bProfileFieldDto): HorizontalLayout =
        privateMethod(section, "fieldRowActions", B2bProfileFieldDto::class.java).invoke(section, f) as HorizontalLayout

    private fun invokeOpenFieldPanel(f: B2bProfileFieldDto?) {
        privateMethod(section, "openFieldPanel", B2bProfileFieldDto::class.java).invoke(section, f)
    }

    private fun invokeDeleteField(id: Long) {
        val m = B2bProfileRulesSection::class.java.getDeclaredMethod("deleteField", Long::class.javaPrimitiveType)
        m.isAccessible = true
        m.invoke(section, id)
    }

    private fun businessDefinitionArea(): TextArea = field(panel, "businessDefinitionArea")

    private fun popupDialog(): Dialog = field(panel, "popupDialog")

    private fun profileField(term: B2bTerm, requirement: B2bFieldRequirement, conditionTerm: B2bTerm? = null, defaultValue: String? = null) =
        B2bProfileFieldDto(
            term = term,
            requirement = requirement,
            conditionTerm = conditionTerm,
            businessDefinition = "Definition ${term.officialCode}",
            defaultValue = defaultValue,
        )

    // ── Requirement badges, default-value truncation ───────────────────────────────────────────

    @Test
    fun `MANDATORY renders as the error-themed badge`() {
        val badge = requirementBadge(profileField(B2bTerm.BT_19, B2bFieldRequirement.MANDATORY))

        assertTrue(badge.classNames.contains("status-chip--error"))
        assertEquals(UiTranslations.tr("referenceData.b2b.field.requirement.mandatory"), badge.text)
    }

    @Test
    fun `OPTIONAL renders as the neutral contrast badge`() {
        val badge = requirementBadge(profileField(B2bTerm.BT_19, B2bFieldRequirement.OPTIONAL))

        assertTrue(badge.classNames.contains("status-chip--neutral"))
        assertEquals(UiTranslations.tr("referenceData.b2b.field.requirement.optional"), badge.text)
    }

    @Test
    fun `CONDITIONAL renders as an amber badge naming its trigger term, not brown, not error-red`() {
        val badge = requirementBadge(profileField(B2bTerm.BT_19, B2bFieldRequirement.CONDITIONAL, conditionTerm = B2bTerm.BT_72))

        assertTrue(badge.classNames.contains("status-chip--warn"))
        assertFalse(badge.classNames.contains("status-chip--error"), "CONDITIONAL must not reuse the MANDATORY error color")
        assertEquals(UiTranslations.tr("referenceData.b2b.field.requirement.conditional.badge", "BT-72"), badge.text)
        assertTrue(badge.text.contains("BT-72"), "the badge must name the trigger term")
    }

    @Test
    fun `CONDITIONAL_IF_EMPTY renders as the same amber badge family, with the if-empty wording`() {
        val badge = requirementBadge(
            profileField(B2bTerm.BT_72, B2bFieldRequirement.CONDITIONAL_IF_EMPTY, conditionTerm = B2bTerm.BT_134),
        )

        assertTrue(badge.classNames.contains("status-chip--warn"))
        assertEquals(UiTranslations.tr("referenceData.b2b.field.requirement.conditionalIfEmpty.badge", "BT-134"), badge.text)
    }

    @Test
    fun `CONDITIONAL with no condition term yet still renders a badge, with an empty trigger code`() {
        val badge = requirementBadge(profileField(B2bTerm.BT_19, B2bFieldRequirement.CONDITIONAL, conditionTerm = null))

        assertEquals(UiTranslations.tr("referenceData.b2b.field.requirement.conditional.badge", ""), badge.text)
    }

    @Test
    fun `CONDITIONAL_IF_EMPTY with no condition term yet still renders a badge, with an empty trigger code`() {
        val badge = requirementBadge(profileField(B2bTerm.BT_72, B2bFieldRequirement.CONDITIONAL_IF_EMPTY, conditionTerm = null))

        assertEquals(UiTranslations.tr("referenceData.b2b.field.requirement.conditionalIfEmpty.badge", ""), badge.text)
    }

    @Test
    fun `default value cell truncates a long value and carries the full value as a tooltip`() {
        val longValue = "rechnung-buchhaltung-nekar-supermarkt-zentrale@example.com"

        val cell = defaultValueCell(profileField(B2bTerm.BT_58, B2bFieldRequirement.OPTIONAL, defaultValue = longValue))

        assertTrue(cell.text.length < longValue.length, "the rendered text must be shorter than the full value")
        assertTrue(cell.text.endsWith("…"))
        assertEquals(longValue, cell.element.getAttribute("title"))
    }

    @Test
    fun `default value cell is empty for a field with no default (BOUND terms never carry one)`() {
        val cell = defaultValueCell(profileField(B2bTerm.BT_84, B2bFieldRequirement.OPTIONAL, defaultValue = null))

        assertEquals("", cell.text)
        assertEquals(null, cell.element.getAttribute("title"))
    }

    // ── Rules grid "Beschreibung" column ─────────────────────────────────────────────────────────

    @Test
    fun `the rules grid's description column header is Beschreibung, not the old Eigene Definition`() {
        assertEquals("Beschreibung", UiTranslations.tr("referenceData.b2b.field.definition"))
    }

    @Test
    fun `the definition cell keeps the full text and is CSS-ellipsized to one line, with a tooltip carrying the full text`() {
        val longDefinition = "Diese Geschäftsdefinition ist sehr lang und würde ohne Kürzung die übrigen Spalten und die Zeilen-Buttons aus dem Sichtbereich drängen."
        val f = profileField(B2bTerm.BT_58, B2bFieldRequirement.OPTIONAL).copy(businessDefinition = longDefinition)

        val cell = businessDefinitionCell(f)

        assertTrue(cell.text.length <= 21, "at most 20 characters plus the ellipsis character")
        assertTrue(cell.text.endsWith("…"), "a capped definition must end with an ellipsis")
        assertEquals(longDefinition.take(20).trimEnd(), cell.text.dropLast(1), "the visible prefix must be the definition's own first 20 characters")
        assertTrue(cell.element.classList.contains("b2b-definition-cell"), "must keep the single-line CSS-ellipsis idiom")
        assertEquals(longDefinition, cell.element.getAttribute("title"), "the full text must still be reachable on mouse-over")
    }

    @Test
    fun `the definition cell carries no tooltip when the definition is empty`() {
        val f = profileField(B2bTerm.BT_58, B2bFieldRequirement.OPTIONAL).copy(businessDefinition = "")

        val cell = businessDefinitionCell(f)

        assertNull(cell.element.getAttribute("title"))
    }

    @Test
    fun `the rules grid uses the businessDefinitionCell renderer under its Beschreibung header`() {
        val profile = B2bProfileDto(id = 44L, name = "Kunde OP")
        whenever(b2bFieldDefinitionQueries.findB2bProfileFields(44L)).thenReturn(listOf(profileField(B2bTerm.BT_19, B2bFieldRequirement.OPTIONAL)))

        section.refresh(profile)

        val table = section.children.toList().filterIsInstance<DataTable<*>>().first()
        val headers = table.columns.map { it.headerText }
        assertTrue(headers.contains(UiTranslations.tr("referenceData.b2b.field.definition")), "column header must read Beschreibung")
    }

    // ── Edit/delete actions are the rules grid's first column ──────────────────────────────────

    @Test
    fun `the actions column is last and frozen to the end in the rules grid`() {
        val profile = B2bProfileDto(id = 50L, name = "Kunde WX")
        whenever(b2bFieldDefinitionQueries.findB2bProfileFields(50L)).thenReturn(listOf(profileField(B2bTerm.BT_19, B2bFieldRequirement.OPTIONAL)))

        section.refresh(profile)

        val table = section.children.toList().filterIsInstance<DataTable<*>>().first()
        val headers = table.columns.map { it.headerText }
        assertEquals("", headers.last(), "the actions column (edit/delete) has no header and must be last")
        assertEquals(UiTranslations.tr("referenceData.b2b.field.term"), headers.first(), "the BT-code/term column leads the grid")
        assertTrue(table.columns.last().isFrozenToEnd)
    }

    // ── Refresh renders the grid or the save-first hint ─────────────────────────────────────────

    @Test
    fun `refresh shows the fields grid for an existing profile`() {
        val profile = B2bProfileDto(id = 42L, name = "Kunde XY")
        whenever(b2bFieldDefinitionQueries.findB2bProfileFields(42L)).thenReturn(listOf(profileField(B2bTerm.BT_19, B2bFieldRequirement.OPTIONAL)))

        section.refresh(profile)

        assertTrue(section.children.toList().any { it is DataTable<*> }, "an existing profile must show the rules grid, not the save-first hint")
    }

    @Test
    fun `refresh shows a save-first hint for a new, unsaved profile`() {
        section.refresh(null)

        val texts = section.children.toList().filterIsInstance<Paragraph>().map { it.text }
        assertTrue(texts.contains(UiTranslations.tr("referenceData.b2b.profile.rules.saveFirst")))
        assertTrue(section.children.toList().none { it is DataTable<*> }, "no grid before the profile has an id")
    }

    // ── Summary chips ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the summary chips show rule, mandatory, and conditional counts plus the T9 usage count`() {
        val profile = B2bProfileDto(id = 11L, name = "Kunde EF")
        whenever(b2bFieldDefinitionQueries.findB2bProfileFields(11L)).thenReturn(
            listOf(
                profileField(B2bTerm.BT_19, B2bFieldRequirement.MANDATORY),
                profileField(B2bTerm.BT_56, B2bFieldRequirement.CONDITIONAL, conditionTerm = B2bTerm.BT_19),
                profileField(B2bTerm.BT_58, B2bFieldRequirement.OPTIONAL),
            ),
        )
        whenever(b2bProfileQueries.findB2bProfileUsageNames(11L)).thenReturn(listOf("Kunde A", "Kunde B"))

        section.refresh(profile)

        val chips = section.summaryChips.children.toList().filterIsInstance<Span>()
        assertEquals(2, chips.size, "one rules-summary chip + one usage chip")
        assertEquals(UiTranslations.tr("referenceData.b2b.profile.rules.summary", 3, 1, 1), chips[0].text)
        assertEquals(UiTranslations.tr("referenceData.b2b.profile.usage.chip", 2), chips[1].text)
    }

    @Test
    fun `the summary chips are hidden for a new, unsaved profile`() {
        section.refresh(null)

        assertFalse(section.summaryChips.isVisible)
    }

    @Test
    fun `the summary chips count a CONDITIONAL_IF_EMPTY-only profile as one conditional rule`() {
        val profile = B2bProfileDto(id = 13L, name = "Kunde IfEmpty")
        whenever(b2bFieldDefinitionQueries.findB2bProfileFields(13L)).thenReturn(
            listOf(profileField(B2bTerm.BT_56, B2bFieldRequirement.CONDITIONAL_IF_EMPTY, conditionTerm = B2bTerm.BT_19)),
        )
        whenever(b2bProfileQueries.findB2bProfileUsageNames(13L)).thenReturn(emptyList())

        section.refresh(profile)

        val chips = section.summaryChips.children.toList().filterIsInstance<Span>()
        assertEquals(
            UiTranslations.tr("referenceData.b2b.profile.rules.summary", 1, 0, 1),
            chips[0].text,
            "CONDITIONAL_IF_EMPTY must count toward the conditional bucket, same as plain CONDITIONAL",
        )
    }

    // ── confirmDeleteField / deleteField ─────────────────────────────────────────────────────────

    @Test
    fun `confirmDeleteField opens a dialog naming the field's term`() {
        val f = profileField(B2bTerm.BT_19, B2bFieldRequirement.OPTIONAL).copy(id = 90L)

        section.confirmDeleteField(f)

        val dlg = _find<ConfirmDialog>().single()
        assertEquals(UiTranslations.tr("referenceData.b2b.field.deleteConfirm.text", "BT-19"), dlg.element.getProperty("message"))
    }

    @Test
    fun `confirmDeleteField names an empty term code for a field with no term selected yet`() {
        val f = B2bProfileFieldDto(id = 91L, term = null, requirement = B2bFieldRequirement.OPTIONAL)

        section.confirmDeleteField(f)

        val dlg = _find<ConfirmDialog>().single()
        assertEquals(UiTranslations.tr("referenceData.b2b.field.deleteConfirm.text", ""), dlg.element.getProperty("message"))
    }

    @Test
    fun `confirmDeleteField is a no-op for a field with no id yet - nothing persisted to delete`() {
        val f = profileField(B2bTerm.BT_19, B2bFieldRequirement.OPTIONAL) // id stays null

        section.confirmDeleteField(f)

        assertTrue(_find<ConfirmDialog>().isEmpty(), "no id means nothing to confirm deleting")
    }

    @Test
    fun `deleteField with no profile loaded still deletes but refreshes nothing`() {
        invokeDeleteField(91L)

        verify(manageB2bFieldDefinition).deleteB2bProfileField(91L)
        verify(b2bFieldDefinitionQueries, never()).findB2bProfileFields(any())
        assertNull(mutatedProfile)
    }

    @Test
    fun `deleting the field currently open in the popup resets it to the empty placeholder`() {
        val profile = B2bProfileDto(id = 92L, name = "Kunde Feld")
        val existingField = profileField(B2bTerm.BT_19, B2bFieldRequirement.OPTIONAL).copy(id = 93L)
        whenever(b2bFieldDefinitionQueries.findB2bProfileFields(92L)).thenReturn(listOf(existingField))
        whenever(b2bFieldDefinitionQueries.findSelectableB2bCatalogEntries(92L, 93L)).thenReturn(emptyList())
        section.refresh(profile)
        panel.openFor(profile, existingField)
        assertEquals(93L, panel.currentField()?.id)

        whenever(b2bFieldDefinitionQueries.findB2bProfileFields(92L)).thenReturn(emptyList())
        invokeDeleteField(93L)

        assertNull(panel.currentField(), "the popup must reset once its own field was deleted")
        assertEquals(92L, mutatedProfile?.id)
    }

    @Test
    fun `deleting a field other than the one open in the popup leaves it untouched`() {
        val profile = B2bProfileDto(id = 94L, name = "Kunde Feld2")
        val openField = profileField(B2bTerm.BT_19, B2bFieldRequirement.OPTIONAL).copy(id = 95L)
        whenever(b2bFieldDefinitionQueries.findB2bProfileFields(94L)).thenReturn(listOf(openField))
        whenever(b2bFieldDefinitionQueries.findSelectableB2bCatalogEntries(94L, 95L)).thenReturn(emptyList())
        section.refresh(profile)
        panel.openFor(profile, openField)

        invokeDeleteField(999L)

        assertEquals(95L, panel.currentField()?.id, "deleting an unrelated field must not touch the popup's own state")
    }

    @Test
    fun `a facade error while deleting a field surfaces an error notification, not a crash`() {
        doThrow(InvalidParameterException("field in use")).whenever(manageB2bFieldDefinition).deleteB2bProfileField(96L)

        invokeDeleteField(96L)
        // No exception — the catch branch handled it.
    }

    // ── setPartNameGenerator: the active-rule-row highlight ─────────────────────────────────────

    @Test
    fun `the rules grid marks the row currently open in the popup as the active-rule-row`() {
        val profile = B2bProfileDto(id = 97L, name = "Kunde Active")
        val fieldA = profileField(B2bTerm.BT_19, B2bFieldRequirement.OPTIONAL).copy(id = 1L)
        val fieldB = profileField(B2bTerm.BT_56, B2bFieldRequirement.OPTIONAL).copy(id = 2L)
        whenever(b2bFieldDefinitionQueries.findB2bProfileFields(97L)).thenReturn(listOf(fieldA, fieldB))
        whenever(b2bFieldDefinitionQueries.findSelectableB2bCatalogEntries(97L, 1L)).thenReturn(emptyList())
        section.refresh(profile)
        panel.openFor(profile, fieldA)

        val table = section.children.toList().filterIsInstance<DataTable<B2bProfileFieldDto>>().first()
        val generator = table.partNameGenerator

        assertEquals("active-rule-row", generator.apply(fieldA))
        assertNull(generator.apply(fieldB), "a field that isn't currently open in the popup gets no part name")
    }

    // ── placementLabel ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `placementLabel covers every placement branch`() {
        assertEquals(UiTranslations.tr("referenceData.b2b.field.placement.invoice"), placementLabel(B2bPlacement.INVOICE))
        assertEquals(UiTranslations.tr("referenceData.b2b.field.placement.recipient"), placementLabel(B2bPlacement.RECIPIENT))
        assertEquals(UiTranslations.tr("referenceData.b2b.field.placement.seller"), placementLabel(B2bPlacement.SELLER))
    }

    // ── openFieldPanel: unsaved-changes guard and the null-profile no-op ────────────────────────

    @Test
    fun `openFieldPanel is a no-op without a loaded profile`() {
        invokeOpenFieldPanel(null)

        assertTrue(_find<Dialog>().none { it.isOpened }, "no popup or unsaved-guard dialog must open without a loaded profile")
    }

    @Test
    fun `boundary - opening a different field while the panel has unsaved edits shows the discard-confirm dialog`() {
        val profile = B2bProfileDto(id = 40L, name = "Kunde Dirty")
        val fieldA = profileField(B2bTerm.BT_19, B2bFieldRequirement.OPTIONAL).copy(id = 10L)
        val fieldB = profileField(B2bTerm.BT_56, B2bFieldRequirement.OPTIONAL).copy(id = 11L)
        whenever(b2bFieldDefinitionQueries.findB2bProfileFields(40L)).thenReturn(listOf(fieldA, fieldB))
        whenever(b2bFieldDefinitionQueries.findSelectableB2bCatalogEntries(any(), any())).thenReturn(emptyList())
        section.refresh(profile)

        invokeOpenFieldPanel(fieldA)
        businessDefinitionArea().value = "nicht gespeicherter Text"
        assertTrue(panel.isDirty())

        invokeOpenFieldPanel(fieldB)

        val dlg = _find<ConfirmDialog>().single()
        assertEquals(UiTranslations.tr("referenceData.b2b.field.unsavedGuard.title"), dlg.element.getProperty("header"))

        ComponentUtil.fireEvent(dlg, ConfirmDialog.ConfirmEvent(dlg, false))

        assertEquals(fieldB.id, panel.currentField()?.id, "confirming discard must force-reopen the popup for the newly requested field")
    }

    @Test
    fun `Feld hinzufuegen opens the same popup in create mode`() {
        val profile = B2bProfileDto(id = 31L, name = "Kunde ST")
        whenever(b2bFieldDefinitionQueries.findB2bProfileFields(31L)).thenReturn(emptyList())
        section.refresh(profile)

        val addButton = section.children.toList()
            .filterIsInstance<HorizontalLayout>()
            .flatMap { it.children.toList() }
            .filterIsInstance<Button>()
            .first { it.text == UiTranslations.tr("referenceData.b2b.field.add") }
        addButton.click()

        assertTrue(popupDialog().isOpened, "Feld hinzufügen must open the popup, in create mode")
        assertNull(panel.currentField(), "create mode — no field selected yet")
    }

    @Test
    fun `clicking a rules-grid row's edit button opens the popup`() {
        val profile = B2bProfileDto(id = 30L, name = "Kunde QR")
        val existingField = profileField(B2bTerm.BT_19, B2bFieldRequirement.OPTIONAL).copy(id = 6L)
        whenever(b2bFieldDefinitionQueries.findB2bProfileFields(30L)).thenReturn(listOf(existingField))
        whenever(b2bFieldDefinitionQueries.findSelectableB2bCatalogEntries(30L, 6L)).thenReturn(emptyList())
        section.refresh(profile)

        val editButton = fieldRowActions(existingField).children.toList()
            .filterIsInstance<Button>()
            .first { it.element.getAttribute("aria-label") == UiTranslations.tr("referenceData.b2b.field.editAction") }
        editButton.click()

        assertTrue(popupDialog().isOpened, "the row's edit button must open the field editor popup")
        assertEquals(existingField.id, panel.currentField()?.id)
    }
}
