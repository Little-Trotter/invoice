package de.littletrotter.ui.view.referencedata

import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asTextColumn
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.ActionBar
import de.littletrotter.ui.component.AppDialog
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.BigDecimalField
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.binder.Binder
import de.littletrotter.application.dto.PerDiemRateDto
import de.littletrotter.application.dto.PerDiemRateSortField
import de.littletrotter.application.dto.SortDirection
import de.littletrotter.application.reference.port.inbound.ManagePerDiemRateUseCase
import de.littletrotter.application.reference.port.inbound.PerDiemRateQueryPort
import de.littletrotter.shared.Money
import de.littletrotter.ui.component.ChipTone
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.StatusChip
import de.littletrotter.ui.component.asNumericColumn
import de.littletrotter.ui.component.createDefaultBadge
import de.littletrotter.ui.component.createInlandBadge
import de.littletrotter.ui.format.DateFormatter
import de.littletrotter.ui.format.MoneyFormatter
import de.littletrotter.ui.notification.UiNotifications
import java.math.BigDecimal

private const val REFERENCE_DATA_COUNTRY_ISO_KEY = "referenceData.countryIso"
private const val PER_DIEM_REQUIRED_KEY = "referenceData.perDiem.required"
private const val COUNTRY_KEY = "country"

class PerDiemRatesTab(
    private val perDiemRateQueries: PerDiemRateQueryPort,
    private val managePerDiemRate: ManagePerDiemRateUseCase,
) : AbstractReferencedataCrudTab<PerDiemRateDto, PerDiemRateFormData>(
    DataTable(PerDiemRateDto::class.java, false),
) {

    private val inlandOnlyField = Checkbox()
    private val binder = Binder(PerDiemRateFormData::class.java)

    private val countryField = TextField()
    private val regionField = TextField()
    private val fullDayRateField = BigDecimalField()
    private val arrivalDepartureRateField = BigDecimalField()
    private val inlandRulesField = Checkbox()
    private val isDefaultField = Checkbox()
    private val validFromPicker = DatePicker()
    private val validToPicker = DatePicker()
    private val activeField = Checkbox()
    private val sourceField = com.vaadin.flow.component.textfield.TextArea()

    // PO decision 14.09.2026 — Referenzdaten grids offer exactly one sortable column; country is
    // both the tab's identifying field and the query's existing default order, so currentSortField
    // never actually varies — kept as a field for the same shape every other repaired grid uses.
    private var currentSortField: PerDiemRateSortField = PerDiemRateSortField.COUNTRY
    private var currentSortDir: SortDirection = SortDirection.ASC

    override var currentFormData = PerDiemRateFormData()

    init {
        initializeTab()
    }

    override fun getValidationInfoKey(): String = "referenceData.perDiem.validationInfo"

    override fun getSearchPlaceholder(): String = "referenceData.perDiem.placeholder"

    override fun buildToolbar(): HorizontalLayout {
        val newButton = AppButtons.primary(tr("referenceData.new"), ActionIcons.ADD) { openEditor(null) }

        inlandOnlyField.label = tr("referenceData.inlandOnly")
        inlandOnlyField.addValueChangeListener { refreshGridData() }

        return ActionBar(listOf(inlandOnlyField), newButton)
    }

    override fun configureGrid() {
        configureGridColumns()
        configureDialogFields()
        configureDialogBindings()
        buildDialogLayout()
    }

    private fun configureGridColumns() {
        grid.setSizeFull()
        // PO decision 14.09.2026 — Referenzdaten grids offer exactly one sortable column, the
        // tab's identifying field (country); the other columns lost their sort arrow.
        grid.addColumn(PerDiemRateDto::country)
            .setHeader(tr(REFERENCE_DATA_COUNTRY_ISO_KEY))
            .asCompactColumn()
            .setSortable(true)
            .setSortProperty(COUNTRY_KEY)
            .key = COUNTRY_KEY
        grid.addColumn { rate: PerDiemRateDto -> rate.region ?: "—" }
            .setHeader(tr("referenceData.region"))
            .asTextColumn(TextColumnWidth.SMALL)
        grid.addColumn { rate: PerDiemRateDto -> MoneyFormatter.format(rate.fullDayRate) }
            .setHeader(tr("referenceData.fullDayRate"))
            .asNumericColumn()
        grid.addColumn { rate: PerDiemRateDto -> MoneyFormatter.format(rate.arrivalDepartureRate) }
            .setHeader(tr("referenceData.arrivalDepartureRate"))
            .asNumericColumn()
        grid.addComponentColumn { rate: PerDiemRateDto -> createInlandBadge(rate.inlandRules) { key -> tr(key) } }
            .setHeader(tr("referenceData.inland"))
            .asCompactColumn()
            .isSortable = false
        grid.addComponentColumn { rate: PerDiemRateDto -> createDefaultBadge(rate.isDefault) { key -> tr(key) } }
            .setHeader(tr("referenceData.perDiem.isDefault"))
            .asCompactColumn()
            .isSortable = false
        grid.addColumn { rate: PerDiemRateDto -> DateFormatter.format(rate.validFrom) }
            .setHeader(tr("referenceData.validFrom"))
            .asCompactColumn()
        grid.addColumn { rate: PerDiemRateDto -> DateFormatter.format(rate.validTo) }
            .setHeader(tr("referenceData.validTo"))
            .asCompactColumn()
        grid.addComponentColumn { rate: PerDiemRateDto ->
            if (!rate.isActive) StatusChip(tr("referenceData.taxRate.status.inactive"), ChipTone.ERROR)
            else Span()
        }
            .setHeader(tr("referenceData.status"))
            .asCompactColumn()
        grid.addSortListener { event ->
            val order = event.sortOrder.firstOrNull()
            currentSortField = PerDiemRateSortField.COUNTRY
            currentSortDir = if (order == null || order.direction == com.vaadin.flow.data.provider.SortDirection.ASCENDING) {
                SortDirection.ASC
            } else {
                SortDirection.DESC
            }
            refreshGrid()
        }
        wireEditorRowActions(grid)
        refreshGrid()
    }

    private fun configureDialogFields() {
        countryField.label = tr(REFERENCE_DATA_COUNTRY_ISO_KEY)
        countryField.maxLength = 2
        countryField.helperText = tr(REFERENCE_DATA_COUNTRY_ISO_KEY)
        regionField.label = tr("referenceData.region")
        fullDayRateField.label = tr("referenceData.fullDayRate")
        fullDayRateField.helperText = tr("referenceData.fullDayHelp")
        arrivalDepartureRateField.label = tr("referenceData.arrivalDepartureRate")
        arrivalDepartureRateField.helperText = tr("referenceData.arrivalDepartureHelp")
        inlandRulesField.label = tr("referenceData.inlandRules")
        inlandRulesField.helperText = tr("referenceData.inlandRulesHelp")
        isDefaultField.label = tr("referenceData.perDiem.isDefault")
        isDefaultField.helperText = tr("referenceData.perDiem.isDefaultHelp")
        validFromPicker.label = tr("referenceData.validFrom")
        validToPicker.label = tr("referenceData.validTo")
        activeField.label = tr("referenceData.taxRate.isActive")
        sourceField.label = tr("referenceData.taxRate.legalBasis")
        sourceField.minRows = 2
    }

    private fun configureDialogBindings() {
        // Same deliberate binder-boilerplate keep as NumberRangesTab — see the comment there
        // (Qodana DuplicatedCode; explicit one-line-per-field wiring beats a reflective helper).
        binder.forField(countryField)
            .asRequired(tr(PER_DIEM_REQUIRED_KEY))
            .bind(PerDiemRateFormData::country, PerDiemRateFormData::country::set)
        binder.forField(regionField)
            .bind(PerDiemRateFormData::region, PerDiemRateFormData::region::set)
        binder.forField(fullDayRateField)
            .asRequired(tr(PER_DIEM_REQUIRED_KEY))
            .withValidator({ v -> isNonNegativeRate(v) }, tr("referenceData.perDiem.fullDayRate.invalid"))
            .withConverter({ amount -> toMoneyOrZero(amount) }, { money -> toBigDecimalOrZero(money) })
            .bind(PerDiemRateFormData::fullDayRate, PerDiemRateFormData::fullDayRate::set)
        binder.forField(arrivalDepartureRateField)
            .asRequired(tr(PER_DIEM_REQUIRED_KEY))
            .withValidator({ v -> isNonNegativeRate(v) }, tr("referenceData.perDiem.arrivalDepartureRate.invalid"))
            .withConverter({ amount -> toMoneyOrZero(amount) }, { money -> toBigDecimalOrZero(money) })
            .bind(PerDiemRateFormData::arrivalDepartureRate, PerDiemRateFormData::arrivalDepartureRate::set)
        binder.forField(inlandRulesField)
            .bind(PerDiemRateFormData::inlandRules, PerDiemRateFormData::inlandRules::set)
        binder.forField(isDefaultField)
            .bind(PerDiemRateFormData::isDefault, PerDiemRateFormData::isDefault::set)
        binder.forField(validFromPicker)
            .bind(PerDiemRateFormData::validFrom, PerDiemRateFormData::validFrom::set)
        binder.forField(validToPicker)
            .bind(PerDiemRateFormData::validTo, PerDiemRateFormData::validTo::set)
        binder.forField(activeField)
            .bind(PerDiemRateFormData::isActive, PerDiemRateFormData::isActive::set)
        binder.forField(sourceField)
            .bind(PerDiemRateFormData::source, PerDiemRateFormData::source::set)
    }

    private fun buildDialogLayout() {
        initCrudDialogButtons()

        useDialogSize(AppDialog.Size.M)
        installCrudFooter()
        editorDialog.add(
            VerticalLayout(
                FormLayout(
                    countryField,
                    regionField,
                    fullDayRateField,
                    arrivalDepartureRateField,
                    validFromPicker,
                    validToPicker,
                    inlandRulesField,
                    isDefaultField,
                    activeField,
                    sourceField,
                ).also {
                    it.setResponsiveSteps(
                        FormLayout.ResponsiveStep("0", 1),
                        FormLayout.ResponsiveStep("480px", 2),
                    )
                    it.setColspan(inlandRulesField, 1)
                    it.setColspan(isDefaultField, 1)
                    it.setColspan(sourceField, 2)
                },
            ).also {
                it.isPadding = false
                it.isSpacing = true
            }
        )
    }

    override fun idOf(formData: PerDiemRateFormData): Long? = formData.id

    override fun formDataFrom(entity: PerDiemRateDto?): PerDiemRateFormData = PerDiemRateFormData.from(entity)

    override fun createWritableForm(id: Long?): PerDiemRateFormData = PerDiemRateFormData(id = id)

    override fun readFormData(formData: PerDiemRateFormData) {
        binder.readBean(formData)
    }

    override fun writeValidatedForm(targetFormData: PerDiemRateFormData): Boolean {
        if (!binder.writeValidated(targetFormData)) return false
        // Mirrors chk_per_diem_rate_active_needs_source (NOT is_active OR (source IS NOT
        // NULL AND LENGTH(TRIM(source)) > 0)) client-side. isActive defaults true with no
        // required validator on source ("Rechtsgrundlage"), a guaranteed constraint violation on
        // save without this. The error is shown on the source field itself, since
        // the binder cannot span the active flag and the source text.
        if (targetFormData.isActive && targetFormData.source.isBlank()) {
            UiNotifications.errorOn(sourceField, tr("referenceData.perDiem.activeRequiresSource"))
            return false
        }
        return true
    }

    override fun findEntityById(id: Long): PerDiemRateDto? = perDiemRateQueries.findPerDiemRateById(id)

    override fun persistForm(formData: PerDiemRateFormData, current: PerDiemRateDto?) {
        managePerDiemRate.savePerDiemRate(formData.toDto(current?.version))
    }

    override fun deleteEntityById(id: Long) {
        managePerDiemRate.deletePerDiemRate(id)
    }

    override fun refreshGridData() {
        refreshGrid()
    }

    override fun newHeaderTitle(): String = tr("referenceData.newPerDiem")

    override fun editHeaderTitle(): String = tr("referenceData.editPerDiem")

    private fun refreshGrid() {
        val inlandOnly = inlandOnlyField.value
        refreshGridWithCounts(
            countTotal = { filter -> perDiemRateQueries.countPerDiemRates(filter) },
            countInactive = { filter -> perDiemRateQueries.countInactivePerDiemRates(filter) },
            createProvider = { filter ->
                createGridDataProvider(
                    fetch = { pageIndex, limit, f ->
                        perDiemRateQueries.findPerDiemRates(
                            pageIndex,
                            limit,
                            f,
                            when (currentSortField) {
                                PerDiemRateSortField.COUNTRY -> COUNTRY_KEY
                            },
                            currentSortDir,
                        ).filter { rate -> !inlandOnly || rate.inlandRules }
                    },
                    count = { f -> perDiemRateQueries.countPerDiemRates(f) },
                    filterSupplier = { filter }
                )
            }
        )
    }

    companion object {
        /** Extracted out of the inline binder lambdas so the null arm (never reachable
         *  through either field itself: the preceding `asRequired()` link in the same binding
         *  chain already rejects a blank field before this validator ever runs) is still directly
         *  unit-testable, instead of a permanently dead branch. Shared by both rate fields, same
         *  as [HotelRatesTab]'s own copy. */
        internal fun isNonNegativeRate(v: BigDecimal?): Boolean = v != null && v >= BigDecimal.ZERO

        /** See [isNonNegativeRate]; the null arm is unreachable via either field (the
         *  validator above already rejected it), but the converter is real production code a
         *  differently-constructed [PerDiemRateFormData] could still hand a null [Money] to. */
        internal fun toMoneyOrZero(amount: BigDecimal?): Money = amount?.let { Money.of(it) } ?: Money.ZERO

        internal fun toBigDecimalOrZero(money: Money?): BigDecimal = money?.amount ?: BigDecimal.ZERO
    }
}
