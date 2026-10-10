package de.littletrotter.application.dto

/**
 * How one recipient identifies an article on their side: the buyer's own article number and/or
 * name for the recipient with id [recipientId]. At least one of the two must be set on save.
 */
data class ArticleRecipientRefDto(
    val recipientId: Long,
    val buyerArticleNumber: String? = null,
    val buyerArticleName: String? = null,
)
