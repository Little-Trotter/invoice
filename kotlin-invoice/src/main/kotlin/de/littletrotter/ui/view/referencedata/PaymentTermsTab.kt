package de.littletrotter.ui.view.referencedata

import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asTextColumn
import de.littletrotter.ui.component.AppDialog
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.BigDecimalField
import com.vaadin.flow.component.textfield.IntegerField
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.binder.Binder
import de.littletrotter.application.dto.LanguageDto
import de.littletrotter.application.dto.PaymentTermDto
import de.littletrotter.application.dto.PaymentTermSortField
import de.littletrotter.application.dto.SortDirection
import de.littletrotter.application.invoice.port.inbound.MultilingualTextsAvailabilityQueryPort
import de.littletrotter.application.reference.port.inbound.LanguageQueryPort
import de.littletrotter.application.reference.port.inbound.ManagePaymentTermUseCase
import de.littletrotter.application.reference.port.inbound.PaymentTermQueryPort
import de.littletrotter.domain.reference.LocalizedTextPurpose
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.createDefaultBadge
import de.littletrotter.ui.notification.UiNotifications
import java.math.BigDecimal

private const val PAYMENT_TERM_REQUIRED_KEY = "referenceData.paymentTerm.required"

class PaymentTermsTab(
    private val paymentTermQueries: PaymentTermQueryPort,
    private val managePaymentTerm: ManagePaymentTermUseCase,
    languageQueries: LanguageQueryPort,
    private val multilingualTextsFeatureConfig: MultilingualTextsAvailabilityQueryPort = MultilingualTextsAvailabilityQueryPort.DISABLED,
) : AbstractReferencedataCrudTab<PaymentTermDto, PaymentTermFormData>(
    DataTable(PaymentTermDto::class.java, false),
) {

    private val binder = Binder(PaymentTermFormData::class.java)

    /**
     * Active languages, fallback (`de`) first, then every other active language in
     * [LanguageDto.sortOrder]. Mirrors [de.littletrotter.ui.view.masterdata.SenderProfileView]'s
     * `invoiceNoteLanguagesOrdered`: explicitly fallback-first rather than trusting seed ordering.
     */
    private val nameLanguagesOrdered: List<LanguageDto> = languageQueries.findActiveLanguages().let { languages ->
        val fallback = languages.filter { it.isFallback }
        fallback + languages.filterNot { it.isFallback }
    }

    /**
     * One name field per active language, built once from [nameLanguagesOrdered]: never
     * two hard-coded fields (the former `nameField`/`englishNameField`), so a third active
     * language renders a third field with no code change. The fallback language's field is
     * unconditional and mandatory (`PaymentTerm.validate`); every other language's field is built
     * only when [multilingualTextsFeatureConfig] is enabled — not added and hidden, the same
     * "absent from the DOM, not merely invisible" rule the seller-note field also follows.
     */
    private val nameFieldsByLanguage: Map<LanguageDto, TextField> =
        nameLanguagesOrdered
            .filter { it.isFallback || multilingualTextsFeatureConfig.enabled }
            .associateWith { language ->
                TextField().apply {
                    maxLength = LocalizedTextPurpose.PAYMENT_TERM_NAME.maxLength
                    setWidthFull()
                    if (language.isFallback) {
                        label = tr("referenceData.name")
                    } else {
                        label = tr("referenceData.paymentTerm.name.additional.label", language.code.uppercase())
                        helperText = tr("referenceData.paymentTerm.name.additional.helper")
                    }
                }
            }

    private val descriptionField = TextArea()
    private val dueInDaysField = IntegerField()
    private val discountPercentField = BigDecimalField()
    private val discountDaysField = IntegerField()
    private val defaultField = Checkbox()
    private val activeField = Checkbox()

    private var currentSortField: PaymentTermSortField = PaymentTermSortField.NAME
    private var currentSortDir: SortDirection = SortDirection.ASC
    private var usageCounts: Map<Long, Long> = emptyMap()

    override var currentFormData = PaymentTermFormData()

    init {
        initializeTab()
    }

    override fun getValidationInfoKey(): String = "referenceData.paymentTerm.validationInfo"

    override fun getSearchPlaceholder(): String = "referenceData.paymentTerm.placeholder"

    override fun configureGrid() {
        configureGridColumns()
        configureDialogFieldMetadata()
        configureDialogBindings()
        buildDialogLayout()
    }

    private fun configureGridColumns() {
        grid.setSizeFull()
        // The fallback (German) name; PanachePaymentTermRepository.findAll sorts/filters
        // "name" against this same resolved value (payment_term.name is no longer a plain column).
        grid.addColumn { it.nameFor("de").orEmpty() }
            .setHeader(tr("referenceData.name"))
            .setSortable(true)
            .setSortProperty("name")
            .setKey("name")
            .asTextColumn(TextColumnWidth.MEDIUM)
        grid.addColumn { it.rawNameFor("en").orEmpty() }
            .setHeader(tr("referenceData.paymentTerm.englishName"))
            .setKey("englishName")
            .asTextColumn(TextColumnWidth.MEDIUM)
        // PO decision 14.09.2026 — Referenzdaten grids offer exactly one sortable column (the
        // name column above); dueInDays' own sort was removed together with its SortField entry.
        grid.addColumn { "${it.dueInDays} ${tr("referenceData.days")}" }
            .setHeader(tr("referenceData.paymentDueDays"))
            .asCompactColumn()
        grid.addColumn { formatDiscount(it) }
            .setHeader(tr("referenceData.paymentDiscount"))
            .asCompactColumn()
        grid.addComponentColumn { createDefaultBadge(it.isDefault) { key -> tr(key) } }
            .setHeader(tr("referenceData.standard"))
            .setSortable(false)
            .asCompactColumn()
        grid.addColumn { if (it.isActive) tr("referenceData.paymentTerm.active") else tr("referenceData.paymentTerm.inactive") }
            .setHeader(tr("referenceData.paymentTerm.activeColumn"))
            .setSortable(false)
            .asCompactColumn()
        // Reads the pre-fetched usageCounts map
        // (refreshed by refreshGrid), not a per-row port call.
        grid.addColumn { term -> usageCountLabel(term) }
            .setHeader(tr("referenceData.paymentTerm.usageCount"))
            .asCompactColumn()
        grid.addSortListener { event ->
            val order = event.sortOrder.firstOrNull()
            if (order == null) {
                currentSortField = PaymentTermSortField.NAME
                currentSortDir = SortDirection.ASC
            } else {
                currentSortField = PaymentTermSortField.NAME
                currentSortDir = if (order.direction == com.vaadin.flow.data.provider.SortDirection.ASCENDING) SortDirection.ASC else SortDirection.DESC
            }
            refreshGrid()
        }
        wireEditorRowActions(grid)
        refreshGrid()
    }

    private fun configureDialogFieldMetadata() {
        descriptionField.label = tr("referenceData.description")
        dueInDaysField.label = tr("referenceData.paymentDueDaysField")
        dueInDaysField.helperText = tr("referenceData.paymentDueDaysHelp")
        discountPercentField.label = tr("referenceData.discountPercent")
        discountPercentField.helperText = tr("referenceData.discountPercentHelp")
        discountDaysField.label = tr("referenceData.discountDays")
        discountDaysField.helperText = tr("referenceData.discountDaysHelp")
        defaultField.label = tr("referenceData.defaultPaymentTerm")
        activeField.label = tr("referenceData.paymentTerm.activeField")
    }

    private fun configureDialogBindings() {
        // The fallback-language name field is bound directly, not through [binder]: it is
        // read/written outside the plain-field binder entirely (see [readFormData]/
        // [writeValidatedForm]), the same shape the invoiceNoteFieldsByLanguage uses for the
        // seller note. `asRequired` is enforced there instead, via the fallback field's own
        // emptiness check.
        binder.forField(descriptionField)
            .bind(PaymentTermFormData::description, PaymentTermFormData::description::set)
        binder.forField(dueInDaysField)
            .asRequired(tr(PAYMENT_TERM_REQUIRED_KEY))
            .withValidator({ value -> value != null && value >= 0 }, tr("referenceData.paymentTerm.dueDays.invalid"))
            .bind(PaymentTermFormData::dueInDays, PaymentTermFormData::dueInDays::set)
        binder.forField(discountPercentField)
            .withValidator({ value -> value == null || (value >= BigDecimal.ZERO && value <= BigDecimal("100")) }, tr("referenceData.paymentTerm.discountPercent.invalid"))
            .bind(PaymentTermFormData::discountPercent, PaymentTermFormData::discountPercent::set)
        binder.forField(discountDaysField)
            .withValidator({ value -> value == null || value >= 0 }, tr("referenceData.paymentTerm.discountDays.invalid"))
            .bind(PaymentTermFormData::discountDays, PaymentTermFormData::discountDays::set)
        binder.forField(defaultField)
            .bind(PaymentTermFormData::isDefault, PaymentTermFormData::isDefault::set)
        binder.forField(activeField)
            .bind(PaymentTermFormData::isActive, PaymentTermFormData::isActive::set)

        // Same isFromClient/revert-on-cancel idiom
        // as B2bProfilesTab's activeField listener; the count decision itself lives in
        // deactivateConfirmCount so it's testable without a live ConfirmDialog.
        activeField.addValueChangeListener { event ->
            if (!event.isFromClient || event.value) return@addValueChangeListener
            val count = deactivateConfirmCount(currentFormData.id) ?: return@addValueChangeListener
            ConfirmDialog(
                tr("referenceData.paymentTerm.deactivateConfirm.title"),
                tr("referenceData.paymentTerm.deactivateConfirm.text", count),
                tr("referenceData.paymentTerm.deactivateConfirm.confirm"),
            ) {
            }.also { dialog ->
                dialog.setCancelable(true)
                dialog.setCancelText(tr("app.cancel"))
                dialog.addCancelListener { activeField.value = true }
                dialog.open()
            }
        }
    }

    private fun buildDialogLayout() {
        initCrudDialogButtons()

        val form = FormLayout(*nameFieldsByLanguage.values.toTypedArray(), descriptionField, dueInDaysField, discountPercentField, discountDaysField, defaultField, activeField).also {
            it.setResponsiveSteps(
                FormLayout.ResponsiveStep("0", 1),
                FormLayout.ResponsiveStep("640px", 2),
            )
            it.setColspan(descriptionField, 2)
            it.setColspan(defaultField, 2)
            it.setColspan(activeField, 2)
        }

        useDialogSize(AppDialog.Size.M)
        installCrudFooter()
        editorDialog.add(
            VerticalLayout(
                form,
            ).also {
                it.isPadding = false
                it.isSpacing = true
            }
        )
    }

    override fun idOf(formData: PaymentTermFormData): Long? = formData.id

    override fun formDataFrom(entity: PaymentTermDto?): PaymentTermFormData = PaymentTermFormData.from(entity)

    override fun createWritableForm(id: Long?): PaymentTermFormData = PaymentTermFormData(id = id)

    override fun readFormData(formData: PaymentTermFormData) {
        binder.readBean(formData)
        // One field per rendered language; a language whose field the feature flag hid
        // simply keeps blank (there is nothing to show, and nothing this field could edit anyway).
        nameFieldsByLanguage.forEach { (language, field) ->
            field.value = formData.namesByLanguageCode[language.code].orEmpty()
        }
    }

    override fun writeValidatedForm(targetFormData: PaymentTermFormData): Boolean {
        if (!binder.writeValidated(targetFormData)) return false
        if (!validateDiscountCombination(targetFormData)) return false
        // The fallback (German) name is mandatory (PaymentTerm.validate's own rule),
        // enforced here since it is no longer bound through [binder]'s asRequired.
        val fallbackField = nameLanguagesOrdered.firstOrNull { it.isFallback }?.let { nameFieldsByLanguage[it] }
        if (fallbackField?.value.isNullOrBlank()) {
            fallbackField?.let { UiNotifications.errorOn(it, tr(PAYMENT_TERM_REQUIRED_KEY)) }
            return false
        }
        // Read back only the rendered fields (see readFormData's own comment on why a
        // flag-hidden language is never represented here at all).
        targetFormData.namesByLanguageCode = nameFieldsByLanguage
            .mapKeys { (language, _) -> language.code }
            .mapValues { (_, field) -> field.value }
            .toMutableMap()
        return true
    }

    override fun findEntityById(id: Long): PaymentTermDto? = paymentTermQueries.findPaymentTermById(id)

    override fun persistForm(formData: PaymentTermFormData, current: PaymentTermDto?) {
        managePaymentTerm.savePaymentTerm(formData.toDto(current?.version))
    }

    override fun deleteEntityById(id: Long) {
        managePaymentTerm.deletePaymentTerm(id)
    }

    override fun refreshGridData() {
        refreshGrid()
    }

    override fun newHeaderTitle(): String = tr("referenceData.newPaymentTerm")

    override fun editHeaderTitle(): String = tr("referenceData.editPaymentTerm")

    private fun validateDiscountCombination(formData: PaymentTermFormData): Boolean {
        val hasPercent = formData.discountPercent != null
        val hasDays = formData.discountDays != null
        if (hasPercent != hasDays) {
            val message = tr("referenceData.paymentTerm.discountCombination.invalid")
            if (hasPercent) UiNotifications.errorOn(discountDaysField, message) else UiNotifications.errorOn(discountPercentField, message)
            return false
        }
        return true
    }

    private fun refreshGrid() {
        refreshGridWithCounts(
            countTotal = { filter -> paymentTermQueries.countPaymentTerms(filter) },
            countInactive = { filter -> paymentTermQueries.countInactivePaymentTerms(filter) },
            createProvider = { filter ->
                usageCounts = paymentTermQueries.findPaymentTermUsageCounts()
                createGridDataProvider(
                    fetch = { pageIndex, limit, f ->
                        paymentTermQueries.findPaymentTerms(
                            pageIndex,
                            limit,
                            f,
                            when (currentSortField) {
                                PaymentTermSortField.NAME -> "name"
                            },
                            currentSortDir
                        )
                    },
                    count = { f -> paymentTermQueries.countPaymentTerms(f) },
                    filterSupplier = { filter }
                )
            }
        )
    }

    private fun usageCountLabel(term: PaymentTermDto): String = (term.id?.let { usageCounts[it] } ?: 0L).toString()

    private fun deactivateConfirmCount(id: Long?): Long? {
        val termId = id ?: return null
        return (usageCounts[termId] ?: 0L).takeIf { it > 0L }
    }

    private fun formatDiscount(term: PaymentTermDto): String {
        return if (term.discountDays != null && term.discountPercent.value > BigDecimal.ZERO) {
            "${term.discountPercent.value.stripTrailingZeros().toPlainString()} % / ${term.discountDays} ${tr("referenceData.days")}"
        } else {
            "—"
        }
    }
}
