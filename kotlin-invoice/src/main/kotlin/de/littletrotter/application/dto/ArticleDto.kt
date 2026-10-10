package de.littletrotter.application.dto

import de.littletrotter.shared.country.Country
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.Money
import java.math.BigDecimal

/**
 * One article of the catalogue: what the article grid, the article-select combos of the item
 * dialogs and the article editor read. [id] `null` never occurs on a stored row; [version] is the
 * row's optimistic-lock revision the editor sends back on save.
 *
 * The default tax rate is carried by id ([defaultTaxRateId]) plus its percentage for display
 * ([defaultTaxRatePercent]). [taxVariants] and [recipientRefs] are the article's owned rows; they
 * are only filled when the article is loaded on its own (by id or as a save result) — list and
 * combo rows leave them empty, so a caller that needs them loads the article by id.
 */
data class ArticleDto(
    val id: Long? = null,
    val version: Long? = null,
    val name: String = "",
    val description: String? = null,
    val category: InvoiceItemCategory = InvoiceItemCategory.SERVICE,
    val defaultUnit: InvoiceItemUnit = InvoiceItemUnit.HOUR,
    val defaultQuantity: BigDecimal = BigDecimal.ONE,
    val defaultUnitPriceNet: Money = Money.ZERO,
    val defaultTaxRateId: Long? = null,
    val defaultTaxRatePercent: TaxRatePercent? = null,
    val isDefault: Boolean = false,
    val shortHash: String? = null,
    val articleNumber: String? = null,
    val taxVariants: List<ArticleTaxVariantDto> = emptyList(),
    val recipientRefs: List<ArticleRecipientRefDto> = emptyList(),
) {
    /** The id of the tax rate this article sells at under a seller VAT registration in
     *  [country], or `null` when no such variant is maintained — callers treat `null` as
     *  "blocked", never as "fall back to the German default". */
    fun taxVariantRateIdFor(country: Country): Long? = taxVariants.firstOrNull { it.country == country }?.taxRateId
}

/** One per-country VAT variant of an article: the country of the seller's VAT registration and
 *  the id of the tax rate that applies there. */
data class ArticleTaxVariantDto(val country: Country, val taxRateId: Long)
