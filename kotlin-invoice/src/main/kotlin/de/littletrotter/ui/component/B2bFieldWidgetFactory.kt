package de.littletrotter.ui.component

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasHelper
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.provider.CallbackDataProvider
import com.vaadin.flow.data.provider.DataProvider
import com.vaadin.flow.data.renderer.ComponentRenderer
import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.shared.tax.VatCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.b2b.B2bFieldKind
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.shared.b2b.B2bTermTexts
import de.littletrotter.shared.b2b.codes.B2bCodeList
import de.littletrotter.shared.b2b.codes.FacturXProfile
import de.littletrotter.ui.view.invoice.shortReasonFallback
import java.util.Locale

/**
 * The widget-creation/read/write
 * logic for one B2B profile field's input, shared by [B2bProfileFieldsSection] (single/union
 * field lists — the article/invoice/invoice-item forms) and [ScopedB2bProfileFieldsSection] (the
 * country-scoped seller form): both need exactly the same DATE/TEXT-or-NUMBER/ENUM mapping, so it
 * exists here once instead of twice. The former BOOLEAN/Checkbox branch was removed: no
 * catalog-eligible term ever reached it (see [de.littletrotter.shared.b2b.B2bFieldKind]'s KDoc).
 */
object B2bFieldWidgetFactory {

    fun createWidget(term: B2bTerm, field: B2bProfileFieldDto, tr: (String) -> String): B2bFieldWidget {
        val label = field.businessDefinition
        // The German definition, or empty (deliberate: ~747 uncurated
        // terms no longer show the raw `!{b2b.catalog...}!` placeholder as helper text).
        val helper = B2bTermTexts.definitionOrNull(term, Locale.GERMAN).orEmpty()
        val required = field.requirement == B2bFieldRequirement.MANDATORY
        // isRequiredIndicatorVisible is set right on each concrete field below, not through a
        // second `when` re-dispatching on the built widget's subtype afterwards: [Unsupported] is
        // never constructed here (see its own KDoc), so a branch for it in that second `when`
        // would have been unreachable dead code, not a real case this factory could ever hit.
        val widget: B2bFieldWidget = when (term.fieldKind) {
            B2bFieldKind.DATE -> B2bFieldWidget.Date(
                DatePicker(label).apply { isClearButtonVisible = true; isRequiredIndicatorVisible = required },
            )
            // A searchable ComboBox over the term's valueDomain; see buildEnumComboBox.
            B2bFieldKind.ENUM -> buildEnumComboBox(term, label, tr).let { (combo, provider) ->
                combo.isRequiredIndicatorVisible = required
                B2bFieldWidget.Enum(combo, provider)
            }
            // NUMBER/TEXT — free text, no options source.
            else -> B2bFieldWidget.Text(
                TextField(label).apply {
                    isClearButtonVisible = true; setWidthFull(); isRequiredIndicatorVisible = required
                    // The value is printed on the PDF; flag characters its fonts lack as soon as typed.
                    addValueChangeListener { PdfTextValidation.check(this, tr) }
                },
            )
        }
        // Every branch of the `when` above (DatePicker/ComboBox/TextField) implements
        // HasHelper; the `is HasHelper` check's false arm was therefore dead code no test could
        // legitimately reach — same "unconditional cast, not a redundant type check" reasoning
        // B2bProfileFieldsSection.setGroupedFields already documents for its own HasHelper cast.
        (widget.component as HasHelper).helperText = helper
        return widget
    }

    fun setWidgetValue(widget: B2bFieldWidget, value: String?) = widget.setValue(value)

    fun widgetValue(widget: B2bFieldWidget): String? = widget.value()

    // ── ENUM — searchable ComboBox over a B2bCodeList ──────────────────────────────

    /**
     * ENUM kind renders a **searchable** [ComboBox] fed lazily from [B2bTerm.valueDomain] — never
     * a plain dropdown (PO 29.08.2026: units alone carry 2,162 values). Invalid values are
     * impossible to *enter* by construction: [ComboBox.isAllowCustomValue] is `false`, and every
     * offered item is itself a member of the domain.
     *
     * - **contains-filter** while typing, matching the code and — where curated — the German
     *   label ([enumMatches]).
     * - **lazy data provider**: [ComboBox.setDataProvider]'s two-callback overload reads straight
     *   from [B2bCodeList.values] on every fetch — the same shared, immutable `Set<String>`
     *   [de.littletrotter.shared.b2b.codes.B2bCodeListValuesEn16931]/`...Extended` already hold,
     *   never copied into a per-session list; Vaadin only ever materializes the current page
     *   (`limit`, typically a few dozen rows), not the full domain.
     * - **curated favorites on top**: [curatedFavorites] surfaces a labeled, categorized subset —
     *   reused from metadata the codebase *already* curates for another purpose ([InvoiceItemUnit]
     *   grouped by [de.littletrotter.shared.catalogue.InvoiceItemUnitDomain] for units, [VatCategory]
     *   for VAT categories) — first; the full official list stays reachable underneath via search.
     *
     * **Honest data constraint**: the codedb carries codes only, no labels or taxonomy — category
     * narrowing exists exactly where curated metadata already does. Do not hand-author labels for
     * the remaining thousands of codes; extend [curatedFavorites] on demand instead, the same way
     * [InvoiceItemUnit]/[VatCategory] exist for unrelated reasons already.
     */
    private fun buildEnumComboBox(term: B2bTerm, label: String, tr: (String) -> String): Pair<ComboBox<String>, DataProvider<String, String>> {
        val domain = requireNotNull(term.valueDomain) { "${term.officialCode} is ENUM but has no valueDomain." }
        val favorites = curatedFavorites(domain, tr)
        val favoriteCodes = favorites.map { it.code }.toSet()
        // Built explicitly (rather than through ComboBox's `setDataProvider(fetch, count)`
        // lambda convenience) so the caller can keep this exact, still-genuinely-typed instance —
        // see B2bFieldWidget.Enum's KDoc on why that matters.
        val provider = CallbackDataProvider<String, String>(
            { query -> enumMatches(domain, favorites, favoriteCodes, query.filter.orElse(null)).drop(query.offset).take(query.limit).stream() },
            { query -> enumMatches(domain, favorites, favoriteCodes, query.filter.orElse(null)).size },
        )
        val comboBox = ComboBox<String>(label).apply {
            isAllowCustomValue = false
            setItemLabelGenerator { code -> enumItemLabel(code, favorites) }
            setRenderer(ComponentRenderer { code: String -> enumItemRow(code, favorites) })
            setItems(provider)
        }
        return comboBox to provider
    }

    /** One curated favorite: its official [code], a German [label], and an optional [group] chip
     *  (units only — [de.littletrotter.shared.catalogue.InvoiceItemUnitDomain] has actual groups;
     *  VAT categories are a flat curated subset, no grouping axis). */
    private data class EnumFavorite(val code: String, val label: String, val group: String?)

    private fun curatedFavorites(domain: B2bCodeList, tr: (String) -> String): List<EnumFavorite> = when (domain) {
        B2bCodeList.UNIT_UNECE_REC20 -> InvoiceItemUnit.entries
            .sortedBy { it.domain.ordinal }
            .map { EnumFavorite(it.uneceCode, tr(it.labelKey), tr(it.domain.labelKey)) }
            // LINEAR_METER shares METER's code ("MTR", deliberately — same physical unit, see
            // InvoiceItemUnit's KDoc); a ComboBox item list is keyed by code, so keep only the
            // first (domain-order-earliest) label per code rather than listing "MTR" twice.
            .distinctBy { it.code }
        B2bCodeList.VAT_CATEGORY_5305 -> VatCategory.entries
            .map { EnumFavorite(it.code, it.shortReasonFallback(), group = null) }
        // No curated metadata for this domain yet — the full official list is still fully usable
        // via search, just without a favorites shortlist. Extend here on demand (PO 29.08.2026
        // names currencies — a small common set, e.g. EUR/USD/CHF/GBP — as the next candidate).
        else -> emptyList()
    }

    private fun enumItemLabel(code: String, favorites: List<EnumFavorite>): String =
        favorites.firstOrNull { it.code == code }?.let { "${it.code} — ${it.label}" } ?: code

    private fun enumItemRow(code: String, favorites: List<EnumFavorite>): Component {
        val favorite = favorites.firstOrNull { it.code == code }
        return HorizontalLayout().apply {
            isSpacing = true
            isPadding = false
            addClassName("lt-enum-row")
            defaultVerticalComponentAlignment = Alignment.CENTER
            if (favorite == null) {
                add(Span(code))
                return@apply
            }
            add(Span(favorite.label).apply { addClassName("lt-medium") })
            add(
                Span(favorite.code).apply { addClassName("lt-enum-row__code") },
            )
            favorite.group?.let { group ->
                add(
                    Span(group).apply {
                        applyChipTone(ChipTone.NEUTRAL)
                        addClassName("lt-enum-row__group")
                    },
                )
            }
        }
    }

    /** Favorites (curated order) first, then the remaining official codes (the domain's own
     *  deterministic order), filtered by a case-insensitive contains-match over the code and,
     *  where curated, the label — matches anywhere while typing, not just a prefix. */
    private fun enumMatches(
        domain: B2bCodeList,
        favorites: List<EnumFavorite>,
        favoriteCodes: Set<String>,
        filter: String?,
    ): List<String> {
        val ordered: List<String> =
            favorites.map { it.code } + domain.values(FacturXProfile.EN16931).filterNot { it in favoriteCodes }
        if (filter.isNullOrBlank()) return ordered
        val favoriteLabelByCode = favorites.associate { it.code to it.label }
        return ordered.filter { code ->
            code.contains(filter, ignoreCase = true) ||
                favoriteLabelByCode[code]?.contains(filter, ignoreCase = true) == true
        }
    }
}
