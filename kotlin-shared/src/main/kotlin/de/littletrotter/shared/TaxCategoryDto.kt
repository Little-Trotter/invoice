package de.littletrotter.shared

/** Whether tax category can be trusted ([SNAPSHOT]) or is incomplete legacy ([LEGACY_UNCLASSIFIED]).
 *  Never guess from 0% rate; render unclassified positions as such, never as Reverse Charge. */
enum class TaxCategoryOrigin { SNAPSHOT, LEGACY_UNCLASSIFIED }

/** [category] is `null` exactly when [origin] is [TaxCategoryOrigin.LEGACY_UNCLASSIFIED]. */
data class ResolvedTaxCategory(val category: String?, val origin: TaxCategoryOrigin)

fun InvoiceItemDto.resolveTaxCategory(): ResolvedTaxCategory =
    if (taxCategory != null) ResolvedTaxCategory(taxCategory, TaxCategoryOrigin.SNAPSHOT)
    else ResolvedTaxCategory(null, TaxCategoryOrigin.LEGACY_UNCLASSIFIED)
