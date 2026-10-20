package de.littletrotter.ui.view.referencedata

import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asTextColumn
import de.littletrotter.ui.component.AppDialog
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.BigDecimalField
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.binder.Binder
import de.littletrotter.application.dto.HotelRateDto
import de.littletrotter.application.dto.HotelRateSortField
import de.littletrotter.application.dto.SortDirection
import de.littletrotter.application.reference.port.inbound.ManageHotelRateUseCase
import de.littletrotter.application.reference.port.inbound.HotelRateQueryPort
import de.littletrotter.shared.Money
import de.littletrotter.ui.component.ChipTone
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.StatusChip
import de.littletrotter.ui.component.asNumericColumn
import de.littletrotter.ui.format.DateFormatter
import de.littletrotter.ui.format.MoneyFormatter
import de.littletrotter.ui.notification.UiNotifications
import java.math.BigDecimal

private const val COUNTRY_KEY = "country"

/** Reference-data tab for the hotel-rate catalogue. Structure mirrors PerDiemRatesTab. */
class HotelRatesTab(
    private val hotelRateQueries: HotelRateQueryPort,
    private val manageHotelRate: ManageHotelRateUseCase,
) : AbstractReferencedataCrudTab<HotelRateDto, HotelRateFormData>(
    DataTable(HotelRateDto::class.java, false),
) {

    private val binder = Binder(HotelRateFormData::class.java)

    private val countryField = TextField()
    private val regionField = TextField()
    private val ratePerNightField = BigDecimalField()
    private val validFromPicker = DatePicker()
    private val validToPicker = DatePicker()
    private val activeField = Checkbox()
    private val sourceField = TextArea()

    // PO decision 14.09.2026 — Referenzdaten grids offer exactly one sortable column; country is
    // both the tab's identifying field and the query's existing default order, so currentSortField
    // never actually varies — kept as a field (not a local constant) for the same shape every
    // other repaired grid uses.
    private var currentSortField: HotelRateSortField = HotelRateSortField.COUNTRY
    private var currentSortDir: SortDirection = SortDirection.ASC

    override var currentFormData = HotelRateFormData()

    init {
        initializeTab()
    }

    override fun getValidationInfoKey(): String = "referenceData.hotel.validationInfo"

    override fun getSearchPlaceholder(): String = "referenceData.hotel.placeholder"

    // Sequencing only — columns, editor fields and the editor dialog each have their own function.
    override fun configureGrid() {
        configureHotelGridColumns()
        configureEditorFields()
        configureEditorDialog()
    }

    private fun configureHotelGridColumns() {
        grid.setSizeFull()
        // PO decision 14.09.2026 — Referenzdaten grids offer exactly one sortable column, the
        // tab's identifying field (country); region/ratePerNight/validFrom lost their sort arrow.
        grid.addColumn(HotelRateDto::country)
            .setHeader(tr("referenceData.countryIso"))
            .asCompactColumn()
            .setSortable(true)
            .setSortProperty(COUNTRY_KEY)
            .key = COUNTRY_KEY
        grid.addColumn { rate: HotelRateDto -> rate.region ?: "—" }
            .setHeader(tr("referenceData.region"))
            .asTextColumn(TextColumnWidth.SMALL)
        grid.addColumn { rate: HotelRateDto -> MoneyFormatter.format(rate.ratePerNight) }
            .setHeader(tr("referenceData.ratePerNight"))
            .asNumericColumn()
        grid.addColumn { rate: HotelRateDto -> DateFormatter.format(rate.validFrom) }
            .setHeader(tr("referenceData.validFrom"))
            .asCompactColumn()
        grid.addColumn { rate: HotelRateDto -> DateFormatter.format(rate.validTo) }
            .setHeader(tr("referenceData.validTo"))
            .asCompactColumn()
        grid.addComponentColumn { rate: HotelRateDto ->
            if (!rate.isActive) StatusChip(tr("referenceData.taxRate.status.inactive"), ChipTone.ERROR)
            else Span()
        }
            .setHeader(tr("referenceData.status"))
            .asCompactColumn()
        grid.addSortListener { event ->
            val order = event.sortOrder.firstOrNull()
            currentSortField = HotelRateSortField.COUNTRY
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

    private fun configureEditorFields() {
        countryField.label = tr("referenceData.countryIso")
        countryField.maxLength = 2
        regionField.label = tr("referenceData.region")
        ratePerNightField.label = tr("referenceData.ratePerNight")
        validFromPicker.label = tr("referenceData.validFrom")
        validToPicker.label = tr("referenceData.validTo")
        activeField.label = tr("referenceData.taxRate.isActive")
        sourceField.label = tr("referenceData.taxRate.legalBasis")
        sourceField.minRows = 2

        binder.forField(countryField)
            .asRequired(tr("referenceData.hotel.required"))
            .bind(HotelRateFormData::country, HotelRateFormData::country::set)
        binder.forField(regionField)
            .bind(HotelRateFormData::region, HotelRateFormData::region::set)
        binder.forField(ratePerNightField)
            .asRequired(tr("referenceData.hotel.required"))
            .withValidator({ v -> isNonNegativeRate(v) }, tr("referenceData.hotel.ratePerNight.invalid"))
            .withConverter({ amount -> toMoneyOrZero(amount) }, { money -> toBigDecimalOrZero(money) })
            .bind(HotelRateFormData::ratePerNight, HotelRateFormData::ratePerNight::set)
        binder.forField(validFromPicker)
            .bind(HotelRateFormData::validFrom, HotelRateFormData::validFrom::set)
        binder.forField(validToPicker)
            .bind(HotelRateFormData::validTo, HotelRateFormData::validTo::set)
        binder.forField(activeField)
            .bind(HotelRateFormData::isActive, HotelRateFormData::isActive::set)
        binder.forField(sourceField)
            .bind(HotelRateFormData::source, HotelRateFormData::source::set)

        initCrudDialogButtons()
    }

    private fun configureEditorDialog() {
        useDialogSize(AppDialog.Size.M)
        installCrudFooter()
        editorDialog.add(
            VerticalLayout(
                FormLayout(countryField, regionField, ratePerNightField, validFromPicker, validToPicker, activeField, sourceField).also {
                    it.setResponsiveSteps(FormLayout.ResponsiveStep("0", 1), FormLayout.ResponsiveStep("480px", 2))
                    it.setColspan(sourceField, 2)
                },
            ).also {
                it.isPadding = false
                it.isSpacing = true
            }
        )
    }

    override fun idOf(formData: HotelRateFormData): Long? = formData.id

    override fun formDataFrom(entity: HotelRateDto?): HotelRateFormData = HotelRateFormData.from(entity)

    override fun createWritableForm(id: Long?): HotelRateFormData = HotelRateFormData(id = id)

    override fun readFormData(formData: HotelRateFormData) {
        binder.readBean(formData)
    }

    override fun writeValidatedForm(targetFormData: HotelRateFormData): Boolean {
        if (!binder.writeValidated(targetFormData)) return false
        // Mirrors chk_hotel_rate_active_needs_source (NOT is_active OR (source IS NOT NULL
        // AND LENGTH(TRIM(source)) > 0)) client-side. isActive defaults true with no required
        // validator on source ("Rechtsgrundlage"), a guaranteed constraint violation on save
        // without this. The error is shown on the source field itself, since
        // the binder cannot span the active flag and the source text.
        if (targetFormData.isActive && targetFormData.source.isBlank()) {
            UiNotifications.errorOn(sourceField, tr("referenceData.hotel.activeRequiresSource"))
            return false
        }
        return true
    }

    override fun findEntityById(id: Long): HotelRateDto? = hotelRateQueries.findHotelRateById(id)

    override fun persistForm(formData: HotelRateFormData, current: HotelRateDto?) {
        manageHotelRate.saveHotelRate(formData.toDto(current?.version))
    }

    override fun deleteEntityById(id: Long) {
        manageHotelRate.deleteHotelRate(id)
    }

    override fun refreshGridData() {
        refreshGrid()
    }

    override fun newHeaderTitle(): String = tr("referenceData.hotel.new")

    override fun editHeaderTitle(): String = tr("referenceData.hotel.edit")

    private fun refreshGrid() {
        refreshGridWithCounts(
            countTotal = { filter -> hotelRateQueries.countHotelRates(filter) },
            countInactive = { filter -> hotelRateQueries.countInactiveHotelRates(filter) },
            createProvider = { filter ->
                createGridDataProvider(
                    fetch = { pageIndex, limit, f ->
                        hotelRateQueries.findHotelRates(
                            pageIndex,
                            limit,
                            f,
                            when (currentSortField) {
                                HotelRateSortField.COUNTRY -> COUNTRY_KEY
                            },
                            currentSortDir,
                        )
                    },
                    count = { f -> hotelRateQueries.countHotelRates(f) },
                    filterSupplier = { filter }
                )
            }
        )
    }

    companion object {
        /** Extracted out of the inline binder lambda so the null arm (never reachable
         *  through the field itself: the preceding `asRequired()` link in the same binding chain
         *  already rejects a blank field before this validator ever runs) is still directly
         *  unit-testable, instead of a permanently dead branch. */
        internal fun isNonNegativeRate(v: BigDecimal?): Boolean = v != null && v >= BigDecimal.ZERO

        /** See [isNonNegativeRate]; the null arm is unreachable via the field (the
         *  validator above already rejected it), but the converter is real production code a
         *  differently-constructed [HotelRateFormData] could still hand a null [Money] to. */
        internal fun toMoneyOrZero(amount: BigDecimal?): Money = amount?.let { Money.of(it) } ?: Money.ZERO

        internal fun toBigDecimalOrZero(money: Money?): BigDecimal = money?.amount ?: BigDecimal.ZERO
    }
}
