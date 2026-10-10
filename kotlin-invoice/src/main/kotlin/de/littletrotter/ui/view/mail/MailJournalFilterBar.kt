package de.littletrotter.ui.view.mail

import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.html.NativeButton
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.value.ValueChangeMode
import com.vaadin.flow.router.QueryParameters
import de.littletrotter.application.mail.port.inbound.MailJournalArchiveStatus
import de.littletrotter.application.mail.port.inbound.MailJournalCriteria
import de.littletrotter.application.mail.port.inbound.MailJournalStatus
import de.littletrotter.application.mail.port.inbound.MailTemplateKind
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.ChipTone
import de.littletrotter.ui.component.applyChipTone
import de.littletrotter.ui.i18n.TranslationProvider
import java.time.LocalDate

/**
 * Session-held filter state of [MailJournalView], restored when the URL carries no filter
 * parameter (menu navigation). Paging and sort are deliberately not part of it: a remembered page
 * or sort would silently replace the newest-first default.
 */
internal data class MailJournalFilterState(
    val text: String? = null,
    val referenceKey: String? = null,
    val status: MailJournalStatus? = null,
    val archiveStatus: MailJournalArchiveStatus? = null,
    val templateKind: MailTemplateKind? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val editedOnly: Boolean = false,
) : java.io.Serializable

/**
 * The filter row of [MailJournalView]: full text, from/to, template kind, archive status and the
 * "edited texts only" switch, plus the reference chip, the status chip's toggled state and the
 * active-filter counter with its reset. It owns every filter value and its three representations:
 * the query ([criteria]), the session state ([state]) and the URL parameters
 * ([writeQueryParameters]/[applyQueryParameters]). Paging and sort stay in the view.
 *
 * Each user edit calls [onFilterChanged]; the view suppresses intermediate refreshes while it
 * resets or restores the bar.
 */
internal class MailJournalFilterBar(
    private val translations: TranslationProvider,
    private val onFilterChanged: () -> Unit,
    private val onClearAll: () -> Unit,
) {

    val textField = TextField()
    val fromPicker = DatePicker()
    val toPicker = DatePicker()
    val kindFilter = ComboBox<MailTemplateKind>()
    val archiveFilter = ComboBox<MailJournalArchiveStatus>()
    val editedOnlyToggle = Checkbox()

    /** The status chip row's toggled status; `null` = all. */
    var statusFilter: MailJournalStatus? = null

    /** Set by `?reference=` and by the retry-of link; shown as its own removable chip. */
    var referenceKey: String? = null
        set(value) {
            field = value?.trim()?.ifEmpty { null }
            refreshReferenceChip()
        }

    val referenceChip = NativeButton().apply {
        applyChipTone(ChipTone.REVIEW)
        addClassName("quick-filter-chip")
        isVisible = false
        addClickListener {
            referenceKey = null
            onFilterChanged()
        }
    }

    val activeFilterChip = NativeButton().apply {
        applyChipTone(ChipTone.NEUTRAL)
        addClassName("quick-filter-chip")
        isVisible = false
        addClickListener { onClearAll() }
    }

    val resetButton = AppButtons.tertiary(translations.tr("mail.journal.filter.reset")) { onClearAll() }.apply {
        addThemeVariants(ButtonVariant.LUMO_SMALL)
        isVisible = false
    }

    private fun tr(key: String, vararg params: Any): String = translations.tr(key, *params)

    init {
        textField.placeholder = tr("mail.journal.filter.text")
        textField.prefixComponent = VaadinIcon.SEARCH.create()
        textField.valueChangeMode = ValueChangeMode.LAZY
        textField.valueChangeTimeout = FILTER_DEBOUNCE_MS
        textField.isClearButtonVisible = true
        textField.width = "18rem"
        textField.element.setAttribute(ARIA_LABEL, tr("mail.journal.filter.text"))
        textField.addValueChangeListener { onFilterChanged() }

        listOf(fromPicker to "mail.journal.filter.from", toPicker to "mail.journal.filter.to").forEach { (picker, key) ->
            picker.placeholder = tr(key)
            picker.element.setAttribute(ARIA_LABEL, tr(key))
            picker.isClearButtonVisible = true
            picker.width = "10rem"
            picker.addValueChangeListener { onFilterChanged() }
        }

        kindFilter.placeholder = tr("mail.journal.filter.kind")
        kindFilter.setItems(*MailTemplateKind.entries.toTypedArray())
        kindFilter.setItemLabelGenerator { tr("mail.journal.kind.${it.name}") }
        kindFilter.isClearButtonVisible = true
        kindFilter.width = COMBO_WIDTH
        kindFilter.element.setAttribute(ARIA_LABEL, tr("mail.journal.filter.kind"))
        kindFilter.addValueChangeListener { onFilterChanged() }

        archiveFilter.placeholder = tr("mail.journal.filter.archive")
        archiveFilter.setItems(*MailJournalArchiveStatus.entries.toTypedArray())
        archiveFilter.setItemLabelGenerator { tr("mail.journal.archive.${it.name}") }
        archiveFilter.isClearButtonVisible = true
        archiveFilter.width = COMBO_WIDTH
        archiveFilter.element.setAttribute(ARIA_LABEL, tr("mail.journal.filter.archive"))
        archiveFilter.addValueChangeListener { onFilterChanged() }

        editedOnlyToggle.label = tr("mail.journal.filter.editedOnly")
        editedOnlyToggle.addValueChangeListener { onFilterChanged() }
    }

    /** The tinted toolbar row; wraps on narrow windows. */
    fun buildFilterRow(): HorizontalLayout = HorizontalLayout(
        kindFilter, archiveFilter, fromPicker, toPicker, editedOnlyToggle, referenceChip, activeFilterChip, resetButton,
    ).apply {
        setWidthFull()
        defaultVerticalComponentAlignment = Alignment.BASELINE
        setSpacing(true)
        isPadding = false
        addClassNames("toolbar-wrap", "mail-journal-filter-row")
    }

    private fun text(): String? = textField.value?.trim()?.ifEmpty { null }

    /** The filter half of a query; sort and page window are the view's. */
    fun criteria(): MailJournalCriteria = MailJournalCriteria(
        text = text(),
        referenceKey = referenceKey,
        status = statusFilter,
        archiveStatus = archiveFilter.value,
        templateKind = kindFilter.value,
        from = fromPicker.value,
        to = toPicker.value,
        editedOnly = editedOnlyToggle.value,
    )

    fun state(): MailJournalFilterState = MailJournalFilterState(
        text = text(),
        referenceKey = referenceKey,
        status = statusFilter,
        archiveStatus = archiveFilter.value,
        templateKind = kindFilter.value,
        from = fromPicker.value,
        to = toPicker.value,
        editedOnly = editedOnlyToggle.value,
    )

    fun applyState(state: MailJournalFilterState) {
        textField.value = state.text ?: ""
        referenceKey = state.referenceKey
        statusFilter = state.status
        archiveFilter.value = state.archiveStatus
        kindFilter.value = state.templateKind
        fromPicker.value = state.from
        toPicker.value = state.to
        editedOnlyToggle.value = state.editedOnly
    }

    /** Resets every field; the caller suppresses the intermediate refreshes. */
    fun clear() = applyState(MailJournalFilterState())

    private fun activeFilterCount(): Int = with(state()) {
        listOfNotNull(text, referenceKey, status, archiveStatus, templateKind, from, to).size +
            if (editedOnly) 1 else 0
    }

    fun refreshActiveFilterChip() {
        val count = activeFilterCount()
        activeFilterChip.isVisible = count > 0
        resetButton.isVisible = count > 0
        if (count > 0) {
            activeFilterChip.text = tr("mail.journal.filter.active", count)
        }
    }

    private fun refreshReferenceChip() {
        val key = referenceKey
        referenceChip.isVisible = key != null
        if (key != null) {
            referenceChip.text = tr("mail.journal.filter.reference", key)
        }
    }

    // ── URL query parameters ──────────────────────────────────────────────────

    /** Writes every active filter as one deep-linkable query parameter. */
    fun writeQueryParameters(add: (String, String) -> Unit) {
        with(state()) {
            text?.let { add(PARAM_FILTER, it) }
            referenceKey?.let { add(PARAM_REFERENCE, it) }
            status?.let { add(PARAM_STATUS, it.name) }
            archiveStatus?.let { add(PARAM_ARCHIVE, it.name) }
            templateKind?.let { add(PARAM_KIND, it.name) }
            from?.let { add(PARAM_FROM, it.toString()) }
            to?.let { add(PARAM_TO, it.toString()) }
            if (editedOnly) add(PARAM_EDITED, "true")
        }
    }

    /** URL-restore path; unknown or unparsable values come from an edited bookmark and are ignored. */
    fun applyQueryParameters(queryParameters: QueryParameters) {
        val first: (String) -> String? = { key -> queryParameters.parameters[key]?.firstOrNull() }
        first(PARAM_FILTER)?.let { textField.value = it }
        first(PARAM_REFERENCE)?.let { referenceKey = it }
        first(PARAM_STATUS)?.let { name -> MailJournalStatus.entries.find { it.name == name }?.let { statusFilter = it } }
        first(PARAM_ARCHIVE)?.let { name -> MailJournalArchiveStatus.entries.find { it.name == name }?.let { archiveFilter.value = it } }
        first(PARAM_KIND)?.let { name -> MailTemplateKind.entries.find { it.name == name }?.let { kindFilter.value = it } }
        first(PARAM_FROM)?.let { raw -> runCatching { LocalDate.parse(raw) }.getOrNull()?.let { fromPicker.value = it } }
        first(PARAM_TO)?.let { raw -> runCatching { LocalDate.parse(raw) }.getOrNull()?.let { toPicker.value = it } }
        if (first(PARAM_EDITED) == "true") editedOnlyToggle.value = true
    }

    companion object {
        private const val ARIA_LABEL = "aria-label"
        private const val COMBO_WIDTH = "13rem"
        private const val FILTER_DEBOUNCE_MS = 300

        private const val PARAM_FILTER = "filter"
        private const val PARAM_REFERENCE = "reference"
        private const val PARAM_STATUS = "status"
        private const val PARAM_ARCHIVE = "archive"
        private const val PARAM_KIND = "kind"
        private const val PARAM_FROM = "from"
        private const val PARAM_TO = "to"
        private const val PARAM_EDITED = "edited"

        /** URL keys that carry filter state; their presence makes the URL win over the session. */
        val FILTER_PARAM_KEYS = setOf(
            PARAM_FILTER, PARAM_REFERENCE, PARAM_STATUS, PARAM_ARCHIVE, PARAM_KIND, PARAM_FROM, PARAM_TO, PARAM_EDITED,
        )
    }
}
