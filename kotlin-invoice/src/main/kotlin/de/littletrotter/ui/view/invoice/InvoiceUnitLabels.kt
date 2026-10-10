package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.ui.i18n.UiTranslations
import java.util.Locale

/**
 * The one UI resolver for invoice units.
 *
 * Persisted unit values are enum names, while [InvoiceItemUnit.labelKey] is the canonical
 * translation key. Keeping the enum-to-key conversion here prevents each invoice surface from
 * inventing a lower-case enum mapping (which cannot resolve keys such as `unit.squareMeter`).
 * Missing and unsupported locales deliberately use German, matching the invoice UI default.
 */
internal object InvoiceUnitLabels {
    private const val DETAIL_PREFIX = "invoiceDetail."
    private const val UNKNOWN_KEY = "invoiceDetail.unit.unknown"

    fun label(component: Component?, unit: InvoiceItemUnit, plural: Boolean = false): String =
        label(unit, plural) { key -> translate(component, key) }

    fun label(component: Component?, rawName: String?, plural: Boolean = false): String {
        val unit = rawName?.let { raw -> InvoiceItemUnit.entries.firstOrNull { it.name == raw } }
        return unit?.let { label(component, it, plural) } ?: translate(component, UNKNOWN_KEY) ?: "Unbekannte Einheit"
    }

    /** Pure resolver used by unit tests and by callers that already own a locale-aware resolver. */
    fun label(
        unit: InvoiceItemUnit,
        plural: Boolean = false,
        resolve: (String) -> String?,
    ): String = resolve("$DETAIL_PREFIX${unit.labelKey}")
        ?: resolve(unit.labelKey)
        ?: unit.germanLabel(plural)

    /**
     * Translates the unit argument carried by the unit consistency findings. Findings stay raw in
     * the domain, but their rendered validation sentence must never expose `SQUARE_METER` etc.
     */
    fun validationArgument(component: Component?, key: String, index: Int, argument: String): String =
        if (isUnitArgument(key, index)) label(component, argument) else argument

    private fun isUnitArgument(key: String, index: Int): Boolean = when (key) {
        "compliance.unit.unitCodeMismatch" -> index == 2
        "compliance.unit.quantityNotPositive",
        "compliance.unit.countableQuantityFractional",
        "compliance.unit.quantityLikeTokenInDescription" -> index == 1
        else -> false
    }

    private fun translate(component: Component?, key: String): String? {
        val language = component?.ui?.orElse(null)?.locale?.language
            ?: UI.getCurrent()?.locale?.language
            ?: Locale.GERMAN.language
        // ResourceBundle's normal locale fallback returns English for e.g. French. The invoice
        // contract only supports DE/EN, so unsupported locales must explicitly use German.
        val supportedLocale = if (language == Locale.ENGLISH.language) Locale.ENGLISH else Locale.GERMAN
        return UiTranslations.trOrNullForLocale(supportedLocale, key)
    }
}
