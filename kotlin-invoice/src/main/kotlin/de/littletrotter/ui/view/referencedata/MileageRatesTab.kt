package de.littletrotter.ui.view.referencedata

import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asTextColumn
import de.littletrotter.ui.component.AppDialog
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.BigDecimalField
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.data.binder.Binder
import de.littletrotter.application.dto.MileageRateDto
import de.littletrotter.application.dto.MileageRateSortField
import de.littletrotter.application.dto.SortDirection
import de.littletrotter.application.reference.port.inbound.ManageMileageRateUseCase
import de.littletrotter.application.reference.port.inbound.MileageRateQueryPort
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.VehicleType
import de.littletrotter.ui.component.ChipTone
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.StatusChip
import de.littletrotter.ui.component.asNumericColumn
import de.littletrotter.ui.format.DateFormatter
import de.littletrotter.ui.format.MoneyFormatter
import java.math.BigDecimal
import java.time.LocalDate

private const val VEHICLE_TYPE_KEY = "vehicleType"

class MileageRatesTab(
    private val mileageRateQueries: MileageRateQueryPort,
    private val manageMileageRate: ManageMileageRateUseCase,
) : AbstractReferencedataCrudTab<MileageRateDto, MileageFormData>(
    DataTable(MileageRateDto::class.java, false),
) {

    private val binder = Binder(MileageFormData::class.java)

    private val vehicleTypeField = ComboBox<VehicleType>()
    private val rateField = BigDecimalField()
    private val validFromPicker = DatePicker()
    private val validToPicker = DatePicker()
    private val activeField = Checkbox()
    private val sourceField = TextArea()

    private var currentSortField: MileageRateSortField = MileageRateSortField.COUNTRY
    private var currentSortDir: SortDirection = SortDirection.ASC

    override var currentFormData = MileageFormData()

    init {
        initializeTab()
    }

    override fun getValidationInfoKey(): String = "referenceData.mileage.validationInfo"

    override fun getSearchPlaceholder(): String = "referenceData.mileage.placeholder"

    override fun configureGrid() {
        grid.setSizeFull()
        configureGridColumns()
        configureSortListener()
        wireEditorRowActions(grid)
        refreshGrid()

        configureFormFields()
        configureFormBindings()
        initCrudDialogButtons()
        configureEditorDialog()
    }

    private fun configureGridColumns() {
        // PO decision 14.09.2026 — Referenzdaten grids offer exactly one sortable column, the
        // tab's identifying field. Here that's this vehicle-type column, not ratePerKm/validFrom
        // any more; see MileageRateSortField.VEHICLE_TYPE's KDoc for why sorting by the
        // ordinal-persisted enum already matches the rendered label order.
        grid.addColumn { rate: MileageRateDto -> vehicleTypeColumnLabel(rate) }
            .setHeader(tr("referenceData.mileage.vehicleType"))
            .asTextColumn(TextColumnWidth.MEDIUM)
            .setSortable(true)
            .setSortProperty(VEHICLE_TYPE_KEY)
            .key = VEHICLE_TYPE_KEY
        grid.addColumn { rate: MileageRateDto -> MoneyFormatter.format(rate.ratePerKm) }
            .setHeader(tr("referenceData.ratePerKm"))
            .asNumericColumn()
        grid.addColumn { rate: MileageRateDto -> DateFormatter.format(rate.validFrom) }
            .setHeader(tr("referenceData.validFrom"))
            .asCompactColumn()
        grid.addColumn { rate: MileageRateDto -> DateFormatter.format(rate.validTo) }
            .setHeader(tr("referenceData.validTo"))
            .asCompactColumn()
        grid.addComponentColumn { rate ->
            if (!rate.isActive) StatusChip(tr("referenceData.taxRate.status.inactive"), ChipTone.ERROR)
            else Span()
        }
            .setHeader(tr("referenceData.status"))
            .asCompactColumn()
    }

    private fun vehicleTypeColumnLabel(rate: MileageRateDto): String = when (rate.vehicleType) {
        VehicleType.CAR -> tr("invoiceDetail.travel.mileage.vehicleType.car")
        VehicleType.OTHER_MOTOR_VEHICLE -> tr("invoiceDetail.travel.mileage.vehicleType.other")
        null -> "${rate.country?.alpha2 ?: "—"} (${tr("referenceData.mileage.legacy")})"
    }

    private fun configureSortListener() {
        grid.addSortListener { event ->
            val order = event.sortOrder.firstOrNull()
            if (order == null) {
                currentSortField = MileageRateSortField.COUNTRY
                currentSortDir = SortDirection.ASC
            } else {
                currentSortField = when (order.sorted.key) {
                    VEHICLE_TYPE_KEY -> MileageRateSortField.VEHICLE_TYPE
                    else -> MileageRateSortField.COUNTRY
                }
                currentSortDir = if (order.direction == com.vaadin.flow.data.provider.SortDirection.ASCENDING) SortDirection.ASC else SortDirection.DESC
            }
            refreshGrid()
        }
    }

    private fun configureFormFields() {
        vehicleTypeField.label = tr("referenceData.mileage.vehicleType")
        vehicleTypeField.setItems(VehicleType.CAR, VehicleType.OTHER_MOTOR_VEHICLE)
        vehicleTypeField.setItemLabelGenerator {
            when (it) {
                VehicleType.CAR -> tr("invoiceDetail.travel.mileage.vehicleType.car")
                VehicleType.OTHER_MOTOR_VEHICLE -> tr("invoiceDetail.travel.mileage.vehicleType.other")
            }
        }
        rateField.label = tr("referenceData.ratePerKm")
        rateField.helperText = tr("referenceData.ratePerKmHelp")
        validFromPicker.label = tr("referenceData.validFrom")
        validToPicker.label = tr("referenceData.validTo")
        activeField.label = tr("referenceData.taxRate.isActive")
        sourceField.label = tr("referenceData.taxRate.legalBasis")
        sourceField.minRows = 2
    }

    private fun configureFormBindings() {
        binder.forField(vehicleTypeField)
            .asRequired(tr("referenceData.mileage.required"))
            .bind(MileageFormData::vehicleType, MileageFormData::vehicleType::set)
        binder.forField(rateField)
            .asRequired(tr("referenceData.mileage.required"))
            .withValidator({ value -> value != null && value >= BigDecimal.ZERO }, tr("referenceData.mileage.ratePerKm.invalid"))
            .bind(MileageFormData::ratePerKm, MileageFormData::ratePerKm::set)
        binder.forField(validFromPicker).bind(MileageFormData::validFrom, MileageFormData::validFrom::set)
        binder.forField(validToPicker).bind(MileageFormData::validTo, MileageFormData::validTo::set)
        binder.forField(activeField).bind(MileageFormData::isActive, MileageFormData::isActive::set)
        binder.forField(sourceField).bind(MileageFormData::source, MileageFormData::source::set)
    }

    private fun configureEditorDialog() {
        useDialogSize(AppDialog.Size.M)
        installCrudFooter()
        editorDialog.add(
            VerticalLayout(
                FormLayout(vehicleTypeField, rateField, validFromPicker, validToPicker, activeField, sourceField).also {
                    it.setResponsiveSteps(FormLayout.ResponsiveStep("0", 1), FormLayout.ResponsiveStep("480px", 2))
                    it.setColspan(sourceField, 2)
                },
            )
        )
    }

    override fun idOf(formData: MileageFormData): Long? = formData.id

    override fun formDataFrom(entity: MileageRateDto?): MileageFormData = MileageFormData.from(entity)

    override fun createWritableForm(id: Long?): MileageFormData = MileageFormData(id = id)

    override fun readFormData(formData: MileageFormData) {
        binder.readBean(formData)
    }

    override fun writeValidatedForm(targetFormData: MileageFormData): Boolean =
        binder.writeValidated(targetFormData)

    override fun findEntityById(id: Long): MileageRateDto? = mileageRateQueries.findMileageRateById(id)

    override fun persistForm(formData: MileageFormData, current: MileageRateDto?) {
        manageMileageRate.saveMileageRate(formData.toDto(current?.version))
    }

    override fun deleteEntityById(id: Long) {
        manageMileageRate.deleteMileageRate(id)
    }

    override fun refreshGridData() {
        refreshGrid()
    }

    override fun newHeaderTitle(): String = tr("referenceData.mileage.new")

    override fun editHeaderTitle(): String = tr("referenceData.mileage.edit")

    private fun refreshGrid() {
        refreshGridWithCounts(
            countTotal = { filter -> mileageRateQueries.countMileageRates(filter) },
            countInactive = { filter -> mileageRateQueries.countInactiveMileageRates(filter) },
            createProvider = { filter ->
                createGridDataProvider(
                    fetch = { pageIndex, limit, f ->
                        mileageRateQueries.findMileageRates(
                            pageIndex,
                            limit,
                            f,
                            when (currentSortField) {
                                MileageRateSortField.COUNTRY -> "country"
                                MileageRateSortField.VEHICLE_TYPE -> VEHICLE_TYPE_KEY
                            },
                            currentSortDir,
                        )
                    },
                    count = { f -> mileageRateQueries.countMileageRates(f) },
                    filterSupplier = { filter }
                )
            }
        )
    }
}

// Plain `class`, not a `data class`: bound by a Vaadin Binder via setter references
// (same rationale as SenderProfileFormData).
class MileageFormData(
    var id: Long? = null,
    // No longer user-facing (the mask shows vehicleType, not country); kept only so an
    // edited legacy row (country set, vehicleType null) round-trips its country unchanged.
    var country: String = "",
    var vehicleType: VehicleType? = null,
    var ratePerKm: BigDecimal? = null,
    var validFrom: LocalDate? = null,
    var validTo: LocalDate? = null,
    var isActive: Boolean = true,
    var source: String = "",
) {
    /** The row to save; [version] is the revision the edit started from. */
    fun toDto(version: Long?): MileageRateDto = MileageRateDto(
        id = id,
        version = version,
        // Every NEW row is German (the mileage rate no longer depends on the destination
        // country at all); a legacy row being edited keeps its own country untouched.
        country = Country.fromAlpha2(country) ?: Country.DE,
        vehicleType = vehicleType,
        ratePerKm = ratePerKm ?: BigDecimal.ZERO,
        validFrom = validFrom,
        validTo = validTo,
        isActive = isActive,
        source = source.trim().ifBlank { null },
    )

    companion object {
        fun from(rate: MileageRateDto?): MileageFormData = if (rate == null) {
            MileageFormData(country = "DE")
        } else {
            MileageFormData(
                id = rate.id,
                country = rate.country?.alpha2 ?: "DE",
                vehicleType = rate.vehicleType,
                ratePerKm = rate.ratePerKm,
                validFrom = rate.validFrom,
                validTo = rate.validTo,
                isActive = rate.isActive,
                source = rate.source.orEmpty(),
            )
        }
    }
}
