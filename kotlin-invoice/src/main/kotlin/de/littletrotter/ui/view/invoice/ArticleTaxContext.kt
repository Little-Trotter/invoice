package de.littletrotter.ui.view.invoice

import de.littletrotter.shared.country.Country
import de.littletrotter.application.dto.TaxRateDto

/**
 * The subset of [BaseItemDialog]'s own tax-context state
 * ([BaseItemDialog.reverseChargeLocked]/[BaseItemDialog.lockedRate]/
 * [BaseItemDialog.sellerCountry]/[BaseItemDialog.fallbackTaxRate]) `resolveArticleTaxRate`
 * (`ItemDialogArticleSupport.kt`) needs, plus [allTaxRates] to turn the article's tax-rate ids
 * back into the rates the tax combo offers; bundled into one parameter to stay under detekt's
 * `LongParameterList` threshold. Own file: detekt's `MatchingDeclarationName` requires a
 * single top-level class/data-class to share its file's name.
 */
internal data class ArticleTaxContext(
    val reverseChargeLocked: Boolean,
    val lockedRate: TaxRateDto?,
    val sellerCountry: Country?,
    val fallbackTaxRate: TaxRateDto?,
    val allTaxRates: List<TaxRateDto>,
)
