package de.littletrotter.ui.view.referencedata

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._clickItem
import com.github.mvysny.kaributesting.v10._doubleClickItem
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._setValue
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldDefinitionUseCase
import de.littletrotter.application.reference.port.inbound.B2bProfileQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bProfileUseCase
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.application.dto.SortDirection
import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.AbstractCrudView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * The field editor panel's "Vorgabewert" (default value) input is visible only for a
 * `FREE`, non-`DATE` term ([de.littletrotter.domain.reference.B2bProfileField
 * .assertDefaultValueCompatibleWithCatalog]'s KDoc spells out the DATE decision this mirrors):
 * hidden for a `BOUND` term (its value lives on the property it's bound to, no room for a
 * profile-level default) and for a `DATE`-kind term.
 *
 * Field maintenance (add/edit/delete, term selection, definition, requirement, condition,
 * placement, default value) lives in [B2bFieldEditorPanel] as a narrow popup [Dialog] the panel
 * itself owns and docks to the right edge, opened on demand instead of costing width permanently
 * (see [popupDialog]). The reflection helpers below reach into the [panel] instance directly —
 * same reflection idiom (this codebase has no `internal` visibility escape hatch for tests).
 *
 * There is no row-details panel: a row click only selects, only [profileRowActions]' buttons open
 * the editor dialog, and there is no double-click fallback either — see the "row click /
 * double-click" section below.
 */
class B2bProfilesTabKaribuTest {

    private val b2bProfileQueries: B2bProfileQueryPort = mock()

    private val manageB2bProfile: ManageB2bProfileUseCase = mock()
    private val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = mock()
    private val manageB2bFieldDefinition: ManageB2bFieldDefinitionUseCase = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
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

    private inline fun <reified T> privateField(view: B2bProfilesTab, name: String): T = field(view, name)

    /** The field editor panel (see the class KDoc); most of the reflection helpers below reach
     *  into this instance. */
    private fun panel(view: B2bProfilesTab): B2bFieldEditorPanel = privateField(view, "fieldEditorPanel")

    private fun privateMethod(target: Any, name: String, vararg paramTypes: Class<*>): java.lang.reflect.Method {
        val m = target.javaClass.getDeclaredMethod(name, *paramTypes)
        m.isAccessible = true
        return m
    }

    private fun profileRowActions(view: B2bProfilesTab, profile: B2bProfileDto): HorizontalLayout =
        privateMethod(view, "profileRowActions", B2bProfileDto::class.java).invoke(view, profile) as HorizontalLayout

    /** [AbstractCrudView.editorDialog] is declared on the superclass, so a plain
     *  `view.javaClass.getDeclaredField` (see [field]) would miss it — reflect via the superclass
     *  instead. Used to assert which entry points (row buttons only, no double-click) actually
     *  open the dialog. */
    private fun editorDialog(view: B2bProfilesTab): Dialog {
        val f = AbstractCrudView::class.java.getDeclaredField("editorDialog")
        f.isAccessible = true
        return f.get(view) as Dialog
    }

    /** Drives the profile editor dialog's own lifecycle hook directly (it is `protected
     *  override`, unreachable from this non-subclass test otherwise) instead of the full
     *  `AbstractCrudView.openEditor` flow, which is orthogonal to what these tests cover (the
     *  "Regeln" section's content, not the dialog chrome). */
    private fun invokeReadFormData(view: B2bProfilesTab, formData: B2bProfileFormData) {
        val m = B2bProfilesTab::class.java.getDeclaredMethod("readFormData", B2bProfileFormData::class.java)
        m.isAccessible = true
        m.invoke(view, formData)
    }

    /** The field editor panel's own save button, private like everything else this test
     *  reflects into. */
    private fun invokeSaveField(panel: B2bFieldEditorPanel) {
        privateMethod(panel, "saveField").invoke(panel)
    }

    /** The profile editor dialog's "Regeln" section, [B2bProfileRulesSection] (its own
     *  dedicated test, `B2bProfileRulesSectionKaribuTest`, covers its rendering directly); the
     *  tests remaining here only exercise the wiring between this tab and that composer. */
    private fun fieldsSection(view: B2bProfilesTab): B2bProfileRulesSection = privateField(view, "fieldsSection")

    private fun profileField(term: B2bTerm, requirement: B2bFieldRequirement, conditionTerm: B2bTerm? = null, defaultValue: String? = null) =
        B2bProfileFieldDto(
            term = term,
            requirement = requirement,
            conditionTerm = conditionTerm,
            businessDefinition = "Definition ${term.officialCode}",
            defaultValue = defaultValue,
        )

    private fun termCombo(panel: B2bFieldEditorPanel): ComboBox<B2bTerm> = field(panel, "termCombo")
    private fun termBadge(panel: B2bFieldEditorPanel): Span = field(panel, "termBadge")
    private fun defaultValueField(panel: B2bFieldEditorPanel): TextField = field(panel, "defaultValueField")
    private fun requirementCombo(panel: B2bFieldEditorPanel): ComboBox<B2bFieldRequirement> = field(panel, "requirementCombo")
    private fun conditionCombo(panel: B2bFieldEditorPanel): ComboBox<B2bProfileFieldDto> = field(panel, "conditionCombo")
    private fun businessDefinitionArea(panel: B2bFieldEditorPanel): TextArea = field(panel, "businessDefinitionArea")
    private fun cancelButton(panel: B2bFieldEditorPanel): Button = field(panel, "cancelButton")

    /** The right-docked popup [B2bFieldEditorPanel] itself owns and opens/closes via
     *  [B2bFieldEditorPanel.openFor]/[B2bFieldEditorPanel.showEmpty]. */
    private fun popupDialog(panel: B2bFieldEditorPanel): Dialog = field(panel, "popupDialog")

    /** ComboBox refuses `.value = ...` before its items are populated; every entry this test
     *  selects is added to the combo's item set first, mirroring what [B2bFieldEditorPanel
     *  .openFor] normally does via `termCombo.setItems(...)`. */
    private fun selectTerm(panel: B2bFieldEditorPanel, entry: B2bTerm?) {
        val combo = termCombo(panel)
        if (entry != null) combo.setItems(entry)
        combo.value = entry
    }

    @Test
    fun `the default value input is visible for a FREE, non-DATE term`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)

        selectTerm(panel(view), B2bTerm.BT_58)

        assertTrue(defaultValueField(panel(view)).isVisible)
    }

    @Test
    fun `the default value input is hidden for a BOUND term`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)

        selectTerm(panel(view), B2bTerm.BT_84)

        assertFalse(defaultValueField(panel(view)).isVisible)
    }

    @Test
    fun `the default value input is hidden for a DATE-kind term - v1 decision`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)

        // Every real DATE catalog term (BT-72/73/74/134/135) also happens to be BOUND today, so
        // this necessarily hides the widget for both reasons at once; there is no real FREE+DATE
        // term to isolate the DATE-only branch with (see B2bFieldEditorPanel's
        // syncDefaultValueVisibility, which checks binding == FREE && fieldKind != DATE).
        selectTerm(panel(view), B2bTerm.BT_72)

        assertFalse(defaultValueField(panel(view)).isVisible)
    }

    @Test
    fun `switching from a FREE term to a BOUND one clears a typed default value`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        val panel = panel(view)

        selectTerm(panel, B2bTerm.BT_58)
        defaultValueField(panel).value = "rechnung@nekar-supermarkt.de"

        val combo = termCombo(panel)
        combo.setItems(B2bTerm.BT_58, B2bTerm.BT_84)
        combo.value = B2bTerm.BT_84

        assertFalse(defaultValueField(panel).isVisible)
        assertEquals("", defaultValueField(panel).value, "a stale default must not survive switching to an ineligible term")
    }

    @Test
    fun `no term selected hides the default value input`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        val panel = panel(view)

        selectTerm(panel, B2bTerm.BT_58)
        selectTerm(panel, null)

        assertFalse(defaultValueField(panel).isVisible)
    }

    // ── CONDITIONAL_IF_EMPTY, the either-or/XOR requirement kind ────
    //
    // The requirement select's fourth option shares the same "Auslösender Term" combo as
    // CONDITIONAL — this coexists with the default-value input added above (a term can be
    // FREE + CONDITIONAL_IF_EMPTY at once, both controls visible together).

    @Test
    fun `the requirement combo lists all four kinds, CONDITIONAL_IF_EMPTY included`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)

        // Populated in B2bFieldEditorPanel's init (requirementCombo.setItems(B2bFieldRequirement
        // .entries)), before any term is even selected.
        val items = requirementCombo(panel(view)).dataProvider
            .fetch(com.vaadin.flow.data.provider.Query())
            .toList()

        assertEquals(B2bFieldRequirement.entries.toList(), items)
        assertTrue(B2bFieldRequirement.CONDITIONAL_IF_EMPTY in items)
    }

    @Test
    fun `selecting CONDITIONAL_IF_EMPTY reveals the condition combo, same as CONDITIONAL`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        val panel = panel(view)
        val requirement = requirementCombo(panel)
        val condition = conditionCombo(panel)

        requirement.value = B2bFieldRequirement.CONDITIONAL_IF_EMPTY
        assertTrue(condition.isVisible)

        requirement.value = B2bFieldRequirement.CONDITIONAL
        assertTrue(condition.isVisible, "CONDITIONAL keeps using the same combo")

        requirement.value = B2bFieldRequirement.OPTIONAL
        assertFalse(condition.isVisible)

        requirement.value = B2bFieldRequirement.MANDATORY
        assertFalse(condition.isVisible)
    }

    @Test
    fun `CONDITIONAL_IF_EMPTY and a FREE term's default value input coexist - the two settings do not conflict`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        val panel = panel(view)

        selectTerm(panel, B2bTerm.BT_58)
        requirementCombo(panel).value = B2bFieldRequirement.CONDITIONAL_IF_EMPTY

        assertTrue(defaultValueField(panel).isVisible, "the FREE term's default-value input stays visible regardless of requirement kind")
        assertTrue(conditionCombo(panel).isVisible)
    }

    @Test
    fun `the profile grid's row actions include a copy button next to edit`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        val profile = B2bProfileDto(id = 1L, name = "Kunde XY")

        val actions = profileRowActions(view, profile)

        val ariaLabels = actions.children.toList().filterIsInstance<Button>().map { it.element.getAttribute("aria-label") }
        assertTrue(ariaLabels.contains(UiTranslations.tr("referenceData.b2b.profile.editAction")))
        assertTrue(ariaLabels.contains(UiTranslations.tr("referenceData.b2b.profile.copyAction")), "copy action must be reachable from the profile grid row, not only the expanded panel")
    }

    // ── Rule maintenance lives in the profile editor dialog ────────
    //
    // The fields grid, its "Feld hinzufügen"/edit/delete actions, and the field editor panel live
    // in the profile editor dialog's "Regeln" section (readFormData → fieldsSection.refresh) —
    // the only place a profile's fields are ever shown (there is no row-expansion panel, see the
    // "row click / double-click" section below).

    @Test
    fun `saving a field from within the popup refreshes the dialog's own Regeln grid and closes the popup`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        val profile = B2bProfileDto(id = 9L, name = "Kunde CD")
        whenever(b2bProfileQueries.findB2bProfileById(9L)).thenReturn(profile)
        whenever(b2bFieldDefinitionQueries.findB2bProfileFields(9L)).thenReturn(emptyList())
        invokeReadFormData(view, B2bProfileFormData(id = 9L, name = "Kunde CD"))

        val entry = B2bTerm.BT_58
        whenever(b2bFieldDefinitionQueries.findSelectableB2bCatalogEntries(9L, null)).thenReturn(listOf(entry))
        val panel = panel(view)
        panel.openFor(profile, null)
        selectTerm(panel, entry)
        assertTrue(popupDialog(panel).isOpened, "openFor must have opened the popup")

        // The facade now reports one saved field — the Regeln grid must reflect it once the popup
        // closes (save closes the popup, it no longer stays open reloaded).
        whenever(b2bFieldDefinitionQueries.findB2bProfileFields(9L)).thenReturn(listOf(profileField(B2bTerm.BT_58, B2bFieldRequirement.OPTIONAL)))

        invokeSaveField(panel)

        assertFalse(popupDialog(panel).isOpened, "ARC-15 point 12 — Speichern closes the popup")
        val content = fieldsSection(view)
        assertTrue(
            content.children.toList().any { it is DataTable<*> },
            "the profile dialog's Regeln grid must refresh after the popup's field save",
        )
    }

    // ── Field editor as a right-docked popup Dialog, not a permanent side panel ──

    @Test
    fun `the field editor panel's popup carries the right-docking theme class`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)

        val popup = popupDialog(panel(view))

        assertTrue(
            popup.element.themeList.contains("b2b-field-popup"),
            "styles.css docks the popup via this theme name, mirrored onto vaadin-dialog-overlay",
        )
    }

    @Test
    fun `cancelling the popup discards edits and closes it without confirmation`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        val profile = B2bProfileDto(id = 32L, name = "Kunde UV")
        val panel = panel(view)
        whenever(b2bFieldDefinitionQueries.findSelectableB2bCatalogEntries(32L, null)).thenReturn(emptyList())
        panel.openFor(profile, null)
        businessDefinitionArea(panel).value = "nicht gespeicherter Text"
        assertTrue(panel.isDirty())

        cancelButton(panel).click()

        assertFalse(popupDialog(panel).isOpened, "Abbrechen must close the popup")
        assertFalse(panel.isDirty())
    }

    // ── No inline detail area; only the row buttons open the dialog ──────────
    //
    // The row-details panel is gone structurally, not merely hidden: there is no
    // more `setItemDetailsRenderer`/`setDetailsVisibleOnClick` wiring on the grid at all. A row
    // click only selects, and -- per the explicit requirement that only the action buttons may
    // open the editor -- a double-click no longer opens it either, unlike every other
    // reference-data tab's shared `AbstractCrudView.wireEditorRowActions` wiring (which this tab
    // deliberately bypasses).

    @Test
    fun `a row click only selects - no inline detail area opens`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        val profile = B2bProfileDto(id = 20L, name = "Kunde IJ")
        val grid: DataTable<B2bProfileDto> = privateField(view, "profileGrid")
        grid.setItems(profile)

        grid._clickItem(0)

        assertEquals(profile, grid.asSingleSelect().value, "a row click still selects")
        assertFalse(grid.isDetailsVisible(profile), "no inline detail area may open on a row click anymore")
        assertFalse(editorDialog(view).isOpened, "a plain row click must not open the dialog either")
    }

    @Test
    fun `the row's edit button opens the workspace dialog - the only way in now`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        val profile = B2bProfileDto(id = 21L, name = "Kunde KL")
        whenever(b2bProfileQueries.findB2bProfileById(21L)).thenReturn(profile)
        whenever(b2bFieldDefinitionQueries.findB2bProfileFields(21L)).thenReturn(emptyList())

        val editButton = profileRowActions(view, profile).children.toList()
            .filterIsInstance<Button>()
            .first { it.element.getAttribute("aria-label") == UiTranslations.tr("referenceData.b2b.profile.editAction") }
        editButton.click()

        assertTrue(editorDialog(view).isOpened, "the row's edit button must still open the dialog")
    }

    @Test
    fun `a double click on the row no longer opens the dialog - only the buttons do`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        val profile = B2bProfileDto(id = 22L, name = "Kunde MN")
        val grid: DataTable<B2bProfileDto> = privateField(view, "profileGrid")
        grid.setItems(profile)

        grid._doubleClickItem(0)

        assertFalse(editorDialog(view).isOpened, "PO: ausschließlich die Aktions-Buttons öffnen den Dialog")
    }

    @Test
    fun `reopening the dialog resets the field editor panel to its empty placeholder`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        val profile = B2bProfileDto(id = 9L, name = "Kunde CD")
        whenever(b2bProfileQueries.findB2bProfileById(9L)).thenReturn(profile)
        whenever(b2bFieldDefinitionQueries.findB2bProfileFields(9L)).thenReturn(listOf(profileField(B2bTerm.BT_58, B2bFieldRequirement.OPTIONAL)))
        whenever(b2bFieldDefinitionQueries.findSelectableB2bCatalogEntries(9L, null)).thenReturn(emptyList())
        invokeReadFormData(view, B2bProfileFormData(id = 9L, name = "Kunde CD"))

        val panel = panel(view)
        panel.openFor(profile, null)
        assertEquals(profile.id, field<B2bProfileDto?>(panel, "profile")?.id)

        // Reopening (a second readFormData, as AbstractCrudView.openEditor does on every open)
        // must show fresh state — the panel must not still be showing the previous field/create form.
        invokeReadFormData(view, B2bProfileFormData(id = 9L, name = "Kunde CD"))

        assertEquals(null, panel.currentField())
        assertFalse(panel.isDirty())
    }

    @Test
    fun `openFor refuses to switch fields while the panel has unsaved edits, unless forced`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        val profile = B2bProfileDto(id = 9L, name = "Kunde CD")
        val fieldA = profileField(B2bTerm.BT_19, B2bFieldRequirement.OPTIONAL).copy(id = 1L)
        val fieldB = profileField(B2bTerm.BT_56, B2bFieldRequirement.OPTIONAL).copy(id = 2L)
        whenever(b2bFieldDefinitionQueries.findSelectableB2bCatalogEntries(9L, 1L)).thenReturn(emptyList())
        whenever(b2bFieldDefinitionQueries.findSelectableB2bCatalogEntries(9L, 2L)).thenReturn(emptyList())
        whenever(b2bFieldDefinitionQueries.findB2bProfileFields(9L)).thenReturn(listOf(fieldA, fieldB))

        val panel = panel(view)
        assertTrue(panel.openFor(profile, fieldA))

        businessDefinitionArea(panel).value = "geänderter Text"
        assertTrue(panel.isDirty())

        val switched = panel.openFor(profile, fieldB)
        assertFalse(switched, "must refuse to discard unsaved edits without force = true")
        assertEquals(fieldA.id, panel.currentField()?.id, "still showing the original field")

        assertTrue(panel.openFor(profile, fieldB, force = true), "force = true discards and switches")
        assertEquals(fieldB.id, panel.currentField()?.id)
        assertFalse(panel.isDirty())
    }

    @Test
    fun `editing an existing field shows the term as a header badge, not an input`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        val profile = B2bProfileDto(id = 9L, name = "Kunde CD")
        val existingField = profileField(B2bTerm.BT_19, B2bFieldRequirement.OPTIONAL).copy(id = 5L)
        val entry = B2bTerm.BT_19
        whenever(b2bFieldDefinitionQueries.findSelectableB2bCatalogEntries(9L, 5L)).thenReturn(listOf(entry))
        whenever(b2bFieldDefinitionQueries.findB2bProfileFields(9L)).thenReturn(listOf(existingField))

        val panel = panel(view)
        assertTrue(panel.openFor(profile, existingField))

        assertFalse(termCombo(panel).isVisible, "the term is fixed once a field exists — no input")
        assertTrue(termBadge(panel).isVisible)
        assertTrue(termBadge(panel).text.contains("BT-19"))
        assertTrue(termBadge(panel).text.contains("Buchungsreferenz"), "the badge names the term, not just its code")
    }

    @Test
    fun `picking a term while creating a field also switches to the badge presentation`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        val panel = panel(view)
        val entry = B2bTerm.BT_58

        selectTerm(panel, entry)

        assertFalse(termCombo(panel).isVisible, "R7 — once a term is chosen it stops being an input, even mid-create")
        assertTrue(termBadge(panel).isVisible)
        assertTrue(termBadge(panel).text.contains("BT-58"))
    }

    // ── Footer (Speichern/Abbrechen/Löschen) pinned outside the scroll area ───

    @Test
    fun `the action footer lives in the dialog footer, outside the body and its scrolling content area`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)

        val dialog = editorDialog(view)
        val body = dialog.children.toList().first { it.element.classList.contains("b2b-editor-body") }
        fun buttonsIn(c: com.vaadin.flow.component.Component): List<String> =
            if (c is com.vaadin.flow.component.button.Button) listOf(c.text) else c.children.toList().flatMap { buttonsIn(it) }
        val footerButtons = dialog.footer.element.children.toList()
            .flatMap { e -> e.component.map { buttonsIn(it) }.orElse(emptyList()) }

        assertEquals(
            listOf(UiTranslations.tr("app.delete"), UiTranslations.tr("app.cancel"), UiTranslations.tr("app.save")),
            footerButtons,
            "delete leads, cancel precedes the primary save",
        )
        assertFalse(body.children.toList().any { it.element.classList.contains("b2b-editor-footer") }, "no footer inside the dialog body")
    }

    // ── Placement ComboBox order: Rechnung, Kunde, Anbieter ────────────────────────────

    @Test
    fun `the placement ComboBox lists Rechnung, Kunde, Anbieter in that order`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        val profile = B2bProfileDto(id = 9L, name = "Kunde CD")
        whenever(b2bProfileQueries.findB2bProfileById(9L)).thenReturn(profile)
        whenever(b2bFieldDefinitionQueries.findB2bProfileFields(9L)).thenReturn(emptyList())
        invokeReadFormData(view, B2bProfileFormData(id = 9L, name = "Kunde CD"))
        whenever(b2bFieldDefinitionQueries.findSelectableB2bCatalogEntries(9L, null)).thenReturn(emptyList())
        val panel = panel(view)
        panel.openFor(profile, null)

        val placementCombo = field<ComboBox<de.littletrotter.domain.reference.B2bPlacement>>(panel, "placementCombo")
        val labels = placementCombo.dataProvider.fetch(com.vaadin.flow.data.provider.Query()).toList()
            .map { placementCombo.getItemLabelGenerator().apply(it) }

        assertEquals(
            listOf(
                UiTranslations.tr("referenceData.b2b.field.placement.invoice"),
                UiTranslations.tr("referenceData.b2b.field.placement.recipient"),
                UiTranslations.tr("referenceData.b2b.field.placement.seller"),
            ),
            labels,
            "ARC-23 decision 3: Rechnung zuerst, Kunde als zweites, Anbieter zuletzt",
        )
    }

    @Test
    fun `RECIPIENT is a selectable placement for a HEADER-level term, same as SELLER`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        val profile = B2bProfileDto(id = 9L, name = "Kunde CD")
        whenever(b2bProfileQueries.findB2bProfileById(9L)).thenReturn(profile)
        whenever(b2bFieldDefinitionQueries.findB2bProfileFields(9L)).thenReturn(emptyList())
        invokeReadFormData(view, B2bProfileFormData(id = 9L, name = "Kunde CD"))
        val entry = B2bTerm.BT_58
        whenever(b2bFieldDefinitionQueries.findSelectableB2bCatalogEntries(9L, null)).thenReturn(listOf(entry))
        val panel = panel(view)
        panel.openFor(profile, null)
        selectTerm(panel, entry)

        val placementCombo = field<ComboBox<de.littletrotter.domain.reference.B2bPlacement>>(panel, "placementCombo")
        placementCombo.value = de.littletrotter.domain.reference.B2bPlacement.RECIPIENT

        assertEquals(de.littletrotter.domain.reference.B2bPlacement.RECIPIENT, placementCombo.value)
        assertFalse(placementCombo.isReadOnly, "a HEADER-level term keeps the placement choice open, unlike a LINE term")
    }

    // ── UI narrowing: the placement choice offered must only ever contain the placements
    // meaningful for the selected term's B2bTermLevel ────

    private fun placementItems(panel: B2bFieldEditorPanel): List<de.littletrotter.domain.reference.B2bPlacement> {
        val combo = field<ComboBox<de.littletrotter.domain.reference.B2bPlacement>>(panel, "placementCombo")
        return combo.dataProvider.fetch(com.vaadin.flow.data.provider.Query()).toList()
    }

    private fun placementCombo(panel: B2bFieldEditorPanel): ComboBox<de.littletrotter.domain.reference.B2bPlacement> =
        field(panel, "placementCombo")

    @Test
    fun `a LINE-level term narrows the placement choice to Rechnung only, preselected and disabled`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        val profile = B2bProfileDto(id = 60L, name = "Kunde LINE")
        whenever(b2bProfileQueries.findB2bProfileById(60L)).thenReturn(profile)
        whenever(b2bFieldDefinitionQueries.findB2bProfileFields(60L)).thenReturn(emptyList())
        invokeReadFormData(view, B2bProfileFormData(id = 60L, name = "Kunde LINE"))
        whenever(b2bFieldDefinitionQueries.findSelectableB2bCatalogEntries(60L, null)).thenReturn(listOf(B2bTerm.BT_155))
        val panel = panel(view)
        panel.openFor(profile, null)

        selectTerm(panel, B2bTerm.BT_155) // LINE, FREE

        assertEquals(listOf(de.littletrotter.domain.reference.B2bPlacement.INVOICE), placementItems(panel), "a LINE term must only ever offer Rechnung")
        assertEquals(de.littletrotter.domain.reference.B2bPlacement.INVOICE, placementCombo(panel).value)
        assertTrue(placementCombo(panel).isReadOnly, "the combo must be locked once only one placement is meaningful")
        assertEquals(
            UiTranslations.tr("referenceData.b2b.field.placement.helper.line"),
            placementCombo(panel).helperText,
            "the helper text must explain why the choice is locked",
        )
    }

    @Test
    fun `a SELLER_PARTY-level term narrows the placement choice to Anbieter only, preselected and disabled - EP6-F5`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        val profile = B2bProfileDto(id = 61L, name = "Kunde SELLER_PARTY")
        whenever(b2bProfileQueries.findB2bProfileById(61L)).thenReturn(profile)
        whenever(b2bFieldDefinitionQueries.findB2bProfileFields(61L)).thenReturn(emptyList())
        invokeReadFormData(view, B2bProfileFormData(id = 61L, name = "Kunde SELLER_PARTY"))
        whenever(b2bFieldDefinitionQueries.findSelectableB2bCatalogEntries(61L, null)).thenReturn(listOf(B2bTerm.BT_84))
        val panel = panel(view)
        panel.openFor(profile, null)

        selectTerm(panel, B2bTerm.BT_84) // SELLER_PARTY, BOUND (iban)

        assertEquals(listOf(de.littletrotter.domain.reference.B2bPlacement.SELLER), placementItems(panel), "a SELLER_PARTY term must only ever offer Anbieter (EP6-F5)")
        assertEquals(de.littletrotter.domain.reference.B2bPlacement.SELLER, placementCombo(panel).value)
        assertTrue(placementCombo(panel).isReadOnly, "the combo must be locked once only one placement is meaningful")
        assertEquals(
            UiTranslations.tr("referenceData.b2b.field.placement.helper.sellerParty"),
            placementCombo(panel).helperText,
            "the helper text must explain why the choice is locked to Anbieter",
        )
    }

    @Test
    fun `a HEADER-level term keeps the full three-way placement choice, unlocked`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        val profile = B2bProfileDto(id = 62L, name = "Kunde HEADER")
        whenever(b2bProfileQueries.findB2bProfileById(62L)).thenReturn(profile)
        whenever(b2bFieldDefinitionQueries.findB2bProfileFields(62L)).thenReturn(emptyList())
        invokeReadFormData(view, B2bProfileFormData(id = 62L, name = "Kunde HEADER"))
        whenever(b2bFieldDefinitionQueries.findSelectableB2bCatalogEntries(62L, null)).thenReturn(listOf(B2bTerm.BT_58))
        val panel = panel(view)
        panel.openFor(profile, null)

        selectTerm(panel, B2bTerm.BT_58) // HEADER, FREE

        assertEquals(
            listOf(
                de.littletrotter.domain.reference.B2bPlacement.INVOICE,
                de.littletrotter.domain.reference.B2bPlacement.RECIPIENT,
                de.littletrotter.domain.reference.B2bPlacement.SELLER,
            ),
            placementItems(panel),
            "a HEADER term keeps the full, freely combinable placement choice",
        )
        assertFalse(placementCombo(panel).isReadOnly)
        assertEquals(null, placementCombo(panel).helperText, "no lock, no explanatory helper text needed")
    }

    @Test
    fun `switching from a LINE term back to a HEADER term restores the full placement choice`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        val profile = B2bProfileDto(id = 63L, name = "Kunde Wechsel")
        whenever(b2bProfileQueries.findB2bProfileById(63L)).thenReturn(profile)
        whenever(b2bFieldDefinitionQueries.findB2bProfileFields(63L)).thenReturn(emptyList())
        invokeReadFormData(view, B2bProfileFormData(id = 63L, name = "Kunde Wechsel"))
        whenever(b2bFieldDefinitionQueries.findSelectableB2bCatalogEntries(63L, null)).thenReturn(listOf(B2bTerm.BT_155, B2bTerm.BT_58))
        val panel = panel(view)
        panel.openFor(profile, null)

        selectTerm(panel, B2bTerm.BT_155)
        assertTrue(placementCombo(panel).isReadOnly)

        val combo = termCombo(panel)
        combo.setItems(B2bTerm.BT_155, B2bTerm.BT_58)
        combo.value = B2bTerm.BT_58

        assertFalse(placementCombo(panel).isReadOnly, "reacting to the term change, same as the other dynamic parts of the dialog")
        assertEquals(3, placementItems(panel).size)
    }

    // ── activeField's deactivate-confirm listener (isFromClient guard, both
    //    early-return directions, and the confirm/cancel dialog) ───────────────────────────────

    private fun activeField(view: B2bProfilesTab): Checkbox = privateField(view, "activeField")

    private fun setCurrentFormData(view: B2bProfilesTab, formData: B2bProfileFormData) {
        val f = B2bProfilesTab::class.java.getDeclaredField("currentFormData")
        f.isAccessible = true
        f.set(view, formData)
    }

    @Test
    fun `a programmatic active-checkbox change opens no confirm dialog - not from a real client`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        setCurrentFormData(view, B2bProfileFormData(id = 70L))
        activeField(view).value = true // baseline — Checkbox already defaults to false

        activeField(view).value = false // plain server-side set, isFromClient = false

        assertTrue(_find<ConfirmDialog>().isEmpty())
    }

    @Test
    fun `re-checking active as a real client action opens no confirm dialog - only deactivation warns`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        setCurrentFormData(view, B2bProfileFormData(id = 71L))
        activeField(view).value = false

        activeField(view)._setValue(true)

        assertTrue(_find<ConfirmDialog>().isEmpty(), "checking the box back on must never prompt")
    }

    @Test
    fun `deactivating a profile with no id yet opens no confirm dialog`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        setCurrentFormData(view, B2bProfileFormData(id = null))
        activeField(view).value = true // baseline — Checkbox already defaults to false

        activeField(view)._setValue(false)

        assertTrue(_find<ConfirmDialog>().isEmpty(), "a new, unsaved profile has nothing to warn about")
    }

    @Test
    fun `deactivating a profile nobody uses opens no confirm dialog`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        setCurrentFormData(view, B2bProfileFormData(id = 72L))
        whenever(b2bProfileQueries.findB2bProfileUsageCounts()).thenReturn(mapOf(72L to 0L))
        activeField(view).value = true // baseline — Checkbox already defaults to false

        activeField(view)._setValue(false)

        assertTrue(_find<ConfirmDialog>().isEmpty())
    }

    @Test
    fun `deactivating a profile in use warns with the usage count and reverts the checkbox on cancel`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        setCurrentFormData(view, B2bProfileFormData(id = 73L))
        whenever(b2bProfileQueries.findB2bProfileUsageCounts()).thenReturn(mapOf(73L to 3L))
        activeField(view).value = true // baseline — Checkbox already defaults to false

        activeField(view)._setValue(false)

        val dlg = _find<ConfirmDialog>().single()
        assertEquals(
            UiTranslations.tr("referenceData.b2b.profile.deactivateConfirm.title"),
            dlg.element.getProperty("header"),
        )
        dlg.fireCancel()
        assertTrue(activeField(view).value, "cancelling the warning must revert the checkbox back to active")
    }

    /** [ConfirmDialog]'s cancel/confirm listeners have no click-driven public API in a headless
     *  test (same limitation documented on
     *  [de.littletrotter.ui.view.invoice.InvoiceDetailViewOverviewEntryPaneTest]) — its own
     *  [ConfirmDialog.CancelEvent] constructor and [com.vaadin.flow.component.ComponentUtil
     *  .fireEvent] are both public, so no reflection is needed to dispatch it. */
    private fun ConfirmDialog.fireCancel() {
        com.vaadin.flow.component.ComponentUtil.fireEvent(this, ConfirmDialog.CancelEvent(this, false))
    }

    // ── copyProfile: happy path, no-id guard, facade exception ──────────────────

    private fun invokeCopyProfile(view: B2bProfilesTab, profile: B2bProfileDto) {
        privateMethod(view, "copyProfile", B2bProfileDto::class.java).invoke(view, profile)
    }

    @Test
    fun `copying a profile with no id is a no-op - nothing to copy server-side yet`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)

        invokeCopyProfile(view, B2bProfileDto(id = null))

        verify(manageB2bProfile, never()).copyB2bProfile(org.mockito.kotlin.any())
    }

    @Test
    fun `copying a profile selects the new row and opens its editor`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        val original = B2bProfileDto(id = 80L, name = "Kunde Original")
        val copy = B2bProfileDto(id = 81L, name = "Kunde Original (Kopie)")
        whenever(manageB2bProfile.copyB2bProfile(80L)).thenReturn(copy)
        whenever(b2bProfileQueries.findB2bProfileById(81L)).thenReturn(copy)
        whenever(b2bFieldDefinitionQueries.findB2bProfileFields(81L)).thenReturn(emptyList())

        invokeCopyProfile(view, original)

        assertTrue(editorDialog(view).isOpened, "copying must open the new profile's editor right away")
    }

    @Test
    fun `a collision while copying surfaces an error instead of crashing`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        val original = B2bProfileDto(id = 82L, name = "Kunde Original")
        whenever(manageB2bProfile.copyB2bProfile(82L)).thenThrow(InvalidParameterException("name duplicate"))

        invokeCopyProfile(view, original)

        assertFalse(editorDialog(view).isOpened, "on error the editor must not open")
    }

    // ── deleteEntityById: clears the popup's selectedProfile only on an id match ──

    private fun invokeDeleteEntityById(view: B2bProfilesTab, id: Long) {
        val m = B2bProfilesTab::class.java.getDeclaredMethod("deleteEntityById", Long::class.javaPrimitiveType)
        m.isAccessible = true
        m.invoke(view, id)
    }

    @Test
    fun `deleting the currently selected profile deselects the grid and clears selectedProfile`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        val profile = B2bProfileDto(id = 98L, name = "Kunde Del")
        whenever(b2bProfileQueries.findB2bProfileById(98L)).thenReturn(profile)
        whenever(b2bFieldDefinitionQueries.findB2bProfileFields(98L)).thenReturn(emptyList())
        invokeReadFormData(view, B2bProfileFormData(id = 98L, name = "Kunde Del"))

        invokeDeleteEntityById(view, 98L)

        verify(manageB2bProfile).deleteB2bProfile(98L)
        assertNull(privateField<B2bProfileDto?>(view, "selectedProfile"))
    }

    @Test
    fun `deleting a profile other than the one currently selected leaves selectedProfile untouched`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        val profile = B2bProfileDto(id = 99L, name = "Kunde Del2")
        whenever(b2bProfileQueries.findB2bProfileById(99L)).thenReturn(profile)
        whenever(b2bFieldDefinitionQueries.findB2bProfileFields(99L)).thenReturn(emptyList())
        invokeReadFormData(view, B2bProfileFormData(id = 99L, name = "Kunde Del2"))

        invokeDeleteEntityById(view, 12345L)

        verify(manageB2bProfile).deleteB2bProfile(12345L)
        assertEquals(99L, privateField<B2bProfileDto?>(view, "selectedProfile")?.id)
    }

    @Test
    fun `deleting a profile while none is currently selected does not crash and leaves selectedProfile null`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        // No invokeReadFormData call — the dialog was never opened, selectedProfile stays null.

        invokeDeleteEntityById(view, 100L)

        verify(manageB2bProfile).deleteB2bProfile(100L)
        assertNull(privateField<B2bProfileDto?>(view, "selectedProfile"))
    }

    // ── buildProfileDataProvider's page-index arithmetic (limit == 0 edge) ───────

    @Test
    fun `the profile data provider treats a zero page size as page zero instead of dividing by zero`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        whenever(b2bProfileQueries.findB2bProfiles(eq(0), eq(0), anyOrNull(), any())).thenReturn(emptyList())
        val provider: com.vaadin.flow.data.provider.CallbackDataProvider<B2bProfileDto, Void?> = privateField(view, "profileDataProvider")

        provider.fetch(com.vaadin.flow.data.provider.Query(0, 0, emptyList(), null, null)).toList()

        verify(b2bProfileQueries).findB2bProfiles(eq(0), eq(0), org.mockito.kotlin.anyOrNull(), any())
    }

    @Test
    fun `the profile data provider computes the page index from a non-zero page size`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        whenever(b2bProfileQueries.findB2bProfiles(eq(2), eq(10), anyOrNull(), any())).thenReturn(emptyList())
        val provider: com.vaadin.flow.data.provider.CallbackDataProvider<B2bProfileDto, Void?> = privateField(view, "profileDataProvider")

        provider.fetch(com.vaadin.flow.data.provider.Query(20, 10, emptyList(), null, null)).toList()

        verify(b2bProfileQueries).findB2bProfiles(eq(2), eq(10), org.mockito.kotlin.anyOrNull(), any())
    }

    // ── The sortable name column's direction reaches the query ────────────────────────────

    @Test
    fun `the profile data provider passes a descending name sort on to the facade`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        whenever(b2bProfileQueries.findB2bProfiles(any(), any(), anyOrNull(), any())).thenReturn(emptyList())
        val provider: com.vaadin.flow.data.provider.CallbackDataProvider<B2bProfileDto, Void?> = privateField(view, "profileDataProvider")
        val descending = com.vaadin.flow.data.provider.QuerySortOrder.desc("name").build()

        provider.fetch(com.vaadin.flow.data.provider.Query(0, 10, descending, null, null)).toList()

        verify(b2bProfileQueries).findB2bProfiles(eq(0), eq(10), anyOrNull(), eq(SortDirection.DESC))
    }

    @Test
    fun `the profile data provider sorts ascending for an ascending and for an absent sort order`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        whenever(b2bProfileQueries.findB2bProfiles(any(), any(), anyOrNull(), any())).thenReturn(emptyList())
        val provider: com.vaadin.flow.data.provider.CallbackDataProvider<B2bProfileDto, Void?> = privateField(view, "profileDataProvider")
        val ascending = com.vaadin.flow.data.provider.QuerySortOrder.asc("name").build()

        provider.fetch(com.vaadin.flow.data.provider.Query(0, 10, ascending, null, null)).toList()
        provider.fetch(com.vaadin.flow.data.provider.Query(0, 10, emptyList(), null, null)).toList()

        verify(b2bProfileQueries, org.mockito.kotlin.times(2)).findB2bProfiles(eq(0), eq(10), anyOrNull(), eq(SortDirection.ASC))
    }

    // ── buildProfileDataProvider's item-identity resolver (getId) ─────────────────

    @Test
    fun `the profile data provider resolves item identity from the profile's own id`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        val provider: com.vaadin.flow.data.provider.CallbackDataProvider<B2bProfileDto, Void?> = privateField(view, "profileDataProvider")
        val profile = B2bProfileDto(id = 55L)

        assertEquals(55L, provider.getId(profile))
    }

    @Test
    fun `the profile data provider falls back to -1 identity for a not-yet-persisted profile`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        val provider: com.vaadin.flow.data.provider.CallbackDataProvider<B2bProfileDto, Void?> = privateField(view, "profileDataProvider")
        val profile = B2bProfileDto(id = null)

        assertEquals(-1L, provider.getId(profile))
    }

    // ── refreshProfileGrid's counterSpan guard: both the not-yet-installed and the
    //    already-installed direction ─────────────────────────────────────────────────────────────

    private fun counterSpan(view: B2bProfilesTab): Span {
        val f = AbstractReferencedataCrudTab::class.java.getDeclaredField("counterSpan")
        f.isAccessible = true
        return f.get(view) as Span
    }

    private fun invokeRefreshProfileGrid(view: B2bProfilesTab) {
        val m = B2bProfilesTab::class.java.getDeclaredMethod("refreshProfileGrid")
        m.isAccessible = true
        m.invoke(view)
    }

    @Test
    fun `refreshing the grid after the header counter is installed updates its text`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        whenever(b2bProfileQueries.countB2bProfiles(null)).thenReturn(5L)
        whenever(b2bProfileQueries.countInactiveB2bProfiles(null)).thenReturn(2L)
        view.installHeaderActions(com.vaadin.flow.component.html.Div())

        invokeRefreshProfileGrid(view)

        assertEquals(UiTranslations.tr("referenceData.counter.withInactive", 5L, 2L), counterSpan(view).text)
    }
}
