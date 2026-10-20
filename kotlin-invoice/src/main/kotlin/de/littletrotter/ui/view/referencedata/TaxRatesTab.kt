package de.littletrotter.ui.view.referencedata

import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asTextColumn
import de.littletrotter.ui.component.AppDialog
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.ActionBar
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.formlayout.FormLayout
import de.littletrotter.ui.component.ChipTone
import de.littletrotter.ui.component.DataTable
import com.vaadin.flow.component.grid.Grid
import de.littletrotter.ui.component.StatusChip
import de.littletrotter.ui.component.asNumericColumn
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.BigDecimalField
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.binder.Binder
import de.littletrotter.application.dto.SortDirection
import de.littletrotter.application.dto.TaxRateSortField
import de.littletrotter.application.reference.port.inbound.ManageTaxRateUseCase
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.component.createDefaultBadge
import de.littletrotter.ui.format.DateFormatter
import de.littletrotter.ui.notification.UiNotifications
import java.math.BigDecimal
import java.time.LocalDate

class TaxRatesTab(
    private val taxRateQueries: TaxRateQueryPort,
    private val manageTaxRate: ManageTaxRateUseCase,
    // A plain UI class may not read the clock itself (TimeSourceArchitectureTest); the real
    // caller (AbrechnungTabFactory) passes TimeSource.today() explicitly.
    private val today: LocalDate,
) : AbstractReferencedataCrudTab<TaxRateDto, TaxRateFormData>(DataTable(TaxRateDto::class.java, false)) {

    // Kept under its historical name (rather than the inherited `grid`) because
    // TaxRatesTabKaribuTest reflects on the exact field "taxRateGrid"; it is the same Grid
    // instance as the base class's `grid`, not a second grid.
    private val taxRateGrid: Grid<TaxRateDto> = grid
    private val trActiveOnly = Checkbox()
    private val trBinder = Binder(TaxRateFormData::class.java)

    private var usageCounts: Map<Long, Long> = emptyMap()

    // PO decision 14.09.2026 — Referenzdaten grids offer exactly one sortable column (name);
    // null = unsorted, keeping the grid's pre-existing `country, rate` default order.
    private var currentSortField: TaxRateSortField? = null
    private var currentSortDir: SortDirection = SortDirection.ASC

    private val trNameField = TextField()
    private val trRateField = BigDecimalField()
    private val trCountryField = TextField()
    private val trValidFromPicker = DatePicker()
    private val trValidToPicker = DatePicker()
    private val trHotelRateField = Checkbox()
    private val trDefaultField = Checkbox()
    private val trReferenceField = Checkbox()

    // ── The rule fields, editable here instead of only via SQL ───────────────────────
    private val trCodeField = TextField()
    private val trCategoryField = ComboBox<String>()
    private val trExemptionReasonCodeField = TextField()
    private val trExemptionReasonTextField = TextArea()
    private val trLegalBasisField = TextArea()
    private val trActiveField = Checkbox()
    private val trSelectableField = Checkbox()
    private val deactivateButton = AppButtons.tertiary("") {}

    override var currentFormData = TaxRateFormData()

    init {
        initializeTab()
    }

    override fun getValidationInfoKey(): String = "referenceData.taxRate.validationInfo"

    override fun getSearchPlaceholder(): String = "referenceData.taxRate.placeholder"

    // TaxRatesTab needs an extra "active only" filter checkbox next to the New button,
    // and its New button must go through [openTaxRateEditor] (not the base's plain openEditor)
    // so a stale read-only technical-code field from a previous edit is reset. Overrides the
    // base's `open` hook rather than adding a new one — the same extension point PaymentTermsTab
    // and friends leave at its default.
    override fun buildToolbar(): HorizontalLayout {
        val newButton = AppButtons.primary(tr("referenceData.new"), ActionIcons.ADD) { openTaxRateEditor(null) }

        trActiveOnly.label = UiTranslations.tr(this, "referenceData.activeOnly")
        trActiveOnly.addValueChangeListener { refreshTaxRateGrid() }

        return ActionBar(listOf(trActiveOnly), newButton)
    }

    override fun configureGrid() {
        taxRateGrid.setSizeFull()
        // PO decision 14.09.2026 — Referenzdaten grids offer exactly one sortable column, the
        // tab's identifying field (name); rate/country/validFrom/validTo lost their sort arrow.
        taxRateGrid.addColumn(TaxRateDto::name)
            .setHeader(UiTranslations.tr(this, "referenceData.name"))
            .setSortable(true)
            .setSortProperty("name")
            .setKey("name")
            .asTextColumn(TextColumnWidth.MEDIUM)
        taxRateGrid.addColumn { "${it.rate.value.stripTrailingZeros().toPlainString()} %" }
            .setHeader(UiTranslations.tr(this, "referenceData.taxRatePercent"))
            .asNumericColumn()
        taxRateGrid.addColumn(TaxRateDto::country)
            .setHeader(UiTranslations.tr(this, "referenceData.country"))
            .asCompactColumn()
        taxRateGrid.addColumn { DateFormatter.format(it.validFrom) }
            .setHeader(UiTranslations.tr(this, "referenceData.validFrom"))
            .asCompactColumn()
        taxRateGrid.addColumn { DateFormatter.format(it.validTo) }
            .setHeader(UiTranslations.tr(this, "referenceData.validTo"))
            .asCompactColumn()
        taxRateGrid.addComponentColumn { rate -> buildStatusBadges(rate) }
            .setHeader(UiTranslations.tr(this, "referenceData.status"))
            .asCompactColumn()
            .isSortable = false
        // Reads the pre-fetched usageCounts map
        // (refreshed by refreshTaxRateGrid), not a per-row port call.
        taxRateGrid.addColumn { rate -> usageCountLabel(rate) }
            .setHeader(UiTranslations.tr(this, "referenceData.taxRate.usageCount"))
            .asCompactColumn()

        taxRateGrid.addSortListener { event -> applySortOrder(event.sortOrder.firstOrNull()) }
        wireEditorRowActions(taxRateGrid) { openTaxRateEditor(it) }
        configureEditorFields()
        configureEditorDialog()

        // Initial load
        refreshTaxRateGrid()
    }

    private fun buildStatusBadges(rate: TaxRateDto): HorizontalLayout =
        HorizontalLayout().also { row ->
            row.add(createDefaultBadge(rate.isDefault) { key -> tr(key) })
            if (rate.isReference) row.add(StatusChip(UiTranslations.tr(this, "referenceData.taxRate.isReference"), ChipTone.ACCENT))
            if (rate.isHotelRate) row.add(StatusChip(UiTranslations.tr(this, "referenceData.hotelRate"), ChipTone.OK))
            if (!isTaxRateActive(rate)) row.add(StatusChip(UiTranslations.tr(this, "referenceData.status.expired"), ChipTone.WARN))
            if (!rate.isActive) row.add(StatusChip(UiTranslations.tr(this, "referenceData.taxRate.status.inactive"), ChipTone.ERROR))
            else if (!rate.isSelectable) row.add(StatusChip(UiTranslations.tr(this, "referenceData.taxRate.status.notSelectable"), ChipTone.NEUTRAL))
        }

    private fun applySortOrder(order: com.vaadin.flow.component.grid.GridSortOrder<TaxRateDto>?) {
        if (order == null) {
            currentSortField = null
            currentSortDir = SortDirection.ASC
        } else {
            currentSortField = TaxRateSortField.NAME
            currentSortDir = if (order.direction == com.vaadin.flow.data.provider.SortDirection.ASCENDING) SortDirection.ASC else SortDirection.DESC
        }
        refreshTaxRateGrid()
    }

    private fun configureEditorFields() {
        val required = UiTranslations.tr(this, "referenceData.taxRate.required")

        trNameField.label = UiTranslations.tr(this, "referenceData.name")
        trRateField.label = UiTranslations.tr(this, "referenceData.taxRatePercent")
        trCountryField.label = UiTranslations.tr(this, "referenceData.countryIso")
        trCountryField.maxLength = 2
        trValidFromPicker.label = UiTranslations.tr(this, "referenceData.validFrom")
        trValidToPicker.label = UiTranslations.tr(this, "referenceData.validTo")
        trHotelRateField.label = UiTranslations.tr(this, "referenceData.hotelRate")
        trDefaultField.label = UiTranslations.tr(this, "referenceData.standard")
        trReferenceField.label = UiTranslations.tr(this, "referenceData.taxRate.isReference")

        trCodeField.label = UiTranslations.tr(this, "referenceData.taxRate.code")
        trCodeField.placeholder = "DE_STANDARD_19"
        // A null entry inside a ComboBox<String>'s item list renders blank and breaks the
        // whole dropdown (Vaadin's generateLabel() forces "" for a null item regardless of the
        // custom ItemLabelGenerator, and the default in-memory item filter calls the generator
        // directly, unguarded, for every item). "No category" is expressed as
        // a clearable field over a plain, non-null item list, not a null sentinel in the list.
        trCategoryField.label = UiTranslations.tr(this, "referenceData.taxRate.category")
        trCategoryField.setItems(listOf("S", "AE", "E", "O"))
        trCategoryField.setItemLabelGenerator { UiTranslations.tr(this, "referenceData.taxRate.category.$it") }
        trCategoryField.isClearButtonVisible = true
        trExemptionReasonCodeField.label = UiTranslations.tr(this, "referenceData.taxRate.exemptionReasonCode")
        trExemptionReasonCodeField.placeholder = "VATEX-EU-AE"
        trExemptionReasonTextField.label = UiTranslations.tr(this, "referenceData.taxRate.exemptionReasonText")
        trExemptionReasonTextField.minRows = 2
        trLegalBasisField.label = UiTranslations.tr(this, "referenceData.taxRate.legalBasis")
        trLegalBasisField.minRows = 2
        trActiveField.label = UiTranslations.tr(this, "referenceData.taxRate.isActive")
        trSelectableField.label = UiTranslations.tr(this, "referenceData.taxRate.isSelectable")

        bindRequiredField(trNameField, required, TaxRateFormData::name, TaxRateFormData::name::set)
        trBinder.forField(trRateField)
            .asRequired(required)
            .withValidator({ v -> v != null && v >= BigDecimal.ZERO && v <= BigDecimal("100") }, UiTranslations.tr(this, "referenceData.taxRate.rate.invalid"))
            .bind(TaxRateFormData::rate, TaxRateFormData::rate::set)
        bindRequiredField(trCountryField, required, TaxRateFormData::country, TaxRateFormData::country::set)
        bindField(trValidFromPicker, TaxRateFormData::validFrom, TaxRateFormData::validFrom::set)
        bindField(trValidToPicker, TaxRateFormData::validTo, TaxRateFormData::validTo::set)
        bindField(trHotelRateField, TaxRateFormData::isHotelRate, TaxRateFormData::isHotelRate::set)
        bindField(trDefaultField, TaxRateFormData::isDefault, TaxRateFormData::isDefault::set)
        bindField(trReferenceField, TaxRateFormData::isReference, TaxRateFormData::isReference::set)
        // Code is NOT NULL + UNIQUE at the DB level (uq_tax_rate_code /
        // chk_tax_rate_code_not_blank); without this, an empty save reached the DB as a raw
        // constraint violation instead of a friendly message. Uniqueness itself still isn't
        // checked client-side (would need a port round-trip) — a duplicate code still
        // surfaces as a DB error, unchanged by this fix.
        bindRequiredField(trCodeField, required, TaxRateFormData::code, TaxRateFormData::code::set)
        bindField(trCategoryField, TaxRateFormData::category, TaxRateFormData::category::set)
        bindField(trExemptionReasonCodeField, TaxRateFormData::exemptionReasonCode, TaxRateFormData::exemptionReasonCode::set)
        bindField(trExemptionReasonTextField, TaxRateFormData::exemptionReasonText, TaxRateFormData::exemptionReasonText::set)
        bindField(trLegalBasisField, TaxRateFormData::legalBasis, TaxRateFormData::legalBasis::set)
        bindField(trActiveField, TaxRateFormData::isActive, TaxRateFormData::isActive::set)
        bindField(trSelectableField, TaxRateFormData::isSelectable, TaxRateFormData::isSelectable::set)
    }

    /** The plain, unvalidated counterpart of [bindRequiredField] — ten fields differed only in
     *  which field/property they named, which is what the duplicate-code check flagged. */
    private fun <T> bindField(
        field: com.vaadin.flow.component.AbstractField<*, T>,
        getter: (TaxRateFormData) -> T,
        setter: (TaxRateFormData, T) -> Unit,
    ) {
        trBinder.forField(field).bind(getter, setter)
    }

    private fun <T> bindRequiredField(
        field: com.vaadin.flow.component.AbstractField<*, T>,
        required: String,
        getter: (TaxRateFormData) -> T,
        setter: (TaxRateFormData, T) -> Unit
    ) {
        trBinder.forField(field)
            .asRequired(required)
            .bind(getter, setter)
    }

    private fun configureEditorDialog() {
        initCrudDialogButtons()

        // A manual toggle of the checkbox goes
        // through the confirm; revert on cancel. Programmatic sets (isFromClient == false, e.g.
        // from deactivateButton below) skip this — deactivateButton runs its own confirm first.
        trActiveField.addValueChangeListener { event ->
            if (!event.isFromClient || event.value) return@addValueChangeListener
            val id = currentFormData.id ?: return@addValueChangeListener
            confirmDeactivateTaxRate(id, onConfirmed = {}, onCancelled = { trActiveField.value = true })
        }

        // A rule that's already in use on an invoice can be deactivated (so it stops
        // appearing for new positions) but never deleted, and its technical code becomes
        // read-only — a later edit must not retroactively change what an already-snapshotted
        // invoice item's taxRuleCode refers to.
        deactivateButton.text = UiTranslations.tr(this, "referenceData.taxRate.deactivate")
        deactivateButton.addClickListener {
            val id = currentFormData.id
            val apply = { trActiveField.value = false; trSelectableField.value = false }
            if (id == null) apply() else confirmDeactivateTaxRate(id, onConfirmed = apply, onCancelled = {})
        }

        useDialogSize(AppDialog.Size.M)
        installCrudFooter(deactivateButton)
        val form = FormLayout(
            trNameField, trRateField, trCountryField, trValidFromPicker, trValidToPicker, trDefaultField,
            trReferenceField, trHotelRateField, trCodeField, trCategoryField, trExemptionReasonCodeField,
            trExemptionReasonTextField, trLegalBasisField, trActiveField, trSelectableField,
        ).also {
            it.setResponsiveSteps(
                FormLayout.ResponsiveStep("0", 1),
                FormLayout.ResponsiveStep("480px", 2),
            )
            it.setColspan(trDefaultField, 2)
            it.setColspan(trExemptionReasonTextField, 2)
            it.setColspan(trLegalBasisField, 2)
        }
        val content = VerticalLayout(form).also {
            it.isPadding = false
            it.isSpacing = true
        }
        editorDialog.add(content)
    }

    private fun openTaxRateEditor(rate: TaxRateDto?) {
        // Protects the technical code once a rule has actually been used: an in-use rate's
        // code must not be changed retroactively out from under already-snapshotted invoice items.
        val inUse = rate?.id?.let { taxRateQueries.isTaxRateInUse(it) } ?: false
        trCodeField.isReadOnly = inUse
        openEditor(rate)
    }

    override fun idOf(formData: TaxRateFormData): Long? = formData.id

    override fun formDataFrom(entity: TaxRateDto?): TaxRateFormData = TaxRateFormData.from(entity)

    override fun createWritableForm(id: Long?): TaxRateFormData = TaxRateFormData(id = id)

    override fun readFormData(formData: TaxRateFormData) {
        trBinder.readBean(formData)
    }

    override fun writeValidatedForm(targetFormData: TaxRateFormData): Boolean {
        if (!trBinder.writeValidated(targetFormData)) return false
        // Every tax rate needs a category (tax_rate.category is NOT NULL); the error is shown on
        // the category field itself.
        if (targetFormData.category.isNullOrBlank()) {
            UiNotifications.errorOn(trCategoryField, UiTranslations.tr(this, "referenceData.taxRate.categoryRequired"))
            return false
        }
        return true
    }

    override fun findEntityById(id: Long): TaxRateDto? = taxRateQueries.findTaxRateById(id)

    override fun persistForm(formData: TaxRateFormData, current: TaxRateDto?) {
        manageTaxRate.saveTaxRate(formData.toDto(current?.version))
    }

    override fun deleteEntityById(id: Long) {
        // Hard delete is blocked once a rule is in use; deactivating (see [deactivateButton])
        // is the correct action instead, so an already-referenced tax_rate row/id never vanishes.
        if (taxRateQueries.isTaxRateInUse(id)) {
            throw InvalidParameterException(UiTranslations.tr(this, "referenceData.taxRate.deleteBlocked"))
        }
        manageTaxRate.deleteTaxRate(id)
    }

    override fun refreshGridData() {
        refreshTaxRateGrid()
    }

    override fun newHeaderTitle(): String = UiTranslations.tr(this, "referenceData.newTaxRate")

    override fun editHeaderTitle(): String = UiTranslations.tr(this, "referenceData.editTaxRate")

    private fun refreshTaxRateGrid() {
        val activeOnly = trActiveOnly.value
        refreshGridWithCounts(
            countTotal = { filter -> taxRateQueries.countTaxRates(filter) },
            countInactive = { filter -> taxRateQueries.countInactiveTaxRates(filter) },
            createProvider = { filter ->
                usageCounts = taxRateQueries.findTaxRateUsageCounts()
                createGridDataProvider(
                    fetch = { pageIndex, limit, f ->
                        taxRateQueries.findTaxRates(
                            pageIndex,
                            limit,
                            f,
                            currentSortField?.let { "name" },
                            currentSortDir,
                        ).filter { rate -> !activeOnly || isTaxRateActive(rate) }
                    },
                    count = { f -> taxRateQueries.countTaxRates(f) },
                    filterSupplier = { filter }
                )
            }
        )
    }

    /** The grid's "Verwendung" column value; a named function (not an inline lambda)
     *  so it's directly testable, same idiom as the other tabs' label helpers. */
    private fun usageCountLabel(rate: TaxRateDto): String = (rate.id?.let { usageCounts[it] } ?: 0L).toString()

    private fun isTaxRateActive(rate: TaxRateDto): Boolean = rate.isApplicableFor(today)

    /** Names the usage count instead of a bare
     *  yes/no; a rate that's actually unused deactivates without asking. */
    private fun confirmDeactivateTaxRate(id: Long, onConfirmed: () -> Unit, onCancelled: () -> Unit) {
        val count = usageCounts[id] ?: 0L
        if (count <= 0L) {
            onConfirmed()
            return
        }
        ConfirmDialog(
            UiTranslations.tr(this, "referenceData.taxRate.deactivateConfirm.title"),
            UiTranslations.tr(this, "referenceData.taxRate.deactivateConfirm.text", count),
            UiTranslations.tr(this, "referenceData.taxRate.deactivateConfirm.confirm"),
        ) { onConfirmed() }.also { dialog ->
            dialog.setCancelable(true)
            dialog.setCancelText(UiTranslations.tr(this, "app.cancel"))
            dialog.addCancelListener { onCancelled() }
            dialog.open()
        }
    }
}
