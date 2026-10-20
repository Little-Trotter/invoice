package de.littletrotter.ui.view.referencedata

import de.littletrotter.ui.component.AppDialog
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.ActionBar
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.ModalityMode
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.renderer.ComponentRenderer
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldDefinitionUseCase
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.shared.b2b.B2bFieldKind
import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.shared.b2b.B2bTermBinding
import de.littletrotter.shared.b2b.B2bTermLevel
import de.littletrotter.shared.b2b.B2bTermTexts
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.notification.UiNotifications
import java.util.Locale

private const val BUSINESS_DEFINITION_MIN_ROWS = 4
private const val BUSINESS_DEFINITION_MAX_ROWS = 8

/**
 * The field editor for a single [B2bProfileFieldDto]. It opens as [popupDialog] — a narrow,
 * right-docked [Dialog] that opens on demand instead of costing width permanently, so editing a
 * field never loses the context of the other rules. [popupDialog] is
 * pinned to the right edge (`b2b-field-popup` theme name, styled in styles.css's b2b-field-popup block via
 * `vaadin-dialog-overlay[theme~="b2b-field-popup"]::part(overlay)`) and non-modal, so the rules
 * table stays fully visible — and interactive — to its left the whole time.
 *
 * This class itself (a [VerticalLayout]) is [popupDialog]'s sole content component — it is never
 * added to any other parent; opening/closing the popup is exactly opening/closing this panel.
 *
 * Term selection: [termCombo] picks directly over [B2bTerm] constants filtered to
 * [B2bTerm.catalogEligible] (via [B2bFieldDefinitionQueryPort.findSelectableB2bCatalogEntries] — reference
 * terms never reach the picker), free-text-filtered by code+German name, shown only while creating
 * a field and no term has been picked yet. Once a term is chosen (or always, when editing an
 * existing field — [editingField] fixes the term, it never changes after creation) the panel shows
 * [termBadge] instead: the term is then a fact of the record, not an input. [englishNameInfo]
 * surfaces the official EN 16931 English name for the *selected* term only, on demand — never
 * pre-computed for the whole list ([B2bTermTexts.name] lazily loads the shared English bundle on
 * its first call).
 *
 * [levelFilterCombo]/[bindingFilterCombo] narrow the
 * picker by [B2bTerm.level]/[B2bTerm.binding], combined with the free-text filter in
 * [applyTermFilters]: both are typed properties already on every [B2bTerm], so this is a plain
 * predicate over the same [selectableTerms] list the picker already holds — no new data structure,
 * no eager per-category cache. Reset to "alle" every time [openFor] runs so a stale filter from a
 * previously edited field can never hide the term the user is now looking for.
 *
 * Unsaved-changes guard: [openFor] refuses to switch to a different field (`force` = false, the
 * default) while [dirty] — the caller ([B2bProfilesTab.openFieldPanel]) reads the `false` return
 * value and asks the user to confirm discarding before retrying with `force = true`. This is also
 * what protects the popup from being silently replaced: since [B2bProfilesTab.openFieldPanel] is
 * the only path that opens a *different* field while one is already showing, the same guard
 * covers "closing the popup with unsaved changes" — there is no separate close-guard on the
 * dialog itself; ESC and outside-click are disabled ([popupDialog]) so Save/Cancel/Delete are the
 * only ways out. [dirty] is set by every user-driven value-change listener below and guarded by
 * [loading] so the programmatic population inside [openFor] itself never marks the panel dirty.
 */
class B2bFieldEditorPanel(
    private val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort,
    private val manageB2bFieldDefinition: ManageB2bFieldDefinitionUseCase,
    private val onSaved: (B2bProfileDto) -> Unit,
    private val onRequestDelete: (B2bProfileFieldDto) -> Unit,
) : VerticalLayout() {

    private val panelTitle = Span().also { it.addClassName("b2b-field-panel-title") }
    /** Compact category narrowing above [termCombo]; see [applyTermFilters]. */
    private val levelFilterCombo = ComboBox<B2bTermLevel>()
    private val bindingFilterCombo = ComboBox<B2bTermBinding>()
    private val termCombo = ComboBox<B2bTerm>()
    private val termBadge = Span().also { it.addClassName("b2b-term-badge") }
    private val englishNameInfo = Span().also { it.addClassName("b2b-term-english-name") }
    /** "vorbelegt" badge for the currently selected/shown term, mirroring the one
     *  [buildTermSuggestion] renders per picker row. */
    private val autoFilledBadge = Span().also { it.addClassName("b2b-term-autofilled-badge") }

    /** When every catalog-eligible term is already used by this profile, [selectableTerms] is
     *  legitimately empty; an empty combo with no explanation reads as a defect, so this hint
     *  replaces the picker in that case. */
    private val allTermsUsedInfo = Span().also {
        it.addClassName("b2b-term-all-used")
        it.isVisible = false
    }
    private val specInfoblock = Div().also { it.addClassName("b2b-spec-infoblock") }
    private val businessDefinitionArea = TextArea()
    private val requirementCombo = ComboBox<B2bFieldRequirement>()
    private val conditionCombo = ComboBox<B2bProfileFieldDto>()
    private val placementCombo = ComboBox<B2bPlacement>()
    private val defaultValueField = TextField()

    private val saveButton = AppButtons.primary("") { saveField() }
    private val cancelButton = AppButtons.cancel { showEmpty() }
    private val deleteButton = AppButtons.danger("") { editingField?.let { onRequestDelete(it) } }

    private val formLayout: VerticalLayout

    /** The right-docked popup; see the class KDoc. Non-modal (the rules table
     *  behind it must stay visible/usable) and closed only programmatically (ESC/outside-click
     *  disabled) — Save, Cancel, and a successful Delete are the only ways to close it, matching
     *  "closing the popup with unsaved changes asks the same confirm the panel-switch did" (there
     *  is nothing *but* the panel-switch guard to ask it, by construction). */
    private val popupDialog = AppDialog("", AppDialog.Size.S)

    private var profile: B2bProfileDto? = null
    private var editingField: B2bProfileFieldDto? = null

    /** The full, catalog-eligible candidate list [openFor] resolved for the
     *  current profile/field, before [levelFilterCombo]/[bindingFilterCombo]/free-text narrowing;
     *  [applyTermFilters] re-derives [termCombo]'s visible items from this on every filter change. */
    private var selectableTerms: List<B2bTerm> = emptyList()

    /** Guards [markDirty] while [openFor] programmatically assigns field values — otherwise every
     *  reload would immediately mark itself dirty via its own value-change listeners. */
    private var loading = false
    private var dirty = false

    init {
        addClassName("b2b-field-panel")
        isPadding = true
        isSpacing = true

        levelFilterCombo.label = UiTranslations.tr(this, "referenceData.b2b.field.filterLevel.label")
        levelFilterCombo.placeholder = UiTranslations.tr(this, "referenceData.b2b.field.filterLevel.all")
        levelFilterCombo.isClearButtonVisible = true
        levelFilterCombo.setItems(B2bTermLevel.entries)
        levelFilterCombo.setItemLabelGenerator { levelFilterLabel(it) }
        levelFilterCombo.addValueChangeListener { applyTermFilters() }

        bindingFilterCombo.label = UiTranslations.tr(this, "referenceData.b2b.field.filterBinding.label")
        bindingFilterCombo.placeholder = UiTranslations.tr(this, "referenceData.b2b.field.filterBinding.all")
        bindingFilterCombo.isClearButtonVisible = true
        bindingFilterCombo.setItems(B2bTermBinding.entries)
        bindingFilterCombo.setItemLabelGenerator { bindingFilterLabel(it) }
        bindingFilterCombo.addValueChangeListener { applyTermFilters() }

        termCombo.label = UiTranslations.tr(this, "referenceData.b2b.field.term")
        termCombo.placeholder = UiTranslations.tr(this, "referenceData.b2b.field.term.searchPlaceholder")
        termCombo.setRenderer(ComponentRenderer { term -> buildTermSuggestion(term) })
        termCombo.setItemLabelGenerator { term -> termLabel(term) }
        termCombo.addValueChangeListener { event ->
            updateSpecInfoblock(event.value)
            syncPlacementForTerm(event.value)
            syncDefaultValueVisibility(event.value)
            syncTermPresentation()
            markDirty()
        }

        businessDefinitionArea.label = UiTranslations.tr(this, "referenceData.b2b.field.ownDefinition")
        businessDefinitionArea.helperText = UiTranslations.tr(this, "referenceData.b2b.field.ownDefinition.helper")
        businessDefinitionArea.minRows = BUSINESS_DEFINITION_MIN_ROWS
        businessDefinitionArea.maxRows = BUSINESS_DEFINITION_MAX_ROWS
        businessDefinitionArea.setWidthFull()
        businessDefinitionArea.addValueChangeListener { markDirty() }

        requirementCombo.label = UiTranslations.tr(this, "referenceData.b2b.field.requirement")
        requirementCombo.setItems(B2bFieldRequirement.entries)
        requirementCombo.setItemLabelGenerator { requirementLabel(it) }
        requirementCombo.addValueChangeListener { syncConditionVisibility(); markDirty() }

        conditionCombo.label = UiTranslations.tr(this, "referenceData.b2b.field.condition")
        conditionCombo.setItemLabelGenerator { it.term?.officialCode.orEmpty() }
        conditionCombo.isVisible = false
        conditionCombo.addValueChangeListener { markDirty() }

        placementCombo.label = UiTranslations.tr(this, "referenceData.b2b.field.placement")
        placementCombo.setItems(B2bPlacement.entries)
        placementCombo.setItemLabelGenerator { placementLabel(it) }
        placementCombo.addValueChangeListener { markDirty() }

        defaultValueField.label = UiTranslations.tr(this, "referenceData.b2b.field.defaultValue")
        defaultValueField.helperText = UiTranslations.tr(this, "referenceData.b2b.field.defaultValue.helper")
        defaultValueField.isClearButtonVisible = true
        defaultValueField.addValueChangeListener { markDirty() }

        saveButton.text = UiTranslations.tr(this, "app.save")

        cancelButton.text = UiTranslations.tr(this, "app.cancel")
        // "Cancel" closes the popup outright (discarding unsaved edits
        // without asking, same as any other Cancel button in this app) instead of merely
        // reverting the still-open panel to its last-persisted state.

        deleteButton.text = UiTranslations.tr(this, "app.delete")

        val specLabel = Span(UiTranslations.tr(this, "referenceData.b2b.field.specDefinition.label")).also {
            it.addClassName("b2b-spec-infoblock-label")
        }
        val termFilters = HorizontalLayout(levelFilterCombo, bindingFilterCombo).also {
            it.addClassName("b2b-term-filters")
            it.isPadding = false
            it.isSpacing = true
        }
        allTermsUsedInfo.text = UiTranslations.tr(this, "referenceData.b2b.field.allTermsUsed")
        autoFilledBadge.text = UiTranslations.tr(this, "referenceData.b2b.field.autoFilled.badge")
        val termArea = Div(termFilters, termCombo, allTermsUsedInfo, termBadge, autoFilledBadge, englishNameInfo).also {
            it.addClassName("b2b-term-area")
        }
        formLayout = VerticalLayout(
            panelTitle,
            termArea,
            specLabel,
            specInfoblock,
            businessDefinitionArea,
            requirementCombo,
            conditionCombo,
            placementCombo,
            defaultValueField,
        ).also {
            it.isPadding = false
            it.isSpacing = true
        }

        add(formLayout)

        // This panel is the popup's sole content; it never lives in any other
        // parent. See styles.css's b2b-field-popup block for the right-docking CSS keyed off this theme
        // name. Non-modal + ESC/outside-click disabled: see the class KDoc and popupDialog's own
        // KDoc for why.
        popupDialog.element.themeList.add("b2b-field-popup")
        popupDialog.modality = ModalityMode.MODELESS
        popupDialog.isCloseOnEsc = false
        popupDialog.isCloseOnOutsideClick = false
        popupDialog.isDraggable = false
        popupDialog.isResizable = false
        // The docked popup keeps its stylesheet-defined width, so the fixed dialog width is cleared.
        popupDialog.width = null
        popupDialog.add(this)
        popupDialog.footer.add(ActionBar(listOf(deleteButton), cancelButton, saveButton))

        showEmpty()
    }

    fun isDirty(): Boolean = dirty

    fun currentField(): B2bProfileFieldDto? = editingField

    /** Resets the panel to its placeholder state and closes [popupDialog] — called by "Speichern"
     *  and "Abbrechen" (both close the popup), on every profile-dialog open
     *  ("reopening after close shows fresh state"), and after a field the panel was
     *  currently showing gets deleted from the grid. Closing an already-closed dialog is a no-op,
     *  so this is safe to call unconditionally. */
    fun showEmpty() {
        profile = null
        editingField = null
        dirty = false
        popupDialog.close()
    }

    /** Loads [field] (`null` = create mode) for editing in [profile]'s context and opens
     *  [popupDialog]. Returns `false` without changing anything when the panel currently holds
     *  unsaved edits and `force` is not set — the caller is expected to confirm discarding with
     *  the user and retry with `force = true`. Also guards re-opening the exact field already
     *  shown — a stray double-click must not silently overwrite unsaved edits with the
     *  last-persisted state. */
    fun openFor(profile: B2bProfileDto, field: B2bProfileFieldDto?, force: Boolean = false): Boolean {
        if (dirty && !force) return false
        val profileId = requireNotNull(profile.id) { "profile must be persisted before its fields can be edited" }

        loading = true
        this.profile = profile
        this.editingField = field
        panelTitle.text = if (field == null) {
            UiTranslations.tr(this, "referenceData.b2b.field.new")
        } else {
            UiTranslations.tr(this, "referenceData.b2b.field.edit")
        }

        selectableTerms = b2bFieldDefinitionQueries.findSelectableB2bCatalogEntries(profileId, field?.id)
        // A stale filter from a previously edited field must never hide the term this open is
        // actually looking for.
        levelFilterCombo.value = null
        bindingFilterCombo.value = null
        applyTermFilters()
        val currentTerm = field?.term
        termCombo.value = currentTerm

        businessDefinitionArea.value = field?.businessDefinition.orEmpty()
        requirementCombo.value = field?.requirement ?: B2bFieldRequirement.OPTIONAL

        val siblings = b2bFieldDefinitionQueries.findB2bProfileFields(profileId).filter { it.id != field?.id }
        conditionCombo.setItems(siblings)
        conditionCombo.value = field?.conditionTerm?.let { term -> siblings.firstOrNull { it.term == term } }
        syncConditionVisibility()

        syncPlacementForTerm(currentTerm)
        // A narrowed (single-choice) placement is already forced by syncPlacementForTerm
        // itself; only a HEADER/no-term selection (the free-choice case) still takes the field's
        // own stored placement (or INVOICE for a brand-new field).
        if (!placementCombo.isReadOnly) placementCombo.value = field?.placement ?: B2bPlacement.INVOICE

        defaultValueField.value = field?.defaultValue.orEmpty()
        syncDefaultValueVisibility(currentTerm)

        updateSpecInfoblock(currentTerm)
        syncTermPresentation()

        deleteButton.isEnabled = field?.id != null

        loading = false
        dirty = false
        popupDialog.open()
        return true
    }

    private fun markDirty() {
        if (!loading) dirty = true
    }

    /**
     * A machine-generated term has no curated German name (spec: "do not machine-generate
     * 200 German names"); [B2bTermTexts.name] falls back to the official English name for exactly
     * those terms itself, never showing a raw placeholder string in the picker.
     */
    private fun termDisplayName(term: B2bTerm): String = B2bTermTexts.name(term, Locale.GERMAN)

    /** The picker label: code + short name (German where curated, English fallback
     *  otherwise — see [termDisplayName]). */
    private fun termLabel(term: B2bTerm): String = "${term.officialCode} ${termDisplayName(term)}"

    /** (Re-)applies [levelFilterCombo]/[bindingFilterCombo]/free-text narrowing to
     *  [termCombo]'s item set. [selectableTerms] itself never changes here — only which of its
     *  entries the combo currently shows — so this is cheap to call on every filter-combo change. */
    private fun applyTermFilters() {
        termCombo.setItems(
            { item: B2bTerm, filterText: String ->
                (levelFilterCombo.value == null || item.level == levelFilterCombo.value) &&
                    (bindingFilterCombo.value == null || item.binding == bindingFilterCombo.value) &&
                    termLabel(item).contains(filterText, ignoreCase = true)
            },
            selectableTerms,
        )
    }

    private fun syncTermPresentation() {
        val term = termCombo.value
        val showBadge = editingField != null || term != null
        // All catalog terms already used by this profile: replace the (correctly)
        // empty picker with an explanatory hint instead of showing an empty dropdown.
        val allUsed = !showBadge && selectableTerms.isEmpty()
        allTermsUsedInfo.isVisible = allUsed
        termBadge.isVisible = showBadge
        termCombo.isVisible = !showBadge && !allUsed
        levelFilterCombo.isVisible = !showBadge && !allUsed
        bindingFilterCombo.isVisible = !showBadge && !allUsed
        saveButton.isEnabled = !allUsed
        if (term != null) {
            termBadge.text = termLabel(term)
            // English name on demand, computed only for the now-selected term (never
            // eagerly for the whole picker list). "EN:" is a plain, language-neutral tag, not a
            // translatable UI string, so it needs no i18n key of its own.
            englishNameInfo.text = "EN: ${B2bTermTexts.name(term, Locale.ENGLISH)}"
            englishNameInfo.isVisible = true
            // `showBadge` is necessarily true in this branch (`term != null` is one of its two
            // disjuncts), so only the term's own flag decides.
            autoFilledBadge.isVisible = term.autoFilled
        } else {
            termBadge.text = ""
            englishNameInfo.text = ""
            englishNameInfo.isVisible = false
            autoFilledBadge.isVisible = false
        }
    }

    /** Most generated terms carry no curated German spec-definition text yet (only the
     *  hand-curated ones do); the infoblock is hidden rather than showing the raw `!{key}!`
     *  placeholder for those, instead of [updateSpecInfoblock] blindly resolving unconditionally. */
    private fun updateSpecInfoblock(term: B2bTerm?) {
        val definition = term?.let { B2bTermTexts.definitionOrNull(it, Locale.GERMAN) }
        specInfoblock.text = definition.orEmpty()
        specInfoblock.isVisible = definition != null
    }

    private fun syncConditionVisibility() {
        // CONDITIONAL_IF_EMPTY shares the same "auslösender Term" combo as CONDITIONAL;
        // only the requirement select's own value decides which hasValue direction applies.
        val conditional = requirementCombo.value == B2bFieldRequirement.CONDITIONAL ||
            requirementCombo.value == B2bFieldRequirement.CONDITIONAL_IF_EMPTY
        conditionCombo.isVisible = conditional
    }

    /**
     * The placement choice offered
     * here must only ever contain the placements
     * [de.littletrotter.domain.reference.B2bProfileField.assertPlacementCompatibleWithLevel]
     * would actually accept for [term]'s [B2bTermLevel], mirroring that server-side invariant
     * instead of letting the anwender pick a combination that saves but never renders:
     * - [B2bTermLevel.LINE]/[B2bTermLevel.BUYER_PARTY] — fixed to [B2bPlacement.INVOICE]
     *   for LINE; BUYER_PARTY gets the identical treatment because that is what the
     *   server invariant actually enforces for it too, not [B2bPlacement.RECIPIENT] as the naming
     *   alone might suggest.
     * - [B2bTermLevel.SELLER_PARTY] — fixed to [B2bPlacement.SELLER] (this level exists
     *   only for the seller's own mandatory self-service fields, not a freely placeable term).
     * - [B2bTermLevel.HEADER] (or no term chosen yet) — the full, freely combinable choice.
     *
     * A single-item result forces that value and disables the combo (same idiom the former
     * LINE-only version of this method already used), with a helper text explaining why — the
     * combo is never merely validated against an already-wrong value, the wrong values are not
     * offered in the first place.
     */
    private fun syncPlacementForTerm(term: B2bTerm?) {
        val allowed = when (term?.level) {
            B2bTermLevel.LINE, B2bTermLevel.BUYER_PARTY -> listOf(B2bPlacement.INVOICE)
            B2bTermLevel.SELLER_PARTY -> listOf(B2bPlacement.SELLER)
            B2bTermLevel.HEADER, null -> B2bPlacement.entries
        }
        placementCombo.setItems(allowed)
        if (allowed.size == 1) {
            placementCombo.value = allowed.single()
            placementCombo.isReadOnly = true
        } else {
            placementCombo.isReadOnly = false
        }
        placementCombo.helperText = when (term?.level) {
            B2bTermLevel.LINE -> UiTranslations.tr(this, "referenceData.b2b.field.placement.helper.line")
            B2bTermLevel.BUYER_PARTY -> UiTranslations.tr(this, "referenceData.b2b.field.placement.helper.buyerParty")
            B2bTermLevel.SELLER_PARTY -> UiTranslations.tr(this, "referenceData.b2b.field.placement.helper.sellerParty")
            else -> null
        }
    }

    /** The default-value input only makes sense for a FREE term (a BOUND term's value
     *  lives on its bound property) and, v1 decision, not for a DATE-kind term either. Hidden
     *  rather than merely disabled, and cleared when hidden so switching the term away from a
     *  FREE/non-DATE one can't silently smuggle a stale default into [saveField]. */
    private fun syncDefaultValueVisibility(term: B2bTerm?) {
        val visible = term != null && term.binding == B2bTermBinding.FREE && term.fieldKind != B2bFieldKind.DATE
        defaultValueField.isVisible = visible
        if (!visible) defaultValueField.clear()
        term?.maxLength?.let { defaultValueField.maxLength = it }
    }

    private fun saveField() {
        val currentProfile = profile ?: return
        val term = termCombo.value
        if (term == null) {
            UiNotifications.errorOn(termCombo, UiTranslations.tr(this, "referenceData.b2b.field.termRequired"))
            return
        }
        val requirement = requirementCombo.value ?: B2bFieldRequirement.OPTIONAL
        val target = (editingField ?: B2bProfileFieldDto()).copy(
            profileId = currentProfile.id,
            term = term,
            businessDefinition = businessDefinitionArea.value.orEmpty(),
            requirement = requirement,
            conditionTerm = if (
                requirement == B2bFieldRequirement.CONDITIONAL ||
                requirement == B2bFieldRequirement.CONDITIONAL_IF_EMPTY
            ) conditionCombo.value?.term else null,
            placement = placementCombo.value ?: B2bPlacement.INVOICE,
            defaultValue = defaultValueField.takeIf { it.isVisible }?.value?.trim()?.ifBlank { null },
        )

        try {
            manageB2bFieldDefinition.saveB2bProfileField(target)
            UiNotifications.saved(this)
            onSaved(currentProfile)
            // "Speichern" closes the popup (the rules grid, refreshed by
            // onSaved above, is the single source of truth again); it no longer stays open
            // reloaded to the just-saved field.
            showEmpty()
        } catch (ex: InvalidParameterException) {
            UiNotifications.error(ex.message ?: UiTranslations.tr(this, "message.error.prefix"))
        }
    }

    private fun buildTermSuggestion(term: B2bTerm): VerticalLayout {
        val codeChip = Span(term.officialCode).also { it.addClassName("bt-code-chip") }
        val name = Span(termDisplayName(term)).also { it.addClassName("term-name") }
        val header = HorizontalLayout(codeChip, name).also { it.isPadding = false; it.isSpacing = true }
        // The "vorbelegt" badge: an unobtrusive marker next to the code/name so a user
        // browsing the picker sees at a glance that a MANDATORY rule on this term is already
        // satisfied by the application itself, without opening the term first.
        if (term.autoFilled) {
            val badge = Span(UiTranslations.tr(this, "referenceData.b2b.field.autoFilled.badge"))
                .also { it.addClassName("b2b-term-autofilled-badge") }
            header.add(badge)
        }
        val definition = Span(B2bTermTexts.definitionOrNull(term, Locale.GERMAN).orEmpty())
            .also { it.addClassName("term-definition") }
        return VerticalLayout(header, definition).also {
            it.addClassName("b2b-term-suggestion")
            it.isPadding = false
            it.isSpacing = false
        }
    }

    private fun requirementLabel(requirement: B2bFieldRequirement): String = when (requirement) {
        B2bFieldRequirement.OPTIONAL -> UiTranslations.tr(this, "referenceData.b2b.field.requirement.optional")
        B2bFieldRequirement.MANDATORY -> UiTranslations.tr(this, "referenceData.b2b.field.requirement.mandatory")
        B2bFieldRequirement.CONDITIONAL -> UiTranslations.tr(this, "referenceData.b2b.field.requirement.conditional")
        B2bFieldRequirement.CONDITIONAL_IF_EMPTY -> UiTranslations.tr(this, "referenceData.b2b.field.requirement.conditionalIfEmpty")
    }

    private fun placementLabel(placement: B2bPlacement): String = when (placement) {
        B2bPlacement.INVOICE -> UiTranslations.tr(this, "referenceData.b2b.field.placement.invoice")
        B2bPlacement.RECIPIENT -> UiTranslations.tr(this, "referenceData.b2b.field.placement.recipient")
        B2bPlacement.SELLER -> UiTranslations.tr(this, "referenceData.b2b.field.placement.seller")
    }

    /** Labels for [levelFilterCombo]'s options. */
    private fun levelFilterLabel(level: B2bTermLevel): String = when (level) {
        B2bTermLevel.HEADER -> UiTranslations.tr(this, "referenceData.b2b.field.filterLevel.header")
        B2bTermLevel.LINE -> UiTranslations.tr(this, "referenceData.b2b.field.filterLevel.line")
        B2bTermLevel.SELLER_PARTY -> UiTranslations.tr(this, "referenceData.b2b.field.filterLevel.sellerParty")
        B2bTermLevel.BUYER_PARTY -> UiTranslations.tr(this, "referenceData.b2b.field.filterLevel.buyerParty")
    }

    /** Labels for [bindingFilterCombo]'s options. */
    private fun bindingFilterLabel(binding: B2bTermBinding): String = when (binding) {
        B2bTermBinding.FREE -> UiTranslations.tr(this, "referenceData.b2b.field.filterBinding.free")
        B2bTermBinding.BOUND -> UiTranslations.tr(this, "referenceData.b2b.field.filterBinding.bound")
    }
}
