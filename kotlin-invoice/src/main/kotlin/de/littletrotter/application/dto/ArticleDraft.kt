package de.littletrotter.application.dto

import de.littletrotter.application.command.Command
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.Money
import java.math.BigDecimal

/**
 * The article editor's write command: every field the form collects, handed to
 * [de.littletrotter.application.reference.port.inbound.ManageArticleUseCase.saveArticle], which
 * owns turning it into the stored article.
 *
 * [id] `null` creates a new article; [version] is the revision the edit started from.
 * [articleNumber] and [shortHash] are what the user typed: `null` lets the save assign one, a
 * typed value is kept as is. [proposedArticleNumber] is the number the form showed as a proposal,
 * so the save can tell an accepted proposal from a hand-typed number.
 *
 * [taxVariants] and [recipientRefs] replace the article's owned rows in full; `null` leaves the
 * stored rows untouched (the editor offers no such section).
 */
data class ArticleDraft(
    val id: Long? = null,
    val version: Long? = null,
    val name: String = "",
    val description: String? = null,
    val category: InvoiceItemCategory = InvoiceItemCategory.SERVICE,
    val defaultUnit: InvoiceItemUnit = InvoiceItemUnit.HOUR,
    val defaultQuantity: BigDecimal = BigDecimal.ONE,
    val defaultUnitPriceNet: Money = Money.ZERO,
    val defaultTaxRateId: Long? = null,
    val articleNumber: String? = null,
    val shortHash: String? = null,
    val taxVariants: List<ArticleTaxVariantDto>? = null,
    val recipientRefs: List<ArticleRecipientRefDto>? = null,
    val proposedArticleNumber: String? = null,
) : Command
