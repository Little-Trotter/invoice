package de.littletrotter.ui.view.referencedata

import de.littletrotter.ui.component.asActionColumn
import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asTextColumn
import de.littletrotter.ui.component.ActionBar
import de.littletrotter.ui.component.AppButtons
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldDefinitionUseCase
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.B2bProfileQueryPort
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.ui.component.ChipTone
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.StatusChip
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.notification.UiNotifications
import de.littletrotter.ui.component.ActionIcons

private const val APP_CANCEL_KEY = "app.cancel"

/**
 * The profile editor dialog's "Regeln" section, split out of `B2bProfilesTab`: the fields
 * grid (add/edit/delete triggers, requirement/placement/default-value rendering), the summary
 * chips, and the field-level delete flow. [B2bProfilesTab] owns this instance and the
 * [B2bFieldEditorPanel] it wires into [fieldEditorPanel]; it calls [refresh] on every dialog open
 * (from `readFormData`) and [confirmDeleteField] from [fieldEditorPanel]'s own delete button (its
 * `onRequestDelete` callback), so a row's trash icon and the field editor's own delete button
 * behave identically.
 *
 * For an unsaved profile (no id — [B2bProfileFieldDto.profileId] has nowhere to point
 * at yet) this renders a "save first" hint instead of the grid. [onFieldMutated] lets a
 * field-level mutation (save/delete) refresh the owning profile's row in the tab's own profile
 * grid ("Felder" count column) without this section needing to know about that grid at all.
 */
class B2bProfileRulesSection(
    private val b2bProfileQueries: B2bProfileQueryPort,
    private val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort,
    private val manageB2bFieldDefinition: ManageB2bFieldDefinitionUseCase,
    private val fieldEditorPanel: B2bFieldEditorPanel,
    private val onFieldMutated: (B2bProfileDto) -> Unit,
) : VerticalLayout() {

    /** "{n} Regeln · {m} Pflicht · {k} bedingt" + the usage chip; a sibling of
     *  this section in the dialog layout (not nested inside it), rebuilt by [refresh] alongside it. */
    val summaryChips: HorizontalLayout = HorizontalLayout().also {
        it.addClassName("b2b-summary-chips")
        it.isPadding = false
        it.isSpacing = true
    }

    // The profile currently loaded in the dialog's "Regeln" section; set by every refresh
    // (null for a new, unsaved profile). openFieldPanel and deleteField operate on it.
    private var profile: B2bProfileDto? = null

    init {
        setWidthFull()
        isPadding = false
        isSpacing = true
    }

    /** (Re)builds this section for [profile]: for an unsaved profile ([profile]
     *  null or without an id) a German hint to save first; otherwise the "Feld hinzufügen"
     *  toolbar plus the fields table (or the empty-state paragraph), and [summaryChips]. Called by
     *  the owning tab's `readFormData` on every dialog open, by [openFieldPanel] after the popup
     *  switches to a different field (updates the active-row highlight), and again after every
     *  field save/delete so the grid refreshes without closing the dialog. */
    fun refresh(profile: B2bProfileDto?) {
        this.profile = profile
        removeAll()
        summaryChips.removeAll()
        summaryChips.isVisible = profile?.id != null
        if (profile?.id == null) {
            add(
                Paragraph(UiTranslations.tr(this, "referenceData.b2b.profile.rules.saveFirst")).also { it.addClassName("lt-hint-text") }
            )
            return
        }
        val addFieldButton = AppButtons.primary(UiTranslations.tr(this, "referenceData.b2b.field.add"), ActionIcons.ADD) { openFieldPanel(null) }
        val toolbar = ActionBar(addFieldButton)
        val fields = b2bFieldDefinitionQueries.findB2bProfileFields(profile.id)
        val body: Component = if (fields.isEmpty()) {
            Paragraph(UiTranslations.tr(this, "referenceData.b2b.field.empty")).also { it.addClassName("lt-hint-text") }
        } else {
            buildProfileFieldsTable(fields)
        }
        add(toolbar, body)
        // The early return above already proved the id non-null; pass it through instead of
        // buildSummaryChips re-deriving (and re-guarding) the same fact itself.
        buildSummaryChips(profile.id, fields)
    }

    /** "{n} Regeln · {m} Pflicht · {k} bedingt" plus the usage chip, both
     *  shared status chips. Reuses the [fields] list [refresh] already fetched — no extra
     *  port round trip for the rule counts; the usage count calls
     *  [B2bProfileQueryPort.findB2bProfileUsageNames] (its size) directly rather than fetching the
     *  whole-tab usage-counts map for a single id. [profileId] is the owning profile's id, already
     *  proved non-null by [refresh] before this is ever called — taken as a parameter instead of
     *  re-deriving it here so there is exactly one non-null check for it, not two. */
    private fun buildSummaryChips(profileId: Long, fields: List<B2bProfileFieldDto>) {
        val mandatoryCount = fields.count { it.requirement == B2bFieldRequirement.MANDATORY }
        val conditionalCount = fields.count {
            it.requirement == B2bFieldRequirement.CONDITIONAL || it.requirement == B2bFieldRequirement.CONDITIONAL_IF_EMPTY
        }
        val rulesChip = StatusChip(
            UiTranslations.tr(this, "referenceData.b2b.profile.rules.summary", fields.size, mandatoryCount, conditionalCount),
            ChipTone.NEUTRAL,
        )
        val usageCount = b2bProfileQueries.findB2bProfileUsageNames(profileId).size
        val usageChip = StatusChip(
            UiTranslations.tr(this, "referenceData.b2b.profile.usage.chip", usageCount),
            if (usageCount > 0) ChipTone.OK else ChipTone.NEUTRAL,
        )
        summaryChips.add(rulesChip, usageChip)
    }

    /** Routes both "Feld hinzufügen" and a field row's edit icon through the
     *  same unsaved-changes guard: [B2bFieldEditorPanel.openFor] refuses to switch (and refuses to
     *  reopen the popup for a different field) while the panel holds unsaved edits unless told to
     *  discard them. */
    private fun openFieldPanel(field: B2bProfileFieldDto?) {
        val profile = this.profile ?: return
        if (fieldEditorPanel.openFor(profile, field)) {
            refresh(profile) // updates the active-row highlight
            return
        }
        ConfirmDialog(
            UiTranslations.tr(this, "referenceData.b2b.field.unsavedGuard.title"),
            UiTranslations.tr(this, "referenceData.b2b.field.unsavedGuard.text"),
            UiTranslations.tr(this, "referenceData.b2b.field.unsavedGuard.discard"),
        ) {
            fieldEditorPanel.openFor(profile, field, force = true)
            refresh(profile)
        }.also {
            it.setCancelable(true)
            it.setCancelText(UiTranslations.tr(this, APP_CANCEL_KEY))
            it.open()
        }
    }

    /** The full-featured fields table, relocated into the profile editor dialog's
     *  "Regeln" section — edit/delete actions, term, description, requirement badge, placement,
     *  default value. */
    private fun buildProfileFieldsTable(fields: List<B2bProfileFieldDto>): DataTable<B2bProfileFieldDto> {
        val table = DataTable(B2bProfileFieldDto::class.java, false)
        table.addClassName("b2b-rules-grid")
        // Material-accent row highlight for whichever rule the field editor panel
        // currently shows (see the ".b2b-rules-grid::part(active-rule-row)" CSS rule).
        table.setPartNameGenerator { field ->
            if (field.id != null && field.id == fieldEditorPanel.currentField()?.id) "active-rule-row" else null
        }
        table.addColumn { field -> field.term?.officialCode.orEmpty() }
            .setHeader(UiTranslations.tr(this, "referenceData.b2b.field.term"))
            .asCompactColumn()
            .isSortable = true
        // "Beschreibung" (formerly "Eigene Definition"), single-line
        // CSS-ellipsized (see businessDefinitionCell) so a long definition can no longer push the
        // requirement/placement/default-value columns and the row's edit/delete buttons out of
        // view; the full text is still reachable via mouse-over.
        table.addComponentColumn { field -> businessDefinitionCell(field) }
            .setHeader(UiTranslations.tr(this, "referenceData.b2b.field.definition"))
            .asTextColumn(TextColumnWidth.LARGE)
        // A badge instead of plain text: makes the CONDITIONAL(_IF_EMPTY) trigger term
        // visible without opening the dialog (it was, until now, only shown there).
        table.addComponentColumn { field -> requirementBadge(field) }
            .setHeader(UiTranslations.tr(this, "referenceData.b2b.field.requirement"))
            .asCompactColumn()
        table.addColumn { placementLabel(it.placement) }
            .setHeader(UiTranslations.tr(this, "referenceData.b2b.field.placement"))
            .asCompactColumn()
        // Truncated, full value in the
        // native-tooltip `title` attribute (same idiom as e.g. SenderProfileView's info hints);
        // naturally empty for a BOUND term, whose defaultValue is always null (enforced at save
        // time by B2bProfileField.assertDefaultValueCompatibleWithTerm).
        table.addComponentColumn { field -> defaultValueCell(field) }
            .setHeader(UiTranslations.tr(this, "referenceData.b2b.field.defaultValue"))
            .asTextColumn(TextColumnWidth.MEDIUM)
        // Edit/delete stay frozen at the right edge, visible while the grid scrolls horizontally.
        // Delete still goes through the usage-guard confirm (fieldRowActions -> confirmDeleteField).
        table.addComponentColumn { field -> fieldRowActions(field) }
            .asActionColumn()
        // Grows with its content instead of scrolling internally — it lives inside the profile
        // editor dialog's own scrolling content area (.b2b-editor-scroll-area), not a
        // fixed-height page area.
        table.isAllRowsVisible = true
        table.setItems(fields)
        return table
    }

    private fun fieldRowActions(field: B2bProfileFieldDto): HorizontalLayout {
        val editBtn = AppButtons.icon(ActionIcons.EDIT, UiTranslations.tr(this, "referenceData.b2b.field.editAction")) { openFieldPanel(field) }
        val deleteBtn = AppButtons.icon(ActionIcons.DELETE, UiTranslations.tr(this, "referenceData.b2b.field.deleteAction")) { confirmDeleteField(field) }
            .apply { addThemeVariants(ButtonVariant.LUMO_ERROR) }
        return HorizontalLayout(editBtn, deleteBtn).also {
            it.isPadding = false
            it.isSpacing = false
        }
    }

    // ── Field maintenance: delete (save/edit live in fieldEditorPanel) ────

    /** Row-action trash button (fields table) and [fieldEditorPanel]'s own delete button (via its
     *  `onRequestDelete` callback, wired by the owning tab) share this one confirm+delete path —
     *  kept in one place, unchanged in spirit by the panel replacing the old nested
     *  dialog. */
    fun confirmDeleteField(field: B2bProfileFieldDto) {
        val id = field.id ?: return
        ConfirmDialog(
            UiTranslations.tr(this, "referenceData.b2b.field.deleteConfirm.title"),
            UiTranslations.tr(this, "referenceData.b2b.field.deleteConfirm.text", field.term?.officialCode.orEmpty()),
            UiTranslations.tr(this, "app.delete"),
        ) { deleteField(id) }.also {
            it.setCancelable(true)
            it.setCancelText(UiTranslations.tr(this, APP_CANCEL_KEY))
            it.open()
        }
    }

    /** Deletes profile field [id] and refreshes the owning profile's row via [onFieldMutated]
     *  (count column — the tab's own grid) as well as this section's own grid ([refresh]). Resets
     *  [fieldEditorPanel] back to its empty placeholder when the deleted field is the one it was
     *  currently showing — there is nothing left to edit. */
    private fun deleteField(id: Long) {
        val profile = this.profile
        try {
            manageB2bFieldDefinition.deleteB2bProfileField(id)
            profile?.let {
                onFieldMutated(it)
                refresh(it)
            }
            if (fieldEditorPanel.currentField()?.id == id) fieldEditorPanel.showEmpty()
            UiNotifications.deleted(this)
        } catch (ex: InvalidParameterException) {
            UiNotifications.error(ex.message ?: UiTranslations.tr(this, "message.error.prefix"))
        }
    }

    private fun placementLabel(placement: B2bPlacement): String = when (placement) {
        B2bPlacement.INVOICE -> UiTranslations.tr(this, "referenceData.b2b.field.placement.invoice")
        B2bPlacement.RECIPIENT -> UiTranslations.tr(this, "referenceData.b2b.field.placement.recipient")
        B2bPlacement.SELLER -> UiTranslations.tr(this, "referenceData.b2b.field.placement.seller")
    }

    /** The fields grid's requirement badge: `Pflicht` (error), `Optional`
     *  (neutral), and `Bedingt (BT-x)` / `Pflicht wenn BT-x leer` (warn) for the two
     *  conditional kinds, naming the trigger term the dialog otherwise hides. */
    private fun requirementBadge(field: B2bProfileFieldDto): Span {
        val tone = when (field.requirement) {
            B2bFieldRequirement.MANDATORY -> ChipTone.ERROR
            B2bFieldRequirement.OPTIONAL -> ChipTone.NEUTRAL
            B2bFieldRequirement.CONDITIONAL, B2bFieldRequirement.CONDITIONAL_IF_EMPTY -> ChipTone.WARN
        }
        return StatusChip(requirementBadgeLabel(field), tone)
    }

    private fun requirementBadgeLabel(field: B2bProfileFieldDto): String = when (field.requirement) {
        B2bFieldRequirement.OPTIONAL -> UiTranslations.tr(this, "referenceData.b2b.field.requirement.optional")
        B2bFieldRequirement.MANDATORY -> UiTranslations.tr(this, "referenceData.b2b.field.requirement.mandatory")
        B2bFieldRequirement.CONDITIONAL ->
            UiTranslations.tr(this, "referenceData.b2b.field.requirement.conditional.badge", field.conditionTerm?.officialCode.orEmpty())
        B2bFieldRequirement.CONDITIONAL_IF_EMPTY ->
            UiTranslations.tr(this, "referenceData.b2b.field.requirement.conditionalIfEmpty.badge", field.conditionTerm?.officialCode.orEmpty())
    }

    /** The fields grid's default-value column: truncated text, full value in the
     *  native-tooltip `title` attribute (same idiom used elsewhere in the app, e.g.
     *  `SenderProfileView`'s BT-29/30/31/32 info hints) instead of a Vaadin `Tooltip` component. */
    private fun defaultValueCell(field: B2bProfileFieldDto): Span {
        val value = field.defaultValue.orEmpty()
        val truncated = if (value.length > DEFAULT_VALUE_TRUNCATE_LENGTH) value.take(DEFAULT_VALUE_TRUNCATE_LENGTH) + "…" else value
        return Span(truncated).also {
            if (value.isNotEmpty()) it.element.setAttribute("title", value)
        }
    }

    /** The rules grid's "Beschreibung" column: unlike [defaultValueCell]'s
     *  character truncation, this keeps the full text as the Span's content and lets CSS clip it
     *  to a single ellipsized line ([de.littletrotter.ui.component.DataTable]'s cell width already
     *  bounds it — see `.b2b-definition-cell` in styles.css), so a long definition
     *  can no longer push the requirement/placement/default-value columns or the row's edit/delete
     *  buttons out of view. The full text is still reachable via the native-tooltip `title`
     *  attribute (same idiom as [defaultValueCell]). */
    private fun businessDefinitionCell(field: B2bProfileFieldDto): Span {
        val value = field.businessDefinition
        // A hard 20-character cap on the rendered
        // text — the CSS ellipsis alone still let the column claim most of the table's width.
        // The full text stays reachable via the mouse-over title below.
        val shown = if (value.length > DEFINITION_DISPLAY_MAX_CHARS) {
            value.take(DEFINITION_DISPLAY_MAX_CHARS).trimEnd() + "…"
        } else {
            value
        }
        return Span(shown).also {
            it.addClassName("b2b-definition-cell")
            if (value.isNotEmpty()) it.element.setAttribute("title", value)
        }
    }

    companion object {
        /** [defaultValueCell]'s truncation length before the "…" ellipsis kicks in. */
        private const val DEFAULT_VALUE_TRUNCATE_LENGTH = 24

        /** PO amendment — the "Beschreibung" column renders at most this many
         *  characters; the full text lives in the cell's mouse-over tooltip. */
        private const val DEFINITION_DISPLAY_MAX_CHARS = 20
    }
}
