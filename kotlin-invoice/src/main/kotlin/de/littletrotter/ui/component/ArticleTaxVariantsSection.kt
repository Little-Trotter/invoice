package de.littletrotter.ui.component

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import de.littletrotter.application.dto.ArticleTaxVariantDto
import de.littletrotter.shared.country.Country
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.spi.masterdata.ArticleTaxVariantsExtension
import de.littletrotter.ui.view.invoice.taxRuleLabel

/**
 * Generic rendering for an article's per-country MWST variants (0..n rows of country
 * + [TaxRateDto]), mirroring [SellerVatRegistrationsSection]'s repeatable-row pattern:
 * the section only knows *that* an article may have such rows and which countries the owning
 * [extension] offers; the rows come from and go back to the article's read model and write
 * command, so the storage model never leaks into the view.
 *
 * [allTaxRates] is supplied by the caller (already loaded for the tax-rate combo elsewhere in the
 * form) so each row can filter its own tax-rate dropdown to the chosen country's selectable rates
 * without a repeated port lookup per row.
 *
 * Whether [extension] is present decides everything: `null` renders nothing — the case where no
 * such extension bean is on the classpath.
 */
class ArticleTaxVariantsSection(
    private val extension: ArticleTaxVariantsExtension?,
    private val allTaxRates: List<TaxRateDto>,
    private val tr: (String) -> String,
) : VerticalLayout() {

    val hasContent: Boolean get() = extension != null

    // internal, not private: Karibu tests drive a row's country/tax-rate combos directly by
    // their real type; the row bar only exposes them through its Vaadin Component children,
    // which would need an unchecked cast back to their type parameter to undo the erasure.
    internal data class Row(val country: ComboBox<String>, val taxRate: ComboBox<TaxRateDto>, val bar: HorizontalLayout)

    internal val rows: MutableList<Row> = mutableListOf()
    private val suggestions = VerticalLayout().apply { isPadding = false }
    private val suggestedRates = mutableListOf<Pair<Checkbox, TaxRateDto>>()
    private val rowsContainer = VerticalLayout().apply { isPadding = false; isSpacing = true; setWidthFull() }
    private val addButton = Button(tr("serviceCatalog.taxVariant.add"), ActionIcons.ADD.create())

    /** The default seller's additional VAT-registration countries, as last passed to
     *  [suggestCountries]; reused by the add-button click to preselect an unambiguous candidate
     *  on a newly opened, still-empty row (see [preselectedCountry]). */
    private var candidateCountries: List<String> = emptyList()

    /** [extension]'s curated country set, narrowed to countries that actually have a
     *  usable reference [TaxRateDto] on file ([TaxRateDto.isUsableReferenceRate]); a country without one
     *  must not be selectable in the row combobox at all. Only called from contexts already gated
     *  on a present [extension] (the add button's listener, and [addRow] itself), so the `!!` here
     *  is as structurally safe as the one [addRow] used to assert directly. */
    private fun usableCountries(): List<String> {
        val withReferenceRate = allTaxRates.filter { it.isUsableReferenceRate }.map { it.country.alpha2 }.toSet()
        return extension!!.supportedCountries.filter { it in withReferenceRate }
    }

    init {
        isPadding = false
        isSpacing = true
        setWidthFull()
        if (extension != null) {
            addButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY)
            addButton.addClickListener {
                val usedCountries = rows.mapNotNull { it.country.value.blankToNull() }.toSet()
                val preselected = preselectedCountry(candidateCountries.filter { it in usableCountries() }, usedCountries)
                addRow(preselected.orEmpty(), null)
            }
            add(rowsContainer, suggestions, addButton)
        }
    }

    /** Shows [variants] (the article's stored rows; empty for a new article). */
    fun readFrom(variants: List<ArticleTaxVariantDto>) {
        suggestions.removeAll()
        suggestedRates.clear()
        candidateCountries = emptyList()
        rowsContainer.removeAll()
        rows.clear()
        if (extension == null) return
        variants.forEach { row ->
            addRow(row.country.alpha2, allTaxRates.find { it.id == row.taxRateId })
        }
    }

    /** Each proposed country can be opted out independently before the first save; also feeds
     *  [preselectedCountry] for the add button. */
    fun suggestCountries(countries: List<String>) {
        if (extension == null) return
        candidateCountries = countries.distinct().filter { it != "DE" }
        val foreignCountries = candidateCountries.sorted()
        if (foreignCountries.isEmpty()) return
        suggestions.add(Span(tr("serviceCatalog.taxVariant.suggestionHint")))
        foreignCountries.forEach { country ->
            val rate = allTaxRates.firstOrNull { it.country.alpha2 == country && it.isUsableReferenceRate && it.id != null }
            if (rate == null) {
                suggestions.add(Span("$country: ${tr("serviceCatalog.taxVariant.missingReference")}"))
            } else {
                val checkbox = Checkbox("$country: ${taxRuleLabel(rate)}", true)
                suggestedRates.add(checkbox to rate)
                suggestions.add(checkbox)
            }
        }
    }

    /** The current, in-flight rows to save, or `null` when no extension offers the section (the
     *  stored rows then stay as they are). A row without a chosen tax rate (country picked but not
     *  yet a rate, or an add-then-abandon row) is dropped silently — the country is derived from
     *  the chosen [TaxRateDto.country] itself, not the country field, so the two can never disagree. */
    fun submittedRows(): List<ArticleTaxVariantDto>? {
        if (extension == null) return null
        val submitted = rows.mapNotNull { row ->
            val rate = row.taxRate.value ?: return@mapNotNull null
            val id = rate.id ?: return@mapNotNull null
            ArticleTaxVariantDto(rate.country, id)
        }
        val proposed = suggestedRates.filter { it.first.value }.map { (_, rate) ->
            ArticleTaxVariantDto(rate.country, requireNotNull(rate.id))
        }
        return (submitted + proposed).distinctBy { it.country }
    }

    private fun addRow(country: String, taxRate: TaxRateDto?) {
        val taxRateField = ComboBox<TaxRateDto>().apply {
            label = tr("serviceCatalog.taxVariant.taxRate")
            setItemLabelGenerator(::taxRuleLabel)
            setWidthFull()
        }
        val countryField = ComboBox<String>().apply {
            label = tr("serviceCatalog.taxVariant.country")
            setItems(usableCountries())
            value = country.blankToNull()
            width = "8rem"
        }
        fun refreshTaxRateItems(selectedCountry: String?) {
            val resolved = Country.fromAlpha2(selectedCountry)
            taxRateField.setItems(
                allTaxRates.filter { it.country == resolved && it.isActive && it.isSelectable }
            )
        }
        refreshTaxRateItems(country)
        taxRateField.value = taxRate
        countryField.addValueChangeListener { event ->
            refreshTaxRateItems(event.value)
            taxRateField.value = null
        }
        val removeButton = Button(ActionIcons.DELETE.create()).apply {
            addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR)
            element.setAttribute("aria-label", tr("serviceCatalog.taxVariant.remove"))
        }
        val bar = HorizontalLayout(countryField, taxRateField, removeButton).apply {
            setWidthFull()
            isSpacing = true
            expand(taxRateField)
            alignItems = FlexComponent.Alignment.BASELINE
        }
        val row = Row(countryField, taxRateField, bar)
        removeButton.addClickListener {
            rows.remove(row)
            rowsContainer.remove(bar)
        }
        rows.add(row)
        rowsContainer.add(bar)
    }
}

/**
 * Of the default seller's additional VAT-registration [candidateCountries] not yet
 * used by an existing row ([usedCountries]), the single country to preselect on a newly opened,
 * still-empty tax-variant row. Zero or more than one still-unused candidate is ambiguous (or
 * there simply is none) — the safe default, per the open PO question the source spec leaves
 * unresolved, is no preselection, keeping today's hint-only behaviour for that case.
 */
fun preselectedCountry(candidateCountries: List<String>, usedCountries: Set<String>): String? =
    candidateCountries.filter { it !in usedCountries }.distinct().singleOrNull()
