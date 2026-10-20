package de.littletrotter.ui.view.referencedata

import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asTextColumn
import de.littletrotter.ui.component.AppDialog
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.IntegerField
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.binder.Binder
import de.littletrotter.application.dto.NumberRangeDto
import de.littletrotter.application.quote.port.inbound.QuoteAvailabilityQueryPort
import de.littletrotter.application.reference.port.inbound.ManageNumberRangeUseCase
import de.littletrotter.application.reference.port.inbound.NumberRangeQueryPort
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.reference.Continuity
import de.littletrotter.domain.reference.CounterScope
import de.littletrotter.shared.numbering.DatePart
import de.littletrotter.shared.numbering.NumberFormatter
import de.littletrotter.shared.numbering.NumberPattern
import de.littletrotter.domain.reference.NumberRangeSubject
import de.littletrotter.domain.reference.PrefixDataField
import de.littletrotter.domain.reference.PrefixSource
import de.littletrotter.shared.numbering.ResetCycle
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.createDefaultBadge
import de.littletrotter.ui.component.ellipsizedTextColumn
import java.time.LocalDate

private const val EXAMPLE_YEAR = 2026
private const val EXAMPLE_MONTH = 8
private const val EXAMPLE_DAY = 21

/**
 * Number ranges as reference data.
 * The list shows subject, structure as an **example number** (`NumberFormatter.compose` with a
 * fixed date and an unused counter), and the current counter value — an example number says more
 * than five enumeration fields and surfaces misconfiguration before it hits an invoice.
 */
class NumberRangesTab(
    private val numberRangeQueries: NumberRangeQueryPort,
    private val manageNumberRange: ManageNumberRangeUseCase,
    private val quoteAvailability: QuoteAvailabilityQueryPort,
) : AbstractReferencedataCrudTab<NumberRangeDto, NumberRangeFormData>(
    DataTable(NumberRangeDto::class.java, false),
) {

    private val binder = Binder(NumberRangeFormData::class.java)

    private val codeField = TextField()
    private val displayNameField = TextField()
    private val subjectField = ComboBox<NumberRangeSubject>()
    private val startValueField = IntegerField()
    private val widthField = IntegerField()
    private val prefixSourceField = ComboBox<PrefixSource>()
    private val fixedPrefixField = TextField()
    private val prefixDataFieldField = ComboBox<PrefixDataField>()
    private val datePartField = ComboBox<DatePart>()
    private val resetCycleField = ComboBox<ResetCycle>()
    private val scopeField = ComboBox<CounterScope>()
    private val continuityField = ComboBox<Continuity>()
    private val activeField = Checkbox()
    private val defaultField = Checkbox()
    private val quoteCounterHint = Span()

    override var currentFormData = NumberRangeFormData()

    init {
        initializeTab()
    }

    override fun getValidationInfoKey(): String = "referenceData.numberRange.info"

    override fun getSearchPlaceholder(): String = "referenceData.numberRange.placeholder"

    override fun configureGrid() {
        configureGridColumns()
        configureFieldLabelsAndItems()
        configureBinder()
        wireFieldListeners()
        initCrudDialogButtons()
        buildEditorDialog()
    }

    private fun configureGridColumns() {
        grid.setSizeFull()
        grid.addColumn { enumLabel(it.subject) }.setHeader(tr("referenceData.numberRange.subject")).setSortable(false).asCompactColumn()
        // displayName is user-entered free text; ellipsized with the full value as a
        // mouse-over tooltip instead of letting it clip silently.
        grid.ellipsizedTextColumn { it.displayName }.setHeader(tr("referenceData.name")).asTextColumn(TextColumnWidth.MEDIUM)
        grid.addColumn { exampleNumber(it) }.setHeader(tr("referenceData.numberRange.example")).asTextColumn(TextColumnWidth.SMALL)
        grid.addColumn { numberRangeQueries.numberRangeCurrentValue(it.subject)?.toString() ?: "—" }
            .setHeader(tr("referenceData.numberRange.counter")).asCompactColumn()
        grid.addComponentColumn { createDefaultBadge(it.isDefault) { key -> tr(key) } }.setHeader(tr("referenceData.standard")).asCompactColumn()
        grid.addColumn { if (it.isActive) tr("referenceData.paymentTerm.active") else tr("referenceData.paymentTerm.inactive") }
            .setHeader(tr("referenceData.paymentTerm.activeColumn")).asCompactColumn()
        wireEditorRowActions(grid)
        refresh()
    }

    private fun configureFieldLabelsAndItems() {
        codeField.label = tr("referenceData.numberRange.code")
        displayNameField.label = tr("referenceData.name")
        subjectField.label = tr("referenceData.numberRange.subject")
        // Quote ranges can only be created while the offer module is part of the instance; existing
        // ones stay listed and editable either way.
        subjectField.setItems(selectableSubjects())
        subjectField.setItemLabelGenerator { enumLabel(it) }
        startValueField.label = tr("referenceData.numberRange.startValue")
        widthField.label = tr("referenceData.numberRange.counterWidth")
        prefixSourceField.label = tr("referenceData.numberRange.prefixSource")
        prefixSourceField.setItems(PrefixSource.entries)
        prefixSourceField.setItemLabelGenerator { enumLabel(it) }
        fixedPrefixField.label = tr("referenceData.numberRange.fixedPrefix")
        prefixDataFieldField.label = tr("referenceData.numberRange.prefixDataField")
        prefixDataFieldField.setItems(prefixDataFieldsFor(subjectField.value))
        prefixDataFieldField.setItemLabelGenerator { enumLabel(it) }
        datePartField.label = tr("referenceData.numberRange.datePart")
        datePartField.setItems(DatePart.entries)
        datePartField.setItemLabelGenerator { enumLabel(it) }
        resetCycleField.label = tr("referenceData.numberRange.resetCycle")
        resetCycleField.setItems(ResetCycle.entries)
        resetCycleField.setItemLabelGenerator { enumLabel(it) }
        scopeField.label = tr("referenceData.numberRange.scope")
        scopeField.setItems(CounterScope.entries)
        scopeField.setItemLabelGenerator { enumLabel(it) }
        continuityField.label = tr("referenceData.numberRange.continuity")
        continuityField.setItems(Continuity.entries)
        continuityField.setItemLabelGenerator { enumLabel(it) }
        activeField.label = tr("referenceData.paymentTerm.activeField")
        defaultField.label = tr("referenceData.defaultPaymentTerm")
        quoteCounterHint.text = tr("referenceData.numberRange.quoteCounterHint")
        quoteCounterHint.isVisible = false
    }

    // Vaadin binder wiring is inherently repetitive across the reference-data tabs (Qodana
    // DuplicatedCode) — kept explicit: each line names exactly one field/property pair, and a
    // reflective "bind all" helper would trade that greppable clarity for nothing.
    private fun configureBinder() {
        binder.forField(codeField).asRequired().bind(NumberRangeFormData::code, NumberRangeFormData::code::set)
        binder.forField(displayNameField).asRequired().bind(NumberRangeFormData::displayName, NumberRangeFormData::displayName::set)
        binder.forField(subjectField).asRequired().bind(NumberRangeFormData::subject, NumberRangeFormData::subject::set)
        binder.forField(startValueField).asRequired().bind(NumberRangeFormData::startValue, NumberRangeFormData::startValue::set)
        binder.forField(widthField).asRequired().bind(NumberRangeFormData::counterWidth, NumberRangeFormData::counterWidth::set)
        binder.forField(prefixSourceField).asRequired().bind(NumberRangeFormData::prefixSource, NumberRangeFormData::prefixSource::set)
        binder.forField(fixedPrefixField).bind(NumberRangeFormData::fixedPrefix, NumberRangeFormData::fixedPrefix::set)
        binder.forField(prefixDataFieldField).bind(NumberRangeFormData::prefixDataField, NumberRangeFormData::prefixDataField::set)
        binder.forField(datePartField).asRequired().bind(NumberRangeFormData::datePart, NumberRangeFormData::datePart::set)
        binder.forField(resetCycleField).asRequired().bind(NumberRangeFormData::resetCycle, NumberRangeFormData::resetCycle::set)
        binder.forField(scopeField).asRequired().bind(NumberRangeFormData::scope, NumberRangeFormData::scope::set)
        binder.forField(continuityField).asRequired().bind(NumberRangeFormData::continuity, NumberRangeFormData::continuity::set)
        binder.forField(activeField).bind(NumberRangeFormData::isActive, NumberRangeFormData::isActive::set)
        binder.forField(defaultField).bind(NumberRangeFormData::isDefault, NumberRangeFormData::isDefault::set)
    }

    private fun wireFieldListeners() {
        subjectField.addValueChangeListener { event ->
            val current = prefixDataFieldField.value
            prefixDataFieldField.setItems(prefixDataFieldsFor(event.value))
            if (current !in prefixDataFieldsFor(event.value)) prefixDataFieldField.clear()
        }
        subjectField.addValueChangeListener { applyQuoteRestrictions(it.value == NumberRangeSubject.QUOTE) }
        prefixSourceField.addValueChangeListener { updatePrefixSourceFields(it.value) }
        updatePrefixSourceFields(prefixSourceField.value)
    }

    private fun selectableSubjects(): List<NumberRangeSubject> =
        NumberRangeSubject.entries.filter { it != NumberRangeSubject.QUOTE || quoteAvailability.enabled }

    /**
     * A quote range is only configured here: the quote module issues the numbers, always gapless
     * and over one global counter, and may not derive the prefix from other data.
     */
    private fun applyQuoteRestrictions(quote: Boolean) {
        val allowed = if (quote) listOf(PrefixSource.NONE, PrefixSource.FIXED) else PrefixSource.entries
        prefixSourceField.setItems(allowed)
        if (quote) {
            if (prefixSourceField.value !in allowed) prefixSourceField.value = PrefixSource.FIXED
            scopeField.value = CounterScope.GLOBAL
            continuityField.value = Continuity.GAPLESS
        }
        scopeField.isEnabled = !quote
        continuityField.isEnabled = !quote
        quoteCounterHint.isVisible = quote
    }

    private fun buildEditorDialog() {
        val form = FormLayout(
            codeField, displayNameField, subjectField, startValueField, widthField,
            prefixSourceField, fixedPrefixField, prefixDataFieldField,
            datePartField, resetCycleField, scopeField, continuityField,
            activeField, defaultField, quoteCounterHint,
        ).also {
            it.setResponsiveSteps(FormLayout.ResponsiveStep("0", 1), FormLayout.ResponsiveStep("640px", 2))
            it.setColspan(codeField, 2)
            it.setColspan(displayNameField, 2)
            it.setColspan(activeField, 2)
            it.setColspan(defaultField, 2)
            it.setColspan(quoteCounterHint, 2)
        }
        useDialogSize(AppDialog.Size.M)
        installCrudFooter()
        editorDialog.add(
            VerticalLayout(form).also { it.isPadding = false; it.isSpacing = true }
        )
    }

    override fun idOf(formData: NumberRangeFormData): Long? = formData.id
    override fun formDataFrom(entity: NumberRangeDto?): NumberRangeFormData = NumberRangeFormData.from(entity)
    override fun createWritableForm(id: Long?): NumberRangeFormData = NumberRangeFormData(id = id)
    override fun readFormData(formData: NumberRangeFormData) = binder.readBean(formData)
    override fun writeValidatedForm(targetFormData: NumberRangeFormData): Boolean = binder.writeValidated(targetFormData)
    override fun findEntityById(id: Long): NumberRangeDto? = numberRangeQueries.findNumberRangeById(id)
    override fun persistForm(formData: NumberRangeFormData, current: NumberRangeDto?) {
        manageNumberRange.saveNumberRange(formData.toDto(current?.version))
    }
    override fun deleteEntityById(id: Long) {
        // Deactivating keeps the issued numbers meaningful; a hard delete is only for unused ranges.
        if (numberRangeQueries.isNumberRangeInUse(id)) {
            throw InvalidParameterException(tr("referenceData.numberRange.deleteBlocked"))
        }
        manageNumberRange.deleteNumberRange(id)
    }
    override fun refreshGridData() = refresh()
    override fun newHeaderTitle(): String = tr("referenceData.numberRange.new")
    override fun editHeaderTitle(): String = tr("referenceData.numberRange.edit")

    private fun refresh() {
        refreshGridWithCounts(
            countTotal = { filter -> numberRangeQueries.countNumberRanges(filter) },
            countInactive = { filter -> numberRangeQueries.countInactiveNumberRanges(filter) },
            createProvider = { filter ->
                createGridDataProvider(
                    fetch = { pageIndex, limit, f -> numberRangeQueries.findNumberRanges(pageIndex, limit, f) },
                    count = { f -> numberRangeQueries.countNumberRanges(f) },
                    filterSupplier = { filter },
                )
            }
        )
    }

    /* Every enum combo/column in this tab renders through an i18n key instead of the raw enum
     * name. Deliberately one exhaustive `when` per enum with literal keys (no `${value.name}`
     * interpolation): a new constant fails compilation until it gets a label, and the static
     * ReferenceDataTranslationsAuditTest sees every key. */

    private fun enumLabel(value: NumberRangeSubject): String = when (value) {
        NumberRangeSubject.INVOICE -> tr("referenceData.numberRange.subject.INVOICE")
        NumberRangeSubject.CUSTOMER -> tr("referenceData.numberRange.subject.CUSTOMER")
        NumberRangeSubject.CATALOGUE -> tr("referenceData.numberRange.subject.CATALOGUE")
        NumberRangeSubject.SELLER -> tr("referenceData.numberRange.subject.SELLER")
        NumberRangeSubject.QUOTE -> tr("referenceData.numberRange.subject.QUOTE")
    }

    private fun enumLabel(value: PrefixSource): String = when (value) {
        PrefixSource.NONE -> tr("referenceData.numberRange.prefixSource.NONE")
        PrefixSource.FIXED -> tr("referenceData.numberRange.prefixSource.FIXED")
        PrefixSource.SELLER_PROFILE -> tr("referenceData.numberRange.prefixSource.SELLER_PROFILE")
        PrefixSource.FROM_DATA -> tr("referenceData.numberRange.prefixSource.FROM_DATA")
    }

    private fun enumLabel(value: PrefixDataField): String = when (value) {
        PrefixDataField.NAME_INITIALS -> tr("referenceData.numberRange.prefixDataField.NAME_INITIALS")
        PrefixDataField.COUNTRY_CODE -> tr("referenceData.numberRange.prefixDataField.COUNTRY_CODE")
        PrefixDataField.CATALOGUE_CATEGORY -> tr("referenceData.numberRange.prefixDataField.CATALOGUE_CATEGORY")
    }

    private fun enumLabel(value: DatePart): String = when (value) {
        DatePart.NONE -> tr("referenceData.numberRange.datePart.NONE")
        DatePart.YEAR -> tr("referenceData.numberRange.datePart.YEAR")
        DatePart.YEAR_MONTH -> tr("referenceData.numberRange.datePart.YEAR_MONTH")
        DatePart.YEAR_MONTH_DAY -> tr("referenceData.numberRange.datePart.YEAR_MONTH_DAY")
    }

    private fun enumLabel(value: ResetCycle): String = when (value) {
        ResetCycle.NEVER -> tr("referenceData.numberRange.resetCycle.NEVER")
        ResetCycle.YEARLY -> tr("referenceData.numberRange.resetCycle.YEARLY")
    }

    private fun enumLabel(value: CounterScope): String = when (value) {
        CounterScope.GLOBAL -> tr("referenceData.numberRange.scope.GLOBAL")
        CounterScope.PER_SELLER -> tr("referenceData.numberRange.scope.PER_SELLER")
    }

    private fun enumLabel(value: Continuity): String = when (value) {
        Continuity.GAPLESS -> tr("referenceData.numberRange.continuity.GAPLESS")
        Continuity.UNIQUE_ONLY -> tr("referenceData.numberRange.continuity.UNIQUE_ONLY")
    }

    /**
     * Keep the form focused on values that can actually be used by the selected number-range
     * subject. The catalogue category is meaningful for specifications; customer and seller
     * ranges can derive a prefix from their name or country.
     */
    private fun prefixDataFieldsFor(subject: NumberRangeSubject?): List<PrefixDataField> = when (subject) {
        NumberRangeSubject.CATALOGUE -> listOf(PrefixDataField.CATALOGUE_CATEGORY)
        NumberRangeSubject.CUSTOMER, NumberRangeSubject.SELLER -> listOf(
            PrefixDataField.NAME_INITIALS,
            PrefixDataField.COUNTRY_CODE,
        )
        NumberRangeSubject.INVOICE, NumberRangeSubject.QUOTE, null -> PrefixDataField.entries.toList()
    }

    private fun updatePrefixSourceFields(source: PrefixSource?) {
        fixedPrefixField.isVisible = source == PrefixSource.FIXED
        prefixDataFieldField.isVisible = source == PrefixSource.FROM_DATA
    }

    // internal, not private: the tab test asserts every prefix-source branch directly.
    internal fun exampleNumber(range: NumberRangeDto): String {
        val prefix = when (range.prefixSource) {
            PrefixSource.NONE -> null
            PrefixSource.FIXED -> range.fixedPrefix
            PrefixSource.SELLER_PROFILE -> tr("referenceData.numberRange.exampleSellerPrefix")
            PrefixSource.FROM_DATA -> tr("referenceData.numberRange.exampleDataPrefix")
        }
        return NumberFormatter.compose(
            NumberPattern(prefix, range.datePart, range.counterWidth),
            LocalDate.of(EXAMPLE_YEAR, EXAMPLE_MONTH, EXAMPLE_DAY),
            range.startValue,
        )
    }
}
