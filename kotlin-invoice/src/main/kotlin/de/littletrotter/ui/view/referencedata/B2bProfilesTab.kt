package de.littletrotter.ui.view.referencedata

import de.littletrotter.ui.component.asNumericColumn
import de.littletrotter.ui.component.asActionColumn
import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asTextColumn
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppDialog
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.MultiSelectComboBox
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.binder.Binder
import com.vaadin.flow.data.provider.CallbackDataProvider
import com.vaadin.flow.data.provider.Query
import com.vaadin.flow.data.provider.SortDirection as VaadinSortDirection
import de.littletrotter.application.dto.SortDirection
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldDefinitionUseCase
import de.littletrotter.application.reference.port.inbound.ManageB2bProfileUseCase
import de.littletrotter.application.reference.port.inbound.B2bProfileQueryPort
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.shared.country.Country
import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.ui.component.ChipTone
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.StatusChip
import de.littletrotter.ui.component.blankToNull
import de.littletrotter.ui.component.Section
import de.littletrotter.ui.component.countryDisplayName
import de.littletrotter.ui.component.twoLineTextColumn
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.notification.UiNotifications

private const val APP_CANCEL_KEY = "app.cancel"

/**
 * Reference-data tab for B2B profiles: a single full-width grid of profiles
 * (standard [de.littletrotter.ui.view.AbstractCrudView] CRUD for name/description/active).
 * Mounted unconditionally (see [ReferenceDataView]).
 *
 * Field/rule maintenance (add/edit/delete) lives in the profile editor dialog itself. There is no
 * row-details panel — a row click only selects (native Vaadin single-selection, no listener
 * needed), and only the action buttons at the end of the row ([profileRowActions] — edit, copy)
 * open the editor dialog. There is exactly one representation of a profile's rules: the dialog.
 * A double-click does **not** open it either — only the buttons do.
 *
 * The profile editor dialog is a large workspace (`min(96vw, 1240px)`):
 *  1. No more "Doppelanzeige": there is nothing left behind the dialog to collapse — the
 *     row-details panel it used to fight with is gone structurally, not just hidden while the
 *     dialog is open.
 *  2. The field editor is a narrow, right-docked popup
 *     [Dialog][com.vaadin.flow.component.dialog.Dialog] that [fieldEditorPanel] itself owns and
 *     opens on demand (see [B2bFieldEditorPanel]'s own KDoc) — the rules grid takes the section's
 *     full width, and only opens the popup while actively editing/creating a field, guarded
 *     against losing unsaved edits (see [B2bProfileRulesSection]'s own KDoc).
 *  3. The dialog carries a tinted master-data header section, summary chips ("{n} Regeln ·
 *     {m} Pflicht · {k} bedingt" + a usage chip — [B2bProfileRulesSection]), and a footer with
 *     Speichern/Abbrechen/Löschen pinned to the dialog's bottom edge: `.b2b-editor
 *     -body` is a column flex layout whose content area (`.b2b-editor-scroll-area`) scrolls
 *     internally past ~82vh while the footer (`.b2b-editor-footer`) sits outside that scrolling
 *     region as a fixed sibling, so the action buttons never scroll out of view regardless of
 *     window height or rule count.
 *
 * Item identity for [profileGrid] is resolved by [B2bProfileDto.id] (see [buildProfileDataProvider])
 * rather than by value, because [B2bProfileDto] compares all of its fields. Vaadin's
 * `DataProvider.refreshItem` keys its internal bookkeeping off `DataProvider.getId(item)`, so with
 * an id-based provider a freshly re-fetched (and possibly edited) [B2bProfileDto] row is
 * still recognized as "the same row" — which is what lets a field save/delete refresh just the
 * row's field-count column via [refreshProfileRow] without disturbing the rest of the grid.
 */
class B2bProfilesTab(
    private val b2bProfileQueries: B2bProfileQueryPort,
    private val manageB2bProfile: ManageB2bProfileUseCase,
    private val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort,
    private val manageB2bFieldDefinition: ManageB2bFieldDefinitionUseCase,
    private val time: TimeSource,
) : AbstractReferencedataCrudTab<B2bProfileDto, B2bProfileFormData>(DataTable(B2bProfileDto::class.java, false)) {

    // Kept under its historical name (rather than the inherited `grid`) because
    // B2bProfilesTabKaribuTest reflects on the exact field "profileGrid"; it is the same Grid
    // instance as the base class's `grid`, not a second grid.
    private val profileGrid: Grid<B2bProfileDto> = grid
    private val binder = Binder(B2bProfileFormData::class.java)

    // The header's search field feeds currentFilter() below via the base class's
    // inherited searchFilter/counterSpan/totalCount/inactiveCount; the counter is refreshed
    // alongside the grid in refreshProfileGrid().

    private val nameField = TextField()
    private val descriptionField = TextArea()
    private val activeField = Checkbox()
    /** This profile's country scope; empty = unscoped (visible
     *  for every seller). */
    private val countriesField = MultiSelectComboBox<Country>()

    override var currentFormData = B2bProfileFormData()

    // The profile currently loaded in editorDialog's "Regeln" section (fieldsSection
    // tracks its own copy for its own field add/edit/delete operations); set by readFormData
    // whenever the profile editor dialog opens (null for a new, unsaved profile), and read back
    // only by deleteEntityById to decide whether the grid's own selection must be cleared too.
    private var selectedProfile: B2bProfileDto? = null
    private val profileDataProvider: CallbackDataProvider<B2bProfileDto, Void> = buildProfileDataProvider()

    // Usage counts (recipients' standing default) keyed by profile id, refreshed
    // alongside the grid ([refreshProfileGrid]) via one grouped port call and then read for
    // free by every row's "Verwendung" column value provider — never one query per row.
    private var usageCounts: Map<Long, Long> = emptyMap()

    // The field editor is a narrow popup Dialog it owns and docks to the
    // right edge itself (see its own class KDoc). Callbacks close the loop back into this tab: onSaved
    // refreshes the owning profile's row + fieldsSection's own rules grid/chips (same as a field
    // save always did); onRequestDelete reuses fieldsSection's one shared confirm+delete path so
    // the popup's own delete button and the grid row's trash icon behave identically.
    private val fieldEditorPanel: B2bFieldEditorPanel = B2bFieldEditorPanel(
        b2bFieldDefinitionQueries = b2bFieldDefinitionQueries,
        manageB2bFieldDefinition = manageB2bFieldDefinition,
        onSaved = { profile -> refreshProfileRow(profile); fieldsSection.refresh(profile) },
        onRequestDelete = { field -> fieldsSection.confirmDeleteField(field) },
    )

    // The profile editor dialog's "Regeln" section (fields grid, summary chips, and the
    // field-level delete flow), split out into its own composer; see its own class KDoc.
    private val fieldsSection: B2bProfileRulesSection = B2bProfileRulesSection(
        b2bProfileQueries = b2bProfileQueries,
        b2bFieldDefinitionQueries = b2bFieldDefinitionQueries,
        manageB2bFieldDefinition = manageB2bFieldDefinition,
        fieldEditorPanel = fieldEditorPanel,
        onFieldMutated = { profile -> refreshProfileRow(profile) },
    )

    init {
        initializeTab()
    }

    // ── Profiles (full-width grid) ───────────────────────────────────────────

    // This screen has never shown the validation-info paragraph the other tabs carry.
    override fun getValidationInfoKey(): String? = null

    override fun getSearchPlaceholder(): String = "referenceData.b2b.profile.placeholder"

    // Split out of the original single ~130-line configureProfiles(): a screen this size
    // (grid columns, editor field wiring, and the large-workspace dialog assembly) blows
    // past detekt's LongMethod/CyclomaticComplexMethod thresholds as one function. configureGrid()
    // itself is now just the sequencing; the three concerns each get their own private function,
    // same split TaxRatesTab settled on.
    override fun configureGrid() {
        configureProfileGridColumns()
        configureEditorFields()
        configureEditorDialog()
        profileGrid.dataProvider = profileDataProvider
        // Seeds usageCounts before the grid's first render — refreshProfileGrid() would otherwise
        // only run on a later filter change or CRUD op, leaving the very first paint of the
        // "Verwendung" column at its emptyMap() default.
        refreshProfileGrid()
    }

    private fun configureProfileGridColumns() {
        profileGrid.setSizeFull()
        // Name + description share one two-line identity cell. Fixed base width
        // instead of auto-width: auto-width would size to the longest text and push
        // status/counts/actions out of view; this one takes the remaining width and clamps.
        profileGrid.twoLineTextColumn({ it.name }, { it.description })
            .setHeader(UiTranslations.tr(this, "referenceData.b2b.profile.identity"))
            .setSortable(true)
            .setSortProperty("name")
            .setKey("name")
            .asTextColumn(TextColumnWidth.LARGE)
        profileGrid.addComponentColumn { profile -> statusBadge(profile.active) }
            .setHeader(UiTranslations.tr(this, "referenceData.status"))
            .asCompactColumn()
        profileGrid.addColumn { b2bFieldDefinitionQueries.findB2bProfileFields(it.id ?: -1).size }
            .setHeader(UiTranslations.tr(this, "referenceData.b2b.profile.fieldCount"))
            .asNumericColumn()
        // Reads the pre-fetched usageCounts map (refreshed by refreshProfileGrid), not
        // a per-row port call: with a grid page of N profiles this is N cheap map lookups, not
        // N queries.
        profileGrid.addColumn { usageCounts[it.id] ?: 0L }
            .setHeader(UiTranslations.tr(this, "referenceData.b2b.profile.usageCount"))
            .asNumericColumn()
        profileGrid.addComponentColumn { profile -> profileRowActions(profile) }
            .asActionColumn()

        // A row click only selects (native Vaadin single-selection, no listener
        // needed); only profileRowActions' edit/copy buttons open the editor dialog. No
        // double-click listener either — the buttons are the sole entry point.
    }

    private fun configureEditorFields() {
        nameField.label = UiTranslations.tr(this, "referenceData.name")
        descriptionField.label = UiTranslations.tr(this, "referenceData.description")
        descriptionField.minRows = 2
        activeField.label = UiTranslations.tr(this, "referenceData.b2b.profile.activeField")
        // Deactivating a profile in use silently changes future invoices of every
        // assigned recipient; warn (naming the count) instead of hard-blocking. Guarded by
        // isFromClient so loading an already-inactive profile into the editor (a programmatic
        // binder.readBean, not a user click) never triggers this. Reverts the checkbox on cancel;
        // on confirm nothing further happens here — the normal Save button still persists it.
        activeField.addValueChangeListener { event ->
            if (!event.isFromClient || event.value) return@addValueChangeListener
            val id = currentFormData.id ?: return@addValueChangeListener
            val count = b2bProfileQueries.findB2bProfileUsageCounts()[id] ?: 0L
            if (count <= 0L) return@addValueChangeListener
            ConfirmDialog(
                UiTranslations.tr(this, "referenceData.b2b.profile.deactivateConfirm.title"),
                UiTranslations.tr(this, "referenceData.b2b.profile.deactivateConfirm.text", count),
                UiTranslations.tr(this, "referenceData.b2b.profile.deactivateConfirm.confirm"),
            ) { }.also { dialog ->
                dialog.setCancelable(true)
                dialog.setCancelText(UiTranslations.tr(this, APP_CANCEL_KEY))
                dialog.addCancelListener { activeField.value = true }
                dialog.open()
            }
        }
        countriesField.label = UiTranslations.tr(this, "b2bProfile.countries.label")
        countriesField.helperText = UiTranslations.tr(this, "b2bProfile.countries.helper")
        countriesField.setItems(b2bProfileQueries.allowedB2bCountryScope(time.today()).sortedBy { it.alpha2 })
        countriesField.setItemLabelGenerator { countryDisplayName(it) }

        binder.forField(nameField)
            .asRequired(UiTranslations.tr(this, "referenceData.b2b.profile.required"))
            .bind(B2bProfileFormData::name, B2bProfileFormData::name::set)
        binder.forField(descriptionField)
            .bind(B2bProfileFormData::description, B2bProfileFormData::description::set)
        binder.forField(activeField)
            .bind(B2bProfileFormData::active, B2bProfileFormData::active::set)
        binder.forField(countriesField)
            .bind(B2bProfileFormData::countries, B2bProfileFormData::countries::set)

        initCrudDialogButtons()
    }

    private fun configureEditorDialog() {
        val form = FormLayout(nameField, descriptionField, activeField, countriesField).also {
            it.setColspan(descriptionField, 2)
            it.setColspan(countriesField, 2)
        }
        // The dialog is a large workspace (see the class KDoc): a tinted master-data
        // header section, summary chips, a full-width "Regeln" rules grid (the field
        // editor does not live inline — it's fieldEditorPanel's own right-docked popup, opened
        // on demand by fieldsSection), and a footer with the danger zone set apart
        // from Speichern/Abbrechen, pinned to the dialog's bottom edge outside the scrolling
        // content area.
        useDialogSize(AppDialog.Size.L)
        installCrudFooter()
        val masterDataSection = Section(
            UiTranslations.tr(this, "referenceData.b2b.profile.masterData.section"),
            "sp-section-bar--blue",
            form,
        ).also { it.addClassName("b2b-master-data") }
        val rulesSection = Section(
            UiTranslations.tr(this, "referenceData.b2b.profile.rules.section"),
            "sp-section-bar--amber",
            fieldsSection,
        )
        // The scrollable content (master data + rules) is a sibling of the
        // footer, not its parent: `.b2b-editor-scroll-area` carries the overflow-y: auto/max-height
        // that used to sit on the whole body, so the footer below it is never part of that
        // scrolling region and stays pinned to the dialog's bottom edge (see styles.css's
        // block for the actual flex layout).
        val scrollArea = VerticalLayout(masterDataSection, fieldsSection.summaryChips, rulesSection).also {
            it.addClassName("b2b-editor-scroll-area")
            it.isPadding = false
            it.isSpacing = true
        }
        editorDialog.add(
            VerticalLayout(scrollArea).also {
                it.addClassName("b2b-editor-body")
                it.isPadding = false
                it.isSpacing = false
            }
        )

        profileGrid.dataProvider = profileDataProvider
        // Seeds usageCounts before the grid's first render — refreshProfileGrid() would otherwise
        // only run on a later filter change or CRUD op, leaving the very first paint of the
        // "Verwendung" column at its emptyMap() default.
        refreshProfileGrid()
    }

    override fun idOf(formData: B2bProfileFormData): Long? = formData.id
    override fun formDataFrom(entity: B2bProfileDto?): B2bProfileFormData = B2bProfileFormData.from(entity)
    override fun createWritableForm(id: Long?): B2bProfileFormData = B2bProfileFormData(id = id)

    /** Beyond the plain binder read, (re)loads the "Regeln" section ([fieldsSection]) for
     *  whichever profile the dialog just opened for: the entity is re-fetched by id (not reused
     *  from the caller) so the fields grid always reflects the latest saved state, matching
     *  [de.littletrotter.ui.view.masterdata.ArticleView.readFormData]'s idiom for its own
     *  sub-sections. `formData.id == null` (new, unsaved profile) clears [selectedProfile] and
     *  makes [fieldsSection] render the "save first" hint instead of the grid.
     *
     *  Also closes the field editor's own popup, if it happened to still be open, and resets
     *  [fieldEditorPanel] back to its empty placeholder, so reopening the dialog after a close
     *  always starts fresh rather than showing whatever field was last being edited. */
    override fun readFormData(formData: B2bProfileFormData) {
        binder.readBean(formData)
        val profile = formData.id?.let { b2bProfileQueries.findB2bProfileById(it) }
        selectedProfile = profile
        fieldEditorPanel.showEmpty()
        fieldsSection.refresh(profile)
    }
    override fun writeValidatedForm(targetFormData: B2bProfileFormData): Boolean = binder.writeValidated(targetFormData)
    override fun findEntityById(id: Long): B2bProfileDto? = b2bProfileQueries.findB2bProfileById(id)
    override fun persistForm(formData: B2bProfileFormData, current: B2bProfileDto?) {
        manageB2bProfile.saveB2bProfile(formData.toDto(current?.version))
    }
    override fun deleteEntityById(id: Long) {
        manageB2bProfile.deleteB2bProfile(id)
        if (selectedProfile?.id == id) {
            selectedProfile = null
            profileGrid.deselectAll()
        }
    }
    override fun refreshGridData() = refreshProfileGrid()
    override fun newHeaderTitle(): String = UiTranslations.tr(this, "referenceData.b2b.profile.new")
    override fun editHeaderTitle(): String = UiTranslations.tr(this, "referenceData.b2b.profile.edit")

    /** Builds the (single, long-lived) data provider backing [profileGrid]. It resolves item
     *  identity via [B2bProfileDto.id] instead of value equality, which is what lets
     *  [refreshProfileGrid] and [refreshProfileRow] update the grid — see the class KDoc for the
     *  underlying Vaadin mechanics. */
    private fun buildProfileDataProvider(): CallbackDataProvider<B2bProfileDto, Void> {
        return CallbackDataProvider(
            { query: Query<B2bProfileDto, Void> ->
                val limit = query.limit
                val offset = query.offset
                val pageIndex = if (limit == 0) 0 else offset / limit
                b2bProfileQueries.findB2bProfiles(pageIndex, limit, currentFilter(), sortDirOf(query)).stream()
            },
            { _: Query<B2bProfileDto, Void> -> b2bProfileQueries.countB2bProfiles(currentFilter()).toInt() },
            { profile: B2bProfileDto -> profile.id ?: -1L },
        )
    }

    // The grid's one sortable column (name) is already the query's default order; only the
    // direction ever varies.
    private fun sortDirOf(query: Query<B2bProfileDto, Void>): SortDirection =
        when (query.sortOrders.firstOrNull()?.direction) {
            VaadinSortDirection.DESCENDING -> SortDirection.DESC
            else -> SortDirection.ASC
        }

    private fun currentFilter(): String? = searchFilter.lowercase().blankToNull()

    // installHeaderActions is the inherited AbstractReferencedataCrudTab
    // implementation: it does exactly this (installSearchAndCounter into searchFilter, calling
    // refreshGridData() — which is refreshProfileGrid(), see the override below), so this screen
    // has no own copy.

    /** Full reload (e.g. filter changed, a profile was saved/deleted). */
    private fun refreshProfileGrid() {
        usageCounts = b2bProfileQueries.findB2bProfileUsageCounts()
        totalCount = b2bProfileQueries.countB2bProfiles(currentFilter())
        inactiveCount = b2bProfileQueries.countInactiveB2bProfiles(currentFilter())
        profileDataProvider.refreshAll()
        if (::counterSpan.isInitialized) counterSpan.text = entryCounterText(totalCount, inactiveCount)
    }

    /** Targeted refresh for [profile]'s row after a field-level mutation (save/delete): updates
     *  the "Felder" count column. Relies on [profileDataProvider]'s id-based item identity (see
     *  its KDoc), so [profile] does not need to be the exact instance the grid last fetched — only
     *  its id has to match. */
    private fun refreshProfileRow(profile: B2bProfileDto) {
        profileDataProvider.refreshItem(profile)
    }

    private fun statusBadge(active: Boolean): Span {
        val label = if (active) UiTranslations.tr(this, "referenceData.b2b.profile.active") else UiTranslations.tr(this, "referenceData.b2b.profile.inactive")
        return StatusChip(label, if (active) ChipTone.OK else ChipTone.NEUTRAL)
    }

    // ── Profile row actions: copy, and the grid's edit-icon entry point ─────

    /** The grid row's copy icon (see [profileRowActions]) copies [profile] server-side
     *  ([de.littletrotter.application.reference.port.inbound.ManageB2bProfileUseCase.copyB2bProfile]), refreshes the grid so the new row and its "(Kopie)"
     *  name are visible, selects it, and opens its editor immediately — the whole point of a copy
     *  action is to rename it right away, not to leave the user hunting for the new row first. */
    private fun copyProfile(profile: B2bProfileDto) {
        val id = profile.id ?: return
        try {
            val copy = manageB2bProfile.copyB2bProfile(id)
            refreshProfileGrid()
            profileGrid.select(copy)
            UiNotifications.success(UiTranslations.tr(this, "referenceData.b2b.profile.copySuccess"))
            openEditor(copy)
        } catch (ex: InvalidParameterException) {
            UiNotifications.error(ex.message ?: UiTranslations.tr(this, "message.error.prefix"))
        }
    }

    /** Since there is no row-details panel, this edit/copy pair is the
     *  *only* row-level way to open the editor dialog (alongside the "Neu" toolbar button for a
     *  new profile); a plain row click only selects, and there is no double-click fallback. */
    private fun profileRowActions(profile: B2bProfileDto): HorizontalLayout {
        val editBtn = AppButtons.icon(ActionIcons.EDIT, UiTranslations.tr(this, "referenceData.b2b.profile.editAction")) { openEditor(profile) }
        val copyBtn = AppButtons.icon(ActionIcons.COPY, UiTranslations.tr(this, "referenceData.b2b.profile.copyAction")) { copyProfile(profile) }
        return HorizontalLayout(editBtn, copyBtn).also {
            it.isPadding = false
            it.isSpacing = false
        }
    }
}
