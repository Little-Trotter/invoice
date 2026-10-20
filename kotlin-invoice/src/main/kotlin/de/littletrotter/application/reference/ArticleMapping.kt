package de.littletrotter.application.reference

import de.littletrotter.domain.masterdata.ArticleDetails
import de.littletrotter.application.dto.ArticleDraft
import de.littletrotter.application.dto.ArticleDto
import de.littletrotter.application.dto.ArticleRecipientRefDto
import de.littletrotter.application.dto.ArticleTaxVariantDto
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.masterdata.ArticleRecipientRef
import de.littletrotter.domain.masterdata.ArticleTaxVariant
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.ArticleNumber

/**
 * View of a stored article. [withOwnedRows] also maps the tax variants and recipient mappings;
 * list and combo rows leave them out, because those rows are loaded without their owned
 * collections.
 */
internal fun Article.toDto(withOwnedRows: Boolean = true): ArticleDto = ArticleDto(
    id = id,
    version = version,
    name = name,
    description = description,
    category = category,
    defaultUnit = defaultUnit,
    defaultQuantity = defaultQuantity,
    defaultUnitPriceNet = defaultUnitPriceNet,
    defaultTaxRateId = defaultTaxRateRef?.id,
    defaultTaxRatePercent = defaultTaxRateRef?.rate,
    isDefault = isDefault,
    shortHash = shortHash,
    articleNumber = articleNumber?.value,
    taxVariants = if (withOwnedRows) taxVariants.mapNotNull { it.toDto() } else emptyList(),
    recipientRefs = if (withOwnedRows) recipientRefs.mapNotNull { it.toDto() } else emptyList(),
)

/** A stored variant always carries its tax rate (validated on save); `null` only for a row that
 *  was never persisted. */
internal fun ArticleTaxVariant.toDto(): ArticleTaxVariantDto? =
    taxRateRef?.id?.let { ArticleTaxVariantDto(country, it) }

/** A stored mapping always carries its recipient (validated on save); `null` only for a row that
 *  was never persisted. */
internal fun ArticleRecipientRef.toDto(): ArticleRecipientRefDto? =
    recipient?.id?.let { ArticleRecipientRefDto(it, buyerArticleNumber, buyerArticleName) }

/** How [applyTo] turns the draft's ids back into the stored rows they reference. */
internal class ArticleDraftReferences(
    val taxRate: (Long) -> TaxRate,
    val recipient: (Long) -> Recipient,
)

/**
 * Copies every editable field of this draft onto [article], resolving the default tax rate, the
 * variants' tax rates and the mappings' recipients through [references]. When the draft carries
 * no variant or mapping rows, [stored]'s rows are carried over unchanged.
 */
internal fun ArticleDraft.applyTo(article: Article, stored: Article?, references: ArticleDraftReferences, today: java.time.LocalDate) {
    article.update(
        ArticleDetails(
            name = name,
            description = description,
            category = category,
            defaultUnit = defaultUnit,
            defaultQuantity = defaultQuantity,
            defaultUnitPriceNet = defaultUnitPriceNet,
            defaultTaxRateRef = defaultTaxRateId?.let(references.taxRate),
            articleNumber = articleNumber?.let(::ArticleNumber),
            shortHash = shortHash,
        ),
    )
    val variants = taxVariants?.map { ArticleTaxVariant.of(article, it.country, references.taxRate(it.taxRateId), today) }
        ?: stored?.taxVariants.orEmpty().map { ArticleTaxVariant.of(article, it.country, it.taxRateRef, today) }
    article.replaceTaxVariants(variants, today)
    val mappings = recipientRefs?.map {
        ArticleRecipientRef.of(article, references.recipient(it.recipientId), it.buyerArticleNumber, it.buyerArticleName)
    } ?: stored?.recipientRefs.orEmpty().map { ArticleRecipientRef.of(article, it.recipient, it.buyerArticleNumber, it.buyerArticleName) }
    article.replaceRecipientRefs(mappings)
}
