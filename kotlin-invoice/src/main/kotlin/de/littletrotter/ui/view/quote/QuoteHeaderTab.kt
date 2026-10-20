package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.quote.port.inbound.EditQuoteHeaderCommand
import de.littletrotter.application.quote.port.inbound.QuoteDetail
import de.littletrotter.application.quote.port.inbound.QuotePriceDisplay
import de.littletrotter.ui.component.Section
import de.littletrotter.ui.components.freetext.ChipCandidate
import de.littletrotter.ui.i18n.TranslationProvider

private const val SUBJECT_MAX_LENGTH = 200
private const val PLACEHOLDERS_KEY = "quote.header.placeholders"
private const val FULL_COLUMN_CLASS = "quote-form-col--full"
private const val STACK_CLASS = "quote-form-stack"
private const val FORM_MAX_WIDTH = "72rem"

/**
 * The "Kopf" tab on a 12-column grid: the customer (name and the frozen document language, both
 * read-only because the customer is fixed when the offer is created) next to the offer data
 * (subject, validity, price display), then the three formatted texts across the full width.
 * [referenceValues] feeds the chips with the entered reference fields; [onChange] tells the
 * editor that something was edited.
 */
internal class QuoteHeaderTab(
    private val host: TranslationProvider,
    private val referenceValues: () -> Map<Int, String>,
    private val onChange: () -> Unit,
) : VerticalLayout() {

    internal val customerName = TextField(host.tr("quote.header.customerName"))
    internal val subject = TextField(host.tr("quote.header.subject"))
    internal val validUntil = DatePicker(host.tr("quote.header.validUntil"))
    internal val priceDisplay = ComboBox<QuotePriceDisplay>(host.tr("quote.header.priceDisplay"))
    internal val language = TextField(host.tr("quote.header.language"))
    internal val noteText = QuoteTextBlock(host.tr("quote.header.noteText"), host.tr(PLACEHOLDERS_KEY)) { chipCandidates() }
    internal val introText = QuoteTextBlock(host.tr("quote.header.introText"), host.tr(PLACEHOLDERS_KEY)) { chipCandidates() }
    internal val closingText = QuoteTextBlock(host.tr("quote.header.closingText"), host.tr(PLACEHOLDERS_KEY)) { chipCandidates() }

    private val labels = QuoteLabels(host)
    private var number: String? = null
    private var saved = ""

    init {
        isPadding = true
        setWidthFull()
        maxWidth = FORM_MAX_WIDTH
        subject.maxLength = SUBJECT_MAX_LENGTH
        subject.setWidthFull()
        validUntil.setWidthFull()
        priceDisplay.setWidthFull()
        language.setWidthFull()
        listOf(noteText, introText, closingText).forEach { it.setWidthFull() }
        validUntil.isRequiredIndicatorVisible = true
        validUntil.addValueChangeListener {
            refreshChips()
            onChange()
        }
        subject.addValueChangeListener { onChange() }
        priceDisplay.addValueChangeListener { onChange() }
        listOf(noteText, introText, closingText).forEach { block -> block.editor.addValueChangeListener { onChange() } }
        priceDisplay.setItems(QuotePriceDisplay.entries)
        priceDisplay.setItemLabelGenerator { labels.priceDisplay(it) }
        priceDisplay.isAllowCustomValue = false
        customerName.isReadOnly = true
        customerName.setWidthFull()
        language.isReadOnly = true
        language.helperText = host.tr("quote.header.language.helper")
        val customer = Div(customerName, language).apply { addClassName(STACK_CLASS) }
        val data = Div(subject, validUntil, priceDisplay).apply { addClassName(STACK_CLASS) }
        val form = Div(
            span(Section(host.tr("quote.header.section.customer"), "", customer), "quote-form-col--customer"),
            span(Section(host.tr("quote.header.section.data"), "", data), "quote-form-col--data"),
            span(noteText, FULL_COLUMN_CLASS),
            span(introText, FULL_COLUMN_CLASS),
            span(closingText, FULL_COLUMN_CLASS),
        ).apply {
            addClassName("quote-form-grid")
            setWidthFull()
        }
        add(form)
    }

    private fun span(child: com.vaadin.flow.component.Component, cssClass: String): Div = Div(child).apply { addClassName(cssClass) }

    /** Whether the header differs from the state last loaded or saved; the references belong to the header. */
    fun isDirty(): Boolean = fingerprint() != saved

    /** Remembers the current input as saved, e.g. after the header was stored. */
    fun markSaved() {
        saved = fingerprint()
    }

    private fun fingerprint(): String = listOf(
        subject.value.trim(), validUntil.value, priceDisplay.value, noteText.markup, introText.markup, closingText.markup,
        referenceValues().toSortedMap(),
    ).joinToString("|")

    fun bind(detail: QuoteDetail) {
        number = detail.number
        customerName.value = detail.party.name
        subject.value = detail.subject.orEmpty()
        validUntil.value = detail.validUntil
        priceDisplay.value = detail.priceDisplay
        language.value = detail.party.documentLanguage
        noteText.markup = detail.noteText.orEmpty()
        introText.markup = detail.introText.orEmpty()
        closingText.markup = detail.closingText.orEmpty()
        refreshChips()
        markSaved()
    }

    /** The header as entered, or `null` while the mandatory validity date is missing. */
    fun toCommand(quoteId: String, references: Map<Int, String>): EditQuoteHeaderCommand? {
        val until = validUntil.value ?: return null
        return EditQuoteHeaderCommand(
            quoteId = quoteId,
            validUntil = until,
            priceDisplay = priceDisplay.value ?: QuotePriceDisplay.AUTO,
            subject = subject.value.trim().ifEmpty { null },
            introText = introText.markup.ifEmpty { null },
            closingText = closingText.markup.ifEmpty { null },
            noteText = noteText.markup.ifEmpty { null },
            b2bValues = references,
        )
    }

    fun refreshChips() {
        listOf(noteText, introText, closingText).forEach { it.refreshChips() }
    }

    private fun chipCandidates(): List<ChipCandidate> {
        val candidates = mutableListOf<ChipCandidate>()
        number?.let { candidates += chip(host.tr("quote.chip.number"), it, "quote") }
        validUntil.value?.let { candidates += chip(host.tr("quote.chip.validUntil"), labels.date(it), "quote") }
        referenceValues().forEach { (code, value) -> candidates += chip(labels.btLabel(code), value, "b2bTerm") }
        return candidates
    }

    private fun chip(label: String, text: String, group: String) = ChipCandidate(label, label, text, group)
}
