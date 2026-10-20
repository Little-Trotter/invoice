package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.html.NativeButton
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.value.ValueChangeMode
import com.vaadin.flow.router.QueryParameters
import de.littletrotter.application.dto.InvoiceExportFilter
import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.application.dto.SenderSummaryDto
import de.littletrotter.application.invoice.port.inbound.InvoiceListQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.ui.component.ChipTone
import de.littletrotter.ui.component.applyChipTone
import de.littletrotter.ui.component.recipientItems
import de.littletrotter.ui.format.DateFormatter
import de.littletrotter.ui.i18n.TranslationProvider
import java.time.LocalDate

/**
 * Session-held filter state for [InvoiceListView], restored whenever the URL carries no filter
 * query parameter (menu navigation carries none; "back to list" from [InvoiceDetailView]
 * round-trips the full set through the URL). Excludes the page index (a stale page is
 * worse than page 1) and the sort (a remembered sort silently replacing the newest-first default
 * is worse than forgetting it — only an explicit URL sort survives navigation).
 */
internal data class InvoiceListFilterState(
    val filterText: String? = null,
    val status: InvoiceStatus? = null,
    val reverseCharge: Boolean? = null,
    val validationStatus: ValidationStatus? = null,
    val recipientId: Long? = null,
    val senderProfileId: Long? = null,
    val dateFrom: LocalDate? = null,
    val dateTo: LocalDate? = null,
) : java.io.Serializable

/**
 * The filter fields of [InvoiceListView]: text search, Kunde/Anbieter, Validierung, Reverse
 * Charge, Von/Bis with the Jahres-Schnellwahl, the quick-filter [statusFilter] and the
 * active-filter counter/reset chip. Owns every filter value and its three representations —
 * the query snapshot ([currentFilter]), the session state ([currentFilterState]) and the URL
 * query parameters ([writeQueryParameters]/[applyQueryParameters]). Paging and sort stay in the
 * view.
 *
 * Every user edit calls [onFilterChanged]; [onClearAll] is the reset chip's click target, so the
 * view can suppress intermediate refreshes while [clear] runs.
 */
internal class InvoiceListFilterBar(
    private val recipientQuery: RecipientQueryPort,
    private val senderQuery: SenderQueryPort,
    private val invoiceListQuery: InvoiceListQueryPort,
    private val translations: TranslationProvider,
    private val onFilterChanged: () -> Unit,
    private val onClearAll: () -> Unit,
) {

    val filterField = TextField()
    val validationFilter = ComboBox<ValidationStatus>()
    val reverseChargeFilter = ComboBox<Boolean>()

    // The combos hold the full Recipient/SenderSummaryDto so the item label can show a
    // name; Von/Bis are inclusive and independently nullable (half-open range). yearQuickPick
    // sets both pickers to Jan 1 / Dec 31 in one click and never round-trips through the URL.
    val recipientFilter = ComboBox<RecipientSummaryDto>()
    val senderFilter = ComboBox<SenderSummaryDto>()
    val dateFromFilter = DatePicker()
    val dateToFilter = DatePicker()
    val yearQuickPick = ComboBox<Int>()

    /** The quick-filter chip row's toggled invoice state; `null` = all states. */
    var statusFilter: InvoiceStatus? = null

    // Guards [applyYearQuickPick] from triggering three refreshes — setting Von/Bis fires their
    // own value-change listeners too.
    private var isApplyingYearQuickPick = false

    /** Counter/reset chip at the end of the filter row, hidden entirely while no filter is active. */
    val activeFilterChip = NativeButton().apply {
        applyChipTone(ChipTone.NEUTRAL)
        addClassName("quick-filter-chip")
        isVisible = false
        addClickListener { onClearAll() }
    }

    private fun tr(key: String, vararg params: Any): String = translations.tr(key, *params)

    init {
        configureTextAndFlagFilters()
        configurePartyFilters()
        configureDateFilters()
    }

    private fun configureTextAndFlagFilters() {
        filterField.placeholder = tr("invoice.filter.placeholder")
        filterField.prefixComponent = VaadinIcon.SEARCH.create()
        filterField.valueChangeMode = ValueChangeMode.LAZY
        filterField.valueChangeTimeout = FILTER_DEBOUNCE_MS
        filterField.addValueChangeListener { onFilterChanged() }
        filterField.isClearButtonVisible = true
        filterField.element.setAttribute(ARIA_LABEL, tr("invoice.filter.placeholder"))
        filterField.width = "18rem"

        validationFilter.placeholder = tr("invoice.filter.validation.all")
        validationFilter.setItems(*ValidationStatus.entries.toTypedArray())
        validationFilter.setItemLabelGenerator { validationStatusLabel(it) { key -> tr(key) } }
        validationFilter.isClearButtonVisible = true
        validationFilter.addValueChangeListener { onFilterChanged() }
        validationFilter.element.setAttribute(ARIA_LABEL, tr("invoice.filter.validation.all"))
        validationFilter.width = FLAG_FILTER_WIDTH

        reverseChargeFilter.placeholder = tr("invoice.filter.reverseCharge.all")
        reverseChargeFilter.setItems(true, false)
        reverseChargeFilter.setItemLabelGenerator { reverseChargeLabel(it) }
        reverseChargeFilter.isClearButtonVisible = true
        reverseChargeFilter.addValueChangeListener { onFilterChanged() }
        reverseChargeFilter.element.setAttribute(ARIA_LABEL, tr("invoice.filter.reverseCharge.all"))
        reverseChargeFilter.width = FLAG_FILTER_WIDTH
    }

    private fun configurePartyFilters() {
        recipientFilter.placeholder = tr("invoice.filter.recipient.all")
        // Lazy, paged recipient items (a capped first page silently dropped
        // customers beyond it); shared with PrintJobListView's customer filter.
        recipientFilter.recipientItems(recipientQuery)
        recipientFilter.setItemLabelGenerator { it.name }
        recipientFilter.isClearButtonVisible = true
        recipientFilter.addValueChangeListener {
            refreshYearOptions()
            onFilterChanged()
        }
        recipientFilter.element.setAttribute(ARIA_LABEL, tr("invoice.filter.recipient.all"))
        recipientFilter.width = PARTY_FILTER_WIDTH

        senderFilter.placeholder = tr("invoice.filter.sender.all")
        senderFilter.setItems(senderQuery.findAllSenders())
        senderFilter.setItemLabelGenerator { it.name }
        senderFilter.isClearButtonVisible = true
        senderFilter.addValueChangeListener {
            refreshYearOptions()
            onFilterChanged()
        }
        senderFilter.element.setAttribute(ARIA_LABEL, tr("invoice.filter.sender.all"))
        senderFilter.width = PARTY_FILTER_WIDTH
    }

    private fun configureDateFilters() {
        listOf(dateFromFilter to "invoice.filter.dateFrom", dateToFilter to "invoice.filter.dateTo").forEach { (picker, key) ->
            picker.placeholder = tr(key)
            picker.element.setAttribute(ARIA_LABEL, tr(key))
            picker.isClearButtonVisible = true
            picker.width = "10rem"
            picker.addValueChangeListener {
                if (isApplyingYearQuickPick) return@addValueChangeListener
                clearYearQuickPickIfStale()
                onFilterChanged()
            }
        }

        yearQuickPick.placeholder = tr("invoice.filter.year.all")
        yearQuickPick.setItemLabelGenerator { it.toString() }
        yearQuickPick.isClearButtonVisible = true
        yearQuickPick.addValueChangeListener { applyYearQuickPick(it.value) }
        yearQuickPick.element.setAttribute(ARIA_LABEL, tr("invoice.filter.year.all"))
        yearQuickPick.width = "8rem"
        refreshYearOptions()
    }

    /** The tinted second toolbar row; wraps on narrow windows. */
    fun buildFilterRow(): HorizontalLayout = HorizontalLayout(
        recipientFilter, senderFilter, validationFilter, reverseChargeFilter,
        dateFromFilter, dateToFilter, activeFilterChip,
    ).apply {
        setWidthFull()
        defaultVerticalComponentAlignment = Alignment.BASELINE
        setSpacing(true)
        isPadding = false
        addClassNames("toolbar-wrap", "invoice-filter-row")
    }

    private fun reverseChargeLabel(value: Boolean): String =
        if (value) tr("invoice.filter.reverseCharge.yes") else tr("invoice.filter.reverseCharge.no")

    private fun filterText(): String? = filterField.value?.trim()?.ifEmpty { null }

    // ── active-filter counter/reset chip ──────────────────────────────────────

    /** Counts every active filter (the [InvoiceListFilterState] set; sort is not a filter). */
    private fun activeFilterCount(): Int = listOfNotNull(
        filterText(),
        statusFilter,
        reverseChargeFilter.value,
        validationFilter.value,
        recipientFilter.value,
        senderFilter.value,
        dateFromFilter.value,
        dateToFilter.value,
    ).size

    fun refreshActiveFilterChip() {
        val count = activeFilterCount()
        activeFilterChip.isVisible = count > 0
        if (count > 0) {
            activeFilterChip.text = tr("invoice.filter.active", count)
        }
    }

    /** Resets every filter field; the caller suppresses the intermediate refreshes. */
    fun clear() {
        filterField.value = ""
        statusFilter = null
        reverseChargeFilter.value = null
        validationFilter.value = null
        recipientFilter.value = null
        senderFilter.value = null
        dateFromFilter.value = null
        dateToFilter.value = null
        yearQuickPick.value = null
    }

    // ── Zeitraum/Jahres-Schnellwahl ─────────────────────────────────

    /** Distinct invoice years for the selected Kunde/Anbieter (one grouped query, newest first);
     *  keeps the current selection if it still exists. */
    private fun refreshYearOptions() {
        val years = invoiceListQuery.availableYears(senderFilter.value?.id, recipientFilter.value?.id)
        val currentSelection = yearQuickPick.value
        yearQuickPick.setItems(years)
        if (currentSelection != null && years.contains(currentSelection)) {
            yearQuickPick.value = currentSelection
        }
    }

    /** A year pick sets Von=01.01./Bis=31.12. with exactly one refresh; `null` (clear button)
     *  leaves Von/Bis untouched — the quick-pick is a one-shot convenience. */
    private fun applyYearQuickPick(year: Int?) {
        if (year == null) return
        isApplyingYearQuickPick = true
        try {
            dateFromFilter.value = LocalDate.of(year, 1, 1)
            dateToFilter.value = LocalDate.of(year, DECEMBER, LAST_DAY_OF_DECEMBER)
        } finally {
            isApplyingYearQuickPick = false
        }
        onFilterChanged()
    }

    /** A direct Von/Bis edit that no longer matches the picked year clears the combo. */
    fun clearYearQuickPickIfStale() {
        val year = yearQuickPick.value ?: return
        val matches = dateFromFilter.value == LocalDate.of(year, 1, 1) &&
            dateToFilter.value == LocalDate.of(year, DECEMBER, LAST_DAY_OF_DECEMBER)
        if (!matches) yearQuickPick.value = null
    }

    /** Best-effort, cosmetic: a restored Von/Bis range covering a full year selects that year. */
    fun refreshYearQuickPickFromDateRange() {
        refreshYearOptions()
        val restoredFrom = dateFromFilter.value
        val restoredTo = dateToFilter.value
        if (restoredFrom != null && restoredTo != null && isFullYearRange(restoredFrom, restoredTo)) {
            yearQuickPick.value = restoredFrom.year
        }
    }

    private fun isFullYearRange(from: LocalDate, to: LocalDate): Boolean =
        from.dayOfYear == 1 && from.year == to.year && to == LocalDate.of(to.year, DECEMBER, LAST_DAY_OF_DECEMBER)

    // ── snapshots ─────────────────────────────────────────────────────────────

    /** The current filter values as one query snapshot — read at call time, never live. */
    fun currentFilter(): InvoiceExportFilter = InvoiceExportFilter(
        filterText = filterText(),
        status = statusFilter,
        reverseCharge = reverseChargeFilter.value,
        validationStatus = validationFilter.value,
        recipientId = recipientFilter.value?.id,
        senderProfileId = senderFilter.value?.id,
        dateFrom = dateFromFilter.value,
        dateTo = dateToFilter.value,
    )

    fun currentFilterState(): InvoiceListFilterState = InvoiceListFilterState(
        filterText = filterText(),
        status = statusFilter,
        reverseCharge = reverseChargeFilter.value,
        validationStatus = validationFilter.value,
        recipientId = recipientFilter.value?.id,
        senderProfileId = senderFilter.value?.id,
        dateFrom = dateFromFilter.value,
        dateTo = dateToFilter.value,
    )

    /** Every active filter as one readable fragment, joined by `; `, or "Alle Rechnungen" — the
     *  export dialog's read-only scope summary. */
    fun currentScopeDescription(): String {
        val parts = buildList {
            filterText()?.let { add(tr("invoiceListExport.scope.text", it)) }
            statusFilter?.let { add(tr("invoiceListExport.scope.status", tr(statusBadgeLabelKey(it)))) }
            validationFilter.value?.let { add(tr("invoiceListExport.scope.validation", validationStatusLabel(it) { key -> tr(key) })) }
            reverseChargeFilter.value?.let { add(tr("invoiceListExport.scope.reverseCharge", reverseChargeLabel(it))) }
            recipientFilter.value?.let { add(tr("invoiceListExport.scope.recipient", it.name)) }
            senderFilter.value?.let { add(tr("invoiceListExport.scope.sender", it.name)) }
            val from = dateFromFilter.value
            val to = dateToFilter.value
            when {
                from != null && to != null -> add(tr("invoiceListExport.scope.dateRange", DateFormatter.format(from), DateFormatter.format(to)))
                from != null -> add(tr("invoiceListExport.scope.dateFrom", DateFormatter.format(from)))
                to != null -> add(tr("invoiceListExport.scope.dateTo", DateFormatter.format(to)))
            }
        }
        return if (parts.isEmpty()) tr("invoiceListExport.scope.all") else parts.joinToString("; ")
    }

    /** Session-restore path — the mirror of [applyQueryParameters], minus paging and sort. */
    fun applyFilterState(state: InvoiceListFilterState) {
        filterField.value = state.filterText ?: ""
        statusFilter = state.status
        reverseChargeFilter.value = state.reverseCharge
        validationFilter.value = state.validationStatus
        state.recipientId?.let { id -> recipientQuery.findRecipientById(id)?.let { recipientFilter.value = it } }
        state.senderProfileId?.let { id -> senderQuery.findSenderById(id)?.let { senderFilter.value = it } }
        dateFromFilter.value = state.dateFrom
        dateToFilter.value = state.dateTo
    }

    // ── URL query parameters ──────────────────────────────────────────────────

    /** Writes every active filter as one query parameter, deep-linkable.
     *  yearQuickPick is deliberately not written — it only ever sets dateFrom/dateTo. */
    fun writeQueryParameters(add: (String, String) -> Unit) {
        filterText()?.let { add(PARAM_FILTER, it) }
        statusFilter?.let { add(PARAM_STATUS, it.name) }
        reverseChargeFilter.value?.let { add(PARAM_REVERSE_CHARGE, it.toString()) }
        validationFilter.value?.let { add(PARAM_VALIDATION_STATUS, it.name) }
        recipientFilter.value?.id?.let { add(PARAM_RECIPIENT_ID, it.toString()) }
        senderFilter.value?.id?.let { add(PARAM_SENDER_PROFILE_ID, it.toString()) }
        dateFromFilter.value?.let { add(PARAM_DATE_FROM, it.toString()) }
        dateToFilter.value?.let { add(PARAM_DATE_TO, it.toString()) }
    }

    /** URL-restore path — one query parameter per field; unknown or unparsable values are
     *  ignored. Recipient/sender are looked up by id (their equals() compares by id, so a
     *  freshly fetched entity still selects the matching combo item). */
    fun applyQueryParameters(queryParameters: QueryParameters) {
        val params: Map<String, List<String>> = queryParameters.parameters
        val first: (String) -> String? = { key -> params[key]?.firstOrNull() }
        applyFlagParameters(first)
        applyPartyAndDateParameters(first)
    }

    private fun applyFlagParameters(first: (String) -> String?) {
        first(PARAM_FILTER)?.let { filterField.value = it }
        first(PARAM_STATUS)?.let { name ->
            InvoiceStatus.entries.find { it.name == name }?.let { statusFilter = it }
        }
        when (first(PARAM_REVERSE_CHARGE)) {
            "true" -> reverseChargeFilter.value = true
            "false" -> reverseChargeFilter.value = false
        }
        first(PARAM_VALIDATION_STATUS)?.let { name ->
            ValidationStatus.entries.find { it.name == name }?.let { validationFilter.value = it }
        }
    }

    private fun applyPartyAndDateParameters(first: (String) -> String?) {
        first(PARAM_RECIPIENT_ID)?.toLongOrNull()?.let { id ->
            recipientQuery.findRecipientById(id)?.let { recipientFilter.value = it }
        }
        first(PARAM_SENDER_PROFILE_ID)?.toLongOrNull()?.let { id ->
            senderQuery.findSenderById(id)?.let { senderFilter.value = it }
        }
        first(PARAM_DATE_FROM)?.let { raw ->
            runCatching { LocalDate.parse(raw) }.getOrNull()?.let { dateFromFilter.value = it }
        }
        first(PARAM_DATE_TO)?.let { raw ->
            runCatching { LocalDate.parse(raw) }.getOrNull()?.let { dateToFilter.value = it }
        }
    }

    companion object {
        private const val ARIA_LABEL = "aria-label"
        private const val FLAG_FILTER_WIDTH = "12rem"
        private const val PARTY_FILTER_WIDTH = "14rem"
        private const val FILTER_DEBOUNCE_MS = 300
        private const val DECEMBER = 12
        private const val LAST_DAY_OF_DECEMBER = 31

        private const val PARAM_FILTER = "filter"
        private const val PARAM_STATUS = "status"
        private const val PARAM_REVERSE_CHARGE = "reverseCharge"
        private const val PARAM_VALIDATION_STATUS = "validationStatus"
        private const val PARAM_RECIPIENT_ID = "recipientId"
        private const val PARAM_SENDER_PROFILE_ID = "senderProfileId"
        private const val PARAM_DATE_FROM = "dateFrom"
        private const val PARAM_DATE_TO = "dateTo"

        /** URL keys that carry filter state (see [InvoiceListView.restoreFilterState]). */
        val FILTER_PARAM_KEYS = setOf(
            PARAM_FILTER, PARAM_STATUS, PARAM_REVERSE_CHARGE, PARAM_VALIDATION_STATUS,
            PARAM_RECIPIENT_ID, PARAM_SENDER_PROFILE_ID, PARAM_DATE_FROM, PARAM_DATE_TO,
        )
    }
}
