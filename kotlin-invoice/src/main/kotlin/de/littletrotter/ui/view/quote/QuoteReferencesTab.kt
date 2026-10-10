package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.tabs.Tab
import com.vaadin.flow.component.tabs.TabSheet
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.value.ValueChangeMode
import de.littletrotter.application.quote.port.inbound.QuoteB2bValue
import de.littletrotter.application.quote.port.inbound.QuoteHeaderField
import de.littletrotter.ui.i18n.TranslationProvider

private const val REFERENCE_MAX_LENGTH = 200

/**
 * The reference fields of the header (business terms such as buyer reference or order number),
 * prefilled from the offer's values and, where it has none, from the field defaults. A missing
 * mandatory field is only flagged — the offer component demands them when the offer is submitted,
 * so a draft can always be saved incomplete. Mandatory fields come first; optional ones sit behind a
 * second tab so the recipient's actual demands are not buried in a long flat list.
 */
internal class QuoteReferencesTab(
    private val host: TranslationProvider,
    private val onChange: () -> Unit,
) : VerticalLayout() {

    internal val warning = Span()
    internal val fields = linkedMapOf<Int, TextField>()
    internal val intro = Span()
    internal val noMandatoryHint = Span()
    internal val mandatoryBox = VerticalLayout().apply { isPadding = false }
    internal val optionalBox = VerticalLayout().apply { isPadding = false }

    /** Present only while optional fields exist. */
    internal var subTabs: TabSheet? = null
        private set
    internal var optionalTab: Tab? = null
        private set

    private val labels = QuoteLabels(host)
    private var mandatory = emptySet<Int>()

    init {
        isPadding = true
        warning.addClassName("quote-warning")
        warning.element.setAttribute("role", "status")
        intro.text = host.tr("quote.references.intro")
        intro.addClassName("text-muted")
        noMandatoryHint.text = host.tr("quote.references.noMandatory")
        noMandatoryHint.addClassName("text-muted")
        add(warning, intro)
    }

    fun bind(definitions: List<QuoteHeaderField>, current: List<QuoteB2bValue>) {
        subTabs?.let { remove(it) }
        subTabs = null
        optionalTab = null
        mandatoryBox.removeFromParent()
        mandatoryBox.removeAll()
        optionalBox.removeAll()
        fields.clear()
        mandatory = definitions.filter { it.mandatory }.map { it.btCode }.toSet()
        val saved = current.associate { it.btCode to it.value }
        definitions.forEach { definition ->
            val field = createField(definition, saved[definition.btCode] ?: definition.defaultValue.orEmpty())
            fields[definition.btCode] = field
            (if (definition.mandatory) mandatoryBox else optionalBox).add(field)
        }
        if (mandatory.isEmpty()) mandatoryBox.add(noMandatoryHint)
        if (fields.size > mandatory.size) {
            val sheet = TabSheet().apply { setWidthFull() }
            val tab = Tab(host.tr("quote.references.tab.optional", fields.size - mandatory.size))
            sheet.add(Tab(host.tr("quote.references.tab.mandatory")), mandatoryBox)
            sheet.add(tab, optionalBox)
            subTabs = sheet
            optionalTab = tab
            add(sheet)
        } else {
            add(mandatoryBox)
        }
        refreshWarning()
    }

    private fun createField(definition: QuoteHeaderField, initial: String) =
        TextField(labels.btLabel(definition.btCode)).apply {
            setWidthFull()
            maxLength = REFERENCE_MAX_LENGTH
            isRequiredIndicatorVisible = definition.mandatory
            valueChangeMode = ValueChangeMode.LAZY
            value = initial
            labels.btHelp(definition.btCode)?.let { helperText = it }
            addValueChangeListener {
                refreshWarning()
                onChange()
            }
        }

    /** Filled fields only, keyed by business-term code. */
    fun values(): Map<Int, String> =
        fields.mapValues { it.value.value.trim() }.filterValues { it.isNotEmpty() }

    /** Codes of mandatory fields that are still empty. */
    fun missingMandatory(): List<Int> = mandatory.filter { fields.getValue(it).value.isBlank() }.sorted()

    private fun refreshWarning() {
        val missing = missingMandatory()
        warning.isVisible = missing.isNotEmpty()
        warning.text = host.tr("quote.references.missing", missing.joinToString(", ") { labels.btLabel(it) })
    }
}
