package de.littletrotter.ui.view.referencedata

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._getRenderedComponentFor
import com.github.mvysny.kaributesting.v10.getSuggestionItems
import com.github.mvysny.kaributesting.v10.setUserInput
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldDefinitionUseCase
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.shared.b2b.B2bTermBinding
import de.littletrotter.shared.b2b.B2bTermLevel
import de.littletrotter.shared.b2b.B2bTermTexts
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Locale

/**
 * Dedicated test for [B2bFieldEditorPanel] — previously only exercised indirectly through
 * [B2bProfilesTabKaribuTest] (which drives it via [B2bProfilesTab]'s own reflection helpers).
 * This suite instantiates the panel directly, without a hosting [B2bProfilesTab], so every branch
 * of its own filtering ([applyTermFilters]), presentation ([syncTermPresentation],
 * [B2bFieldEditorPanel.updateSpecInfoblock]), and save/delete wiring can be pinned without the
 * indirection.
 */
class B2bFieldEditorPanelKaribuTest {

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

    private var savedProfile: B2bProfileDto? = null
    private var deleteRequested: B2bProfileFieldDto? = null

    private fun newPanel(): B2bFieldEditorPanel =
        B2bFieldEditorPanel(b2bFieldDefinitionQueries, manageB2bFieldDefinition, { savedProfile = it }, { deleteRequested = it })

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

    private fun invokeSaveField(panel: B2bFieldEditorPanel) {
        privateMethod(panel, "saveField").invoke(panel)
    }

    private fun invokeBuildTermSuggestion(panel: B2bFieldEditorPanel, term: B2bTerm): VerticalLayout =
        privateMethod(panel, "buildTermSuggestion", B2bTerm::class.java).invoke(panel, term) as VerticalLayout

    private fun levelFilterCombo(panel: B2bFieldEditorPanel): ComboBox<B2bTermLevel> = field(panel, "levelFilterCombo")
    private fun bindingFilterCombo(panel: B2bFieldEditorPanel): ComboBox<B2bTermBinding> = field(panel, "bindingFilterCombo")
    private fun termCombo(panel: B2bFieldEditorPanel): ComboBox<B2bTerm> = field(panel, "termCombo")
    private fun termBadge(panel: B2bFieldEditorPanel): Span = field(panel, "termBadge")
    private fun englishNameInfo(panel: B2bFieldEditorPanel): Span = field(panel, "englishNameInfo")
    private fun autoFilledBadge(panel: B2bFieldEditorPanel): Span = field(panel, "autoFilledBadge")
    private fun allTermsUsedInfo(panel: B2bFieldEditorPanel): Span = field(panel, "allTermsUsedInfo")
    private fun specInfoblock(panel: B2bFieldEditorPanel): Div = field(panel, "specInfoblock")
    private fun businessDefinitionArea(panel: B2bFieldEditorPanel): TextArea = field(panel, "businessDefinitionArea")
    private fun requirementCombo(panel: B2bFieldEditorPanel): ComboBox<B2bFieldRequirement> = field(panel, "requirementCombo")
    private fun conditionCombo(panel: B2bFieldEditorPanel): ComboBox<B2bProfileFieldDto> = field(panel, "conditionCombo")
    private fun placementCombo(panel: B2bFieldEditorPanel): ComboBox<B2bPlacement> = field(panel, "placementCombo")
    private fun defaultValueField(panel: B2bFieldEditorPanel): TextField = field(panel, "defaultValueField")
    private fun saveButton(panel: B2bFieldEditorPanel): Button = field(panel, "saveButton")
    private fun cancelButton(panel: B2bFieldEditorPanel): Button = field(panel, "cancelButton")
    private fun deleteButton(panel: B2bFieldEditorPanel): Button = field(panel, "deleteButton")
    private fun popupDialog(panel: B2bFieldEditorPanel): Dialog = field(panel, "popupDialog")

    /** Filtering here goes through [B2bFieldEditorPanel.applyTermFilters]'s
     *  `ComboBox.setItems(ItemFilter, Collection)` overload — the underlying raw data provider's
     *  filter type is `SerializablePredicate<B2bTerm>`, converted from the combo's typed text by
     *  Vaadin internally, so a plain `dataProvider.fetch(Query(..., "text"))` (which works for the
     *  raw-`String`-filter `CallbackDataProvider` in [B2bFieldWidgetFactoryTest]) throws a
     *  `ClassCastException` here. Worse, the item filter never even runs until *some* non-null
     *  filter text has actually round-tripped through the client-side filter slot at least once —
     *  `ListDataProvider.fetch` skips the whole predicate (level/binding narrowing included) when
     *  the query carries no filter object at all, which is exactly the state right after
     *  `setItems(ItemFilter, Collection)` until a filter text is sent. Karibu-Testing's
     *  `setUserInput`/`getSuggestionItems` emulate that client round-trip — an explicit `""` (never
     *  `null`, which the underlying filter slot cannot handle) reproduces "the user opened the
     *  dropdown having typed nothing", after which the level/binding predicate legitimately runs. */
    private fun visibleTerms(panel: B2bFieldEditorPanel, filter: String = ""): List<B2bTerm> {
        val combo = termCombo(panel)
        combo.setUserInput(filter)
        return combo.getSuggestionItems()
    }

    private fun profile(id: Long = 1L) = B2bProfileDto(id = id, name = "Kunde")

    private fun profileField(
        term: B2bTerm?,
        requirement: B2bFieldRequirement = B2bFieldRequirement.OPTIONAL,
        id: Long? = null,
        conditionTerm: B2bTerm? = null,
        defaultValue: String? = null,
    ) = B2bProfileFieldDto(
        id = id,
        term = term,
        requirement = requirement,
        conditionTerm = conditionTerm,
        defaultValue = defaultValue,
        businessDefinition = term?.let { "Definition ${it.officialCode}" }.orEmpty(),
    )

    private fun stubOpen(
        profile: B2bProfileDto,
        field: B2bProfileFieldDto?,
        selectable: List<B2bTerm>,
        siblings: List<B2bProfileFieldDto> = emptyList(),
    ) {
        whenever(b2bFieldDefinitionQueries.findSelectableB2bCatalogEntries(profile.id, field?.id)).thenReturn(selectable)
        whenever(b2bFieldDefinitionQueries.findB2bProfileFields(profile.id!!)).thenReturn(siblings)
    }

    // ── applyTermFilters — level/binding/free-text combinations ──────────────────────────────

    @Test
    fun `the level filter alone narrows the term combo to the matching level`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_19, B2bTerm.BT_155))
        panel.openFor(p, null)

        levelFilterCombo(panel).value = B2bTermLevel.LINE

        assertEquals(listOf(B2bTerm.BT_155), visibleTerms(panel))
    }

    @Test
    fun `the binding filter alone narrows the term combo to the matching binding`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_19, B2bTerm.BT_1))
        panel.openFor(p, null)

        bindingFilterCombo(panel).value = B2bTermBinding.BOUND

        assertEquals(listOf(B2bTerm.BT_1), visibleTerms(panel))
    }

    @Test
    fun `level and binding filters combine with AND, not OR`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_19, B2bTerm.BT_1, B2bTerm.BT_155))
        panel.openFor(p, null)

        levelFilterCombo(panel).value = B2bTermLevel.HEADER
        bindingFilterCombo(panel).value = B2bTermBinding.FREE

        assertEquals(listOf(B2bTerm.BT_19), visibleTerms(panel), "BT-1 fails binding, BT-155 fails level")
    }

    @Test
    fun `the free-text filter combines with an active level filter`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_19, B2bTerm.BT_56, B2bTerm.BT_155))
        panel.openFor(p, null)

        levelFilterCombo(panel).value = B2bTermLevel.HEADER

        assertEquals(listOf(B2bTerm.BT_56), visibleTerms(panel, "56"), "text filter still applies on top of the level filter")
    }

    @Test
    fun `opening a field resets a stale level or binding filter from a previously edited field`() {
        val panel = newPanel()
        val p = profile()
        val fieldA = profileField(B2bTerm.BT_19, id = 1L)
        stubOpen(p, fieldA, listOf(B2bTerm.BT_19, B2bTerm.BT_155))
        panel.openFor(p, fieldA)
        levelFilterCombo(panel).value = B2bTermLevel.LINE
        bindingFilterCombo(panel).value = B2bTermBinding.FREE

        stubOpen(p, null, listOf(B2bTerm.BT_19, B2bTerm.BT_155))
        panel.openFor(p, null, force = true)

        assertNull(levelFilterCombo(panel).value)
        assertNull(bindingFilterCombo(panel).value)
        assertEquals(2, visibleTerms(panel).size)
    }

    // ── syncConditionVisibility ────────────────────────────────────────────────────────────────

    @Test
    fun `CONDITIONAL and CONDITIONAL_IF_EMPTY both reveal the condition combo, OPTIONAL and MANDATORY do not`() {
        val panel = newPanel()
        val requirement = requirementCombo(panel)
        val condition = conditionCombo(panel)

        requirement.value = B2bFieldRequirement.CONDITIONAL
        assertTrue(condition.isVisible)
        requirement.value = B2bFieldRequirement.CONDITIONAL_IF_EMPTY
        assertTrue(condition.isVisible)
        requirement.value = B2bFieldRequirement.OPTIONAL
        assertFalse(condition.isVisible)
        requirement.value = B2bFieldRequirement.MANDATORY
        assertFalse(condition.isVisible)
    }

    // ── editing-existing vs new-field mode / englishNameInfo ──────────────────────────────────

    @Test
    fun `creating a field with no term chosen yet shows the picker, not the badge`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_19))

        panel.openFor(p, null)

        assertTrue(termCombo(panel).isVisible)
        assertFalse(termBadge(panel).isVisible)
        assertFalse(englishNameInfo(panel).isVisible)
        assertEquals("", englishNameInfo(panel).text)
        assertFalse(autoFilledBadge(panel).isVisible)
    }

    @Test
    fun `picking a term while creating switches to the badge and reveals the English name`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_19))
        panel.openFor(p, null)

        termCombo(panel).value = B2bTerm.BT_19

        assertFalse(termCombo(panel).isVisible)
        assertTrue(termBadge(panel).isVisible)
        assertTrue(termBadge(panel).text.contains("BT-19"))
        assertTrue(englishNameInfo(panel).isVisible)
        assertEquals("EN: ${B2bTermTexts.name(B2bTerm.BT_19, Locale.ENGLISH)}", englishNameInfo(panel).text)
    }

    @Test
    fun `editing an existing field always shows the badge, term combo stays hidden`() {
        val panel = newPanel()
        val p = profile()
        val existing = profileField(B2bTerm.BT_19, id = 5L)
        stubOpen(p, existing, listOf(B2bTerm.BT_19))

        panel.openFor(p, existing)

        assertFalse(termCombo(panel).isVisible)
        assertTrue(termBadge(panel).isVisible)
    }

    // ── autoFilledBadge ────────────────────────────────────────────────────────────────────────

    @Test
    fun `the autoFilled badge is visible for an autoFilled term`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_1))
        panel.openFor(p, null)

        termCombo(panel).value = B2bTerm.BT_1

        assertTrue(autoFilledBadge(panel).isVisible, "BT-1 is autoFilled")
    }

    @Test
    fun `the autoFilled badge is hidden for a non-autoFilled term`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_19))
        panel.openFor(p, null)

        termCombo(panel).value = B2bTerm.BT_19

        assertFalse(autoFilledBadge(panel).isVisible)
    }

    // ── allTermsUsedInfo / Save enabling ───────────────────────────────────────────────────────

    @Test
    fun `no selectable terms and no term chosen shows the all-terms-used hint and disables Save`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, emptyList())

        panel.openFor(p, null)

        assertTrue(allTermsUsedInfo(panel).isVisible)
        assertFalse(termCombo(panel).isVisible)
        assertFalse(levelFilterCombo(panel).isVisible)
        assertFalse(bindingFilterCombo(panel).isVisible)
        assertFalse(saveButton(panel).isEnabled)
    }

    @Test
    fun `editing an existing field shows the badge, not the all-terms-used hint, even if selectableTerms is empty`() {
        val panel = newPanel()
        val p = profile()
        val existing = profileField(B2bTerm.BT_19, id = 7L)
        stubOpen(p, existing, emptyList())

        panel.openFor(p, existing)

        assertFalse(allTermsUsedInfo(panel).isVisible)
        assertTrue(termBadge(panel).isVisible)
        assertTrue(saveButton(panel).isEnabled)
    }

    // ── updateSpecInfoblock ────────────────────────────────────────────────────────────────────

    @Test
    fun `the spec infoblock shows the curated German definition when one exists`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_19))
        panel.openFor(p, null)

        termCombo(panel).value = B2bTerm.BT_19

        assertTrue(specInfoblock(panel).isVisible)
        assertEquals(requireNotNull(B2bTermTexts.definitionOrNull(B2bTerm.BT_19, Locale.GERMAN)), specInfoblock(panel).text)
    }

    @Test
    fun `the spec infoblock is hidden when no curated German definition exists`() {
        assertNull(B2bTermTexts.definitionOrNull(B2bTerm.BT_131, Locale.GERMAN))
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_131))
        panel.openFor(p, null)

        termCombo(panel).value = B2bTerm.BT_131

        assertFalse(specInfoblock(panel).isVisible)
        assertEquals("", specInfoblock(panel).text)
    }

    @Test
    fun `the spec infoblock is hidden again once the term is cleared`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_19))
        panel.openFor(p, null)
        termCombo(panel).value = B2bTerm.BT_19

        termCombo(panel).value = null

        assertFalse(specInfoblock(panel).isVisible)
    }

    // ── syncPlacementForTerm ───────────────────────────────────────────────────────────────────

    @Test
    fun `a LINE-level term forces placement to INVOICE and makes the combo read-only`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_155))
        panel.openFor(p, null)

        termCombo(panel).value = B2bTerm.BT_155

        assertEquals(B2bPlacement.INVOICE, placementCombo(panel).value)
        assertTrue(placementCombo(panel).isReadOnly)
    }

    @Test
    fun `a non-LINE term leaves the placement combo editable`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_19))
        panel.openFor(p, null)

        termCombo(panel).value = B2bTerm.BT_19

        assertFalse(placementCombo(panel).isReadOnly)
    }

    @Test
    fun `switching from a LINE term back to a non-LINE term re-enables the placement combo`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_155, B2bTerm.BT_19))
        panel.openFor(p, null)
        termCombo(panel).value = B2bTerm.BT_155
        assertTrue(placementCombo(panel).isReadOnly)

        termCombo(panel).value = B2bTerm.BT_19

        assertFalse(placementCombo(panel).isReadOnly)
    }

    @Test
    fun `opening an existing field with a LINE-level term does not re-apply the field's own stored placement`() {
        val panel = newPanel()
        val p = profile()
        val existing = profileField(B2bTerm.BT_155, id = 5L).copy(placement = B2bPlacement.SELLER)
        stubOpen(p, existing, listOf(B2bTerm.BT_155))

        panel.openFor(p, existing)

        // syncPlacementForTerm already forced INVOICE (the only allowed choice for LINE) before
        // the field's own stored placement would otherwise be applied — the stale SELLER value
        // on the entity must not win.
        assertEquals(B2bPlacement.INVOICE, placementCombo(panel).value)
        assertTrue(placementCombo(panel).isReadOnly)
    }

    @Test
    fun `a BUYER_PARTY-level term forces placement to INVOICE and makes the combo read-only`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_44))
        panel.openFor(p, null)

        termCombo(panel).value = B2bTerm.BT_44

        assertEquals(B2bPlacement.INVOICE, placementCombo(panel).value)
        assertTrue(placementCombo(panel).isReadOnly)
        assertEquals(UiTranslations.tr("referenceData.b2b.field.placement.helper.buyerParty"), placementCombo(panel).helperText)
    }

    @Test
    fun `a SELLER_PARTY-level term forces placement to SELLER and makes the combo read-only`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_84))
        panel.openFor(p, null)

        termCombo(panel).value = B2bTerm.BT_84

        assertEquals(B2bPlacement.SELLER, placementCombo(panel).value)
        assertTrue(placementCombo(panel).isReadOnly)
        assertEquals(UiTranslations.tr("referenceData.b2b.field.placement.helper.sellerParty"), placementCombo(panel).helperText)
    }

    // ── syncDefaultValueVisibility ─────────────────────────────────────────────────────────────

    @Test
    fun `a FREE, non-DATE term shows the default value input and applies its maxLength`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_19))
        panel.openFor(p, null)

        termCombo(panel).value = B2bTerm.BT_19

        assertTrue(defaultValueField(panel).isVisible)
        assertEquals(B2bTerm.BT_19.maxLength, defaultValueField(panel).maxLength)
    }

    @Test
    fun `a BOUND term hides the default value input`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_1))
        panel.openFor(p, null)

        termCombo(panel).value = B2bTerm.BT_1

        assertFalse(defaultValueField(panel).isVisible)
    }

    @Test
    fun `switching from a FREE term to a BOUND one clears an already-typed default value`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_19, B2bTerm.BT_1))
        panel.openFor(p, null)
        termCombo(panel).value = B2bTerm.BT_19
        defaultValueField(panel).value = "some text"

        termCombo(panel).value = B2bTerm.BT_1

        assertFalse(defaultValueField(panel).isVisible)
        assertEquals("", defaultValueField(panel).value)
    }

    @Test
    fun `no term selected hides the default value input`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_19))
        panel.openFor(p, null)
        termCombo(panel).value = B2bTerm.BT_19

        termCombo(panel).value = null

        assertFalse(defaultValueField(panel).isVisible)
    }

    // ── dirty / loading ────────────────────────────────────────────────────────────────────────

    @Test
    fun `openFor never marks the panel dirty despite programmatically populating every control`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_19))

        panel.openFor(p, null)

        assertFalse(panel.isDirty())
    }

    @Test
    fun `changing the term combo after loading marks the panel dirty`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_19))
        panel.openFor(p, null)

        termCombo(panel).value = B2bTerm.BT_19

        assertTrue(panel.isDirty())
    }

    @Test
    fun `changing the business definition after loading marks the panel dirty`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_19))
        panel.openFor(p, null)

        businessDefinitionArea(panel).value = "neuer Text"

        assertTrue(panel.isDirty())
    }

    @Test
    fun `changing the requirement combo after loading marks the panel dirty`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_19))
        panel.openFor(p, null)

        requirementCombo(panel).value = B2bFieldRequirement.MANDATORY

        assertTrue(panel.isDirty())
    }

    @Test
    fun `changing the condition combo after loading marks the panel dirty`() {
        val panel = newPanel()
        val p = profile()
        val sibling = profileField(B2bTerm.BT_56, id = 2L)
        stubOpen(p, null, listOf(B2bTerm.BT_19), siblings = listOf(sibling))
        panel.openFor(p, null)
        requirementCombo(panel).value = B2bFieldRequirement.CONDITIONAL
        // requirementCombo's own listener already marked it dirty; reload state to isolate the
        // conditionCombo listener itself.
        panel.openFor(p, null, force = true)
        requirementCombo(panel).value = B2bFieldRequirement.CONDITIONAL

        conditionCombo(panel).value = sibling

        assertTrue(panel.isDirty())
    }

    @Test
    fun `changing the placement combo after loading marks the panel dirty`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_19))
        panel.openFor(p, null)

        placementCombo(panel).value = B2bPlacement.SELLER

        assertTrue(panel.isDirty())
    }

    @Test
    fun `changing the default value after loading marks the panel dirty`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_19))
        panel.openFor(p, null)
        termCombo(panel).value = B2bTerm.BT_19
        panel.openFor(p, null, force = true)
        termCombo(panel).value = B2bTerm.BT_19

        defaultValueField(panel).value = "rechnung@example.com"

        assertTrue(panel.isDirty())
    }

    @Test
    fun `openFor refuses to switch fields while dirty unless forced`() {
        val panel = newPanel()
        val p = profile()
        val fieldA = profileField(B2bTerm.BT_19, id = 1L)
        val fieldB = profileField(B2bTerm.BT_56, id = 2L)
        stubOpen(p, fieldA, emptyList())
        stubOpen(p, fieldB, emptyList())
        panel.openFor(p, fieldA)
        businessDefinitionArea(panel).value = "geändert"

        val switched = panel.openFor(p, fieldB)

        assertFalse(switched)
        assertEquals(fieldA.id, panel.currentField()?.id)
        assertTrue(panel.openFor(p, fieldB, force = true))
        assertEquals(fieldB.id, panel.currentField()?.id)
    }

    // ── saveField ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `saving without a term selected shows an error and does not persist`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_19))
        panel.openFor(p, null)

        invokeSaveField(panel)

        verify(manageB2bFieldDefinition, never()).saveB2bProfileField(any())
        assertNull(savedProfile)
        assertTrue(popupDialog(panel).isOpened, "an invalid save must not close the popup")
    }

    @Test
    fun `saving with a term persists, notifies onSaved, and closes the popup`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_19))
        panel.openFor(p, null)
        termCombo(panel).value = B2bTerm.BT_19
        whenever(manageB2bFieldDefinition.saveB2bProfileField(any())).thenAnswer { it.arguments[0] as B2bProfileFieldDto }

        invokeSaveField(panel)

        assertEquals(p, savedProfile)
        assertFalse(popupDialog(panel).isOpened)
        assertFalse(panel.isDirty())
    }

    @Test
    fun `a CONDITIONAL requirement with no chosen trigger saves a null conditionTerm`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_19))
        panel.openFor(p, null)
        termCombo(panel).value = B2bTerm.BT_19
        requirementCombo(panel).value = B2bFieldRequirement.CONDITIONAL
        val captor = argumentCaptor<B2bProfileFieldDto>()
        whenever(manageB2bFieldDefinition.saveB2bProfileField(captor.capture())).thenAnswer { it.arguments[0] as B2bProfileFieldDto }

        invokeSaveField(panel)

        assertNull(captor.firstValue.conditionTerm)
    }

    @Test
    fun `a CONDITIONAL_IF_EMPTY requirement with a chosen trigger saves its term`() {
        val panel = newPanel()
        val p = profile()
        val sibling = profileField(B2bTerm.BT_56, id = 2L)
        stubOpen(p, null, listOf(B2bTerm.BT_19), siblings = listOf(sibling))
        panel.openFor(p, null)
        termCombo(panel).value = B2bTerm.BT_19
        requirementCombo(panel).value = B2bFieldRequirement.CONDITIONAL_IF_EMPTY
        conditionCombo(panel).value = sibling
        val captor = argumentCaptor<B2bProfileFieldDto>()
        whenever(manageB2bFieldDefinition.saveB2bProfileField(captor.capture())).thenAnswer { it.arguments[0] as B2bProfileFieldDto }

        invokeSaveField(panel)

        assertEquals(B2bTerm.BT_56, captor.firstValue.conditionTerm)
    }

    @Test
    fun `switching back to OPTIONAL ignores a stale condition combo selection - conditionTerm is not saved`() {
        val panel = newPanel()
        val p = profile()
        val sibling = profileField(B2bTerm.BT_56, id = 2L)
        stubOpen(p, null, listOf(B2bTerm.BT_19), siblings = listOf(sibling))
        panel.openFor(p, null)
        termCombo(panel).value = B2bTerm.BT_19
        requirementCombo(panel).value = B2bFieldRequirement.CONDITIONAL
        conditionCombo(panel).value = sibling
        // Revert to OPTIONAL - the combo keeps its stale value, but it must not leak into the save.
        requirementCombo(panel).value = B2bFieldRequirement.OPTIONAL
        val captor = argumentCaptor<B2bProfileFieldDto>()
        whenever(manageB2bFieldDefinition.saveB2bProfileField(captor.capture())).thenAnswer { it.arguments[0] as B2bProfileFieldDto }

        invokeSaveField(panel)

        assertNull(captor.firstValue.conditionTerm)
    }

    @Test
    fun `a blank default value is saved as null, a non-blank one is trimmed`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_19))
        panel.openFor(p, null)
        termCombo(panel).value = B2bTerm.BT_19
        defaultValueField(panel).value = "   "
        val captor = argumentCaptor<B2bProfileFieldDto>()
        whenever(manageB2bFieldDefinition.saveB2bProfileField(captor.capture())).thenAnswer { it.arguments[0] as B2bProfileFieldDto }

        invokeSaveField(panel)

        assertNull(captor.firstValue.defaultValue)
    }

    @Test
    fun `a default value is not read when the widget is hidden for a BOUND term`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_1))
        panel.openFor(p, null)
        termCombo(panel).value = B2bTerm.BT_1
        val captor = argumentCaptor<B2bProfileFieldDto>()
        whenever(manageB2bFieldDefinition.saveB2bProfileField(captor.capture())).thenAnswer { it.arguments[0] as B2bProfileFieldDto }

        invokeSaveField(panel)

        assertNull(captor.firstValue.defaultValue)
    }

    @Test
    fun `saveField catches InvalidParameterException, shows an error, and keeps the popup open`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_19))
        panel.openFor(p, null)
        termCombo(panel).value = B2bTerm.BT_19
        whenever(manageB2bFieldDefinition.saveB2bProfileField(any())).thenThrow(InvalidParameterException("boom"))

        invokeSaveField(panel)

        assertTrue(popupDialog(panel).isOpened, "a validation failure must not close the popup")
        assertNull(savedProfile)
    }

    // ── delete button ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `the delete button does nothing while creating - there is no field to delete yet`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_19))
        panel.openFor(p, null)

        deleteButton(panel).click()

        assertNull(deleteRequested)
    }

    @Test
    fun `the delete button requests deletion of the field being edited`() {
        val panel = newPanel()
        val p = profile()
        val existing = profileField(B2bTerm.BT_19, id = 9L)
        stubOpen(p, existing, listOf(B2bTerm.BT_19))
        panel.openFor(p, existing)

        deleteButton(panel).click()

        assertEquals(existing, deleteRequested)
    }

    @Test
    fun `the delete button is only enabled when editing a persisted field`() {
        val panel = newPanel()
        val p = profile()
        val existing = profileField(B2bTerm.BT_19, id = 9L)
        stubOpen(p, null, listOf(B2bTerm.BT_19))
        stubOpen(p, existing, listOf(B2bTerm.BT_19))

        panel.openFor(p, null)
        assertFalse(deleteButton(panel).isEnabled)

        panel.openFor(p, existing, force = true)
        assertTrue(deleteButton(panel).isEnabled)
    }

    // ── cancel / showEmpty ─────────────────────────────────────────────────────────────────────

    @Test
    fun `cancel discards unsaved edits and closes the popup without confirmation`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, emptyList())
        panel.openFor(p, null)
        businessDefinitionArea(panel).value = "nicht gespeichert"
        assertTrue(panel.isDirty())

        cancelButton(panel).click()

        assertFalse(popupDialog(panel).isOpened)
        assertFalse(panel.isDirty())
        assertNull(panel.currentField())
    }

    // ── item label generators (termLabel/requirementLabel/placementLabel/filter labels) ──────

    @Test
    fun `the term combo's item label combines the code with the curated or fallback display name`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_19))
        panel.openFor(p, null)

        val label = termCombo(panel).itemLabelGenerator.apply(B2bTerm.BT_19)

        assertEquals("BT-19 ${B2bTermTexts.name(B2bTerm.BT_19, Locale.GERMAN)}", label)
    }

    @Test
    fun `every requirement enum value has a distinct, non-blank label`() {
        val panel = newPanel()
        val labels = B2bFieldRequirement.entries.map { requirementCombo(panel).itemLabelGenerator.apply(it) }

        assertTrue(labels.all { it.isNotBlank() })
        assertEquals(labels.toSet().size, labels.size, "each requirement kind must render a distinct label")
    }

    @Test
    fun `every placement enum value has a distinct, non-blank label`() {
        val panel = newPanel()
        val labels = B2bPlacement.entries.map { placementCombo(panel).itemLabelGenerator.apply(it) }

        assertTrue(labels.all { it.isNotBlank() })
        assertEquals(labels.toSet().size, labels.size)
    }

    @Test
    fun `every term level has a distinct, non-blank filter label`() {
        val panel = newPanel()
        val labels = B2bTermLevel.entries.map { levelFilterCombo(panel).itemLabelGenerator.apply(it) }

        assertTrue(labels.all { it.isNotBlank() })
        assertEquals(labels.toSet().size, labels.size)
    }

    @Test
    fun `every term binding has a distinct, non-blank filter label`() {
        val panel = newPanel()
        val labels = B2bTermBinding.entries.map { bindingFilterCombo(panel).itemLabelGenerator.apply(it) }

        assertTrue(labels.all { it.isNotBlank() })
        assertEquals(labels.toSet().size, labels.size)
    }

    // ── buildTermSuggestion (picker row) ───────────────────────────────────────────────────────

    @Test
    fun `the picker suggestion shows the autoFilled badge for an autoFilled term`() {
        val panel = newPanel()

        val suggestion = invokeBuildTermSuggestion(panel, B2bTerm.BT_1)

        val header = suggestion.children.toList().filterIsInstance<HorizontalLayout>().first()
        val spans = header.children.toList().filterIsInstance<Span>()
        assertEquals(3, spans.size, "code chip + name + autoFilled badge")
        assertEquals(B2bTerm.BT_1.officialCode, spans[0].text)
        assertEquals(UiTranslations.tr("referenceData.b2b.field.autoFilled.badge"), spans[2].text)
    }

    @Test
    fun `the picker suggestion omits the autoFilled badge for a non-autoFilled term`() {
        val panel = newPanel()

        val suggestion = invokeBuildTermSuggestion(panel, B2bTerm.BT_19)

        val header = suggestion.children.toList().filterIsInstance<HorizontalLayout>().first()
        val spans = header.children.toList().filterIsInstance<Span>()
        assertEquals(2, spans.size, "no autoFilled badge - only code chip and name")
    }

    @Test
    fun `the picker suggestion's definition line is empty for a term with no curated definition`() {
        val panel = newPanel()

        val suggestion = invokeBuildTermSuggestion(panel, B2bTerm.BT_131)

        val definition = suggestion.children.toList().filterIsInstance<Span>().first { it.element.classList.contains("term-definition") }
        assertEquals("", definition.text)
    }

    // ── termCombo's own ComponentRenderer wires straight into buildTermSuggestion ─────────────

    @Test
    fun `the term combo's own renderer produces the same suggestion buildTermSuggestion does`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_1))
        panel.openFor(p, null)

        val rendered = termCombo(panel)._getRenderedComponentFor(B2bTerm.BT_1) as VerticalLayout

        val header = rendered.children.toList().filterIsInstance<HorizontalLayout>().first()
        assertEquals(3, header.children.toList().filterIsInstance<Span>().size, "renders through to the autoFilled badge branch")
    }

    // ── openFor's own field population — the remaining reachable branches ────────────────────

    @Test
    fun `the condition combo's item label falls back to empty for a sibling with no term`() {
        val panel = newPanel()
        val siblingWithNoTerm = profileField(term = null, id = 3L)

        val label = conditionCombo(panel).itemLabelGenerator.apply(siblingWithNoTerm)

        assertEquals("", label)
    }

    @Test
    fun `opening an existing field pre-fills the default value input from the stored value`() {
        val panel = newPanel()
        val p = profile()
        val existing = profileField(B2bTerm.BT_19, id = 4L, defaultValue = "rechnung@example.com")
        stubOpen(p, existing, listOf(B2bTerm.BT_19))

        panel.openFor(p, existing)

        assertEquals("rechnung@example.com", defaultValueField(panel).value)
    }

    @Test
    fun `opening an existing CONDITIONAL field pre-selects the sibling matching its stored conditionTerm`() {
        val panel = newPanel()
        val p = profile()
        val sibling = profileField(B2bTerm.BT_56, id = 2L)
        val existing = profileField(
            B2bTerm.BT_19,
            requirement = B2bFieldRequirement.CONDITIONAL,
            id = 5L,
            conditionTerm = B2bTerm.BT_56,
        )
        stubOpen(p, existing, listOf(B2bTerm.BT_19), siblings = listOf(sibling, existing))

        panel.openFor(p, existing)

        assertEquals(sibling, conditionCombo(panel).value, "the sibling whose term matches the stored conditionTerm must be pre-selected")
    }

    @Test
    fun `opening an existing field whose stored conditionTerm matches no sibling leaves the condition combo empty`() {
        val panel = newPanel()
        val p = profile()
        // No sibling carries BT-56 - the stored reference is stale/orphaned.
        val existing = profileField(
            B2bTerm.BT_19,
            requirement = B2bFieldRequirement.CONDITIONAL,
            id = 6L,
            conditionTerm = B2bTerm.BT_56,
        )
        stubOpen(p, existing, listOf(B2bTerm.BT_19), siblings = listOf(existing))

        panel.openFor(p, existing)

        assertNull(conditionCombo(panel).value)
    }

    @Test
    fun `openFor requires the profile to already be persisted - an unsaved profile (null id) throws`() {
        val panel = newPanel()
        val unsavedProfile = B2bProfileDto(id = null, name = "Neu, noch nicht gespeichert")

        assertThrows(IllegalArgumentException::class.java) { panel.openFor(unsavedProfile, null) }
    }

    @Test
    fun `saveField called before any openFor is a safe no-op - the guard clause for a null profile`() {
        val panel = newPanel()

        invokeSaveField(panel)

        verify(manageB2bFieldDefinition, never()).saveB2bProfileField(any())
        assertNull(savedProfile)
    }

    @Test
    fun `editing an existing field and saving targets that same field`() {
        val panel = newPanel()
        val p = profile()
        val existing = profileField(B2bTerm.BT_19, id = 8L)
        stubOpen(p, existing, listOf(B2bTerm.BT_19))
        panel.openFor(p, existing)
        businessDefinitionArea(panel).value = "aktualisiert"
        val captor = argumentCaptor<B2bProfileFieldDto>()
        whenever(manageB2bFieldDefinition.saveB2bProfileField(captor.capture())).thenAnswer { it.arguments[0] as B2bProfileFieldDto }

        invokeSaveField(panel)

        assertEquals(existing.id, captor.firstValue.id, "editing must save the existing field, not create a new one")
        assertEquals("aktualisiert", captor.firstValue.businessDefinition)
    }

    @Test
    fun `a cleared requirement combo defaults to OPTIONAL on save`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_19))
        panel.openFor(p, null)
        termCombo(panel).value = B2bTerm.BT_19
        requirementCombo(panel).value = null
        val captor = argumentCaptor<B2bProfileFieldDto>()
        whenever(manageB2bFieldDefinition.saveB2bProfileField(captor.capture())).thenAnswer { it.arguments[0] as B2bProfileFieldDto }

        invokeSaveField(panel)

        assertEquals(B2bFieldRequirement.OPTIONAL, captor.firstValue.requirement)
    }

    @Test
    fun `a cleared placement combo defaults to INVOICE on save`() {
        val panel = newPanel()
        val p = profile()
        stubOpen(p, null, listOf(B2bTerm.BT_19))
        panel.openFor(p, null)
        termCombo(panel).value = B2bTerm.BT_19
        placementCombo(panel).value = null
        val captor = argumentCaptor<B2bProfileFieldDto>()
        whenever(manageB2bFieldDefinition.saveB2bProfileField(captor.capture())).thenAnswer { it.arguments[0] as B2bProfileFieldDto }

        invokeSaveField(panel)

        assertEquals(B2bPlacement.INVOICE, captor.firstValue.placement)
    }
}
