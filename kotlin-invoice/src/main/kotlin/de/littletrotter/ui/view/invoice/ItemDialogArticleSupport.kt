package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.invoice.port.inbound.ItemEntryQueryPort
import de.littletrotter.application.dto.ArticleDto
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.shared.catalogue.InvoiceItemCategory

/**
 * Article-selection helpers shared by [GoodsItemDialog] and [ServiceItemDialog]
 * (`BaseItemDialog` already sits at the detekt `TooManyFunctions` threshold, so these are
 * top-level functions rather than further base-class methods — the same idiom
 * `ItemDialogB2bSupport.kt`/`TaxRuleUiSupport.kt` already use in this package).
 */

/**
 * The tax rate an article selection applies: locked under reverse charge, the seller's own
 * variant abroad, or the domestic rule engine's proposal. Goods-/ServiceItemDialog resolved this
 * identically in their `applyArticleSelection` overrides (only [category] differed).
 */
internal fun resolveArticleTaxRate(
    article: ArticleDto,
    category: InvoiceItemCategory,
    taxRulePanel: TaxRuleProposalPanel?,
    taxContext: ArticleTaxContext,
): TaxRateDto? = when {
    taxContext.reverseChargeLocked -> taxContext.lockedRate
    taxContext.sellerCountry != null ->
        taxContext.rateById(article.taxVariantRateIdFor(taxContext.sellerCountry)) ?: taxContext.fallbackTaxRate
    else -> effectiveTaxRuleFor(
        category,
        taxRulePanel?.resolution,
        TaxRateCandidates(
            proposed = taxRulePanel?.proposedRate,
            articleDefault = taxContext.rateById(article.defaultTaxRateId),
            fallback = taxContext.fallbackTaxRate,
        ),
    )
}

/** The rate with [id] among [ArticleTaxContext.allTaxRates], or `null` when [id] is `null` or unknown. */
private fun ArticleTaxContext.rateById(id: Long?): TaxRateDto? = id?.let { wanted -> allTaxRates.firstOrNull { it.id == wanted } }

/**
 * An existing item's saved article, loaded into the article combo and its read-only
 * catalogue-number field. Goods-/ServiceItemDialog looked this up identically in
 * `loadExistingItemIntoFields`.
 */
internal fun loadArticleIntoFields(
    articleQueries: ItemEntryQueryPort,
    articleRefId: Long?,
    articleSelect: ComboBox<ArticleDto>,
    articleNumberField: TextField,
) {
    articleRefId?.let { articleId -> articleQueries.findArticleById(articleId) }?.let { article ->
        articleSelect.value = article
        articleNumberField.value = article.articleNumber.orEmpty()
    }
}

/** The read-only catalogue-number field next to the article select, identical in every item
 *  dialog that has one. */
internal fun buildArticleNumberField(tr: (String) -> String): TextField =
    TextField(tr("article.articleNumber")).apply {
        setWidthFull(); isReadOnly = true
        helperText = tr("invoiceDetail.itemDialog.catalogueNumberReadOnly")
    }

/** Applies the same column span to several [FormLayout] members; Goods-/ServiceItemDialog
 *  repeated one `setColspan` call per field with only the span value differing. */
internal fun applyColspan(layout: FormLayout, span: Int, vararg components: Component) {
    components.forEach { layout.setColspan(it, span) }
}
