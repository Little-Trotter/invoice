package de.littletrotter.fixture

import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.masterdata.ArticleRecipientRef
import de.littletrotter.domain.masterdata.Recipient

/**
 * Property-style access to the state of an [ArticleRecipientRef].
 *
 * Owner and identity are assigned by `ArticleRecipientRef.of` and persistence; a mapping without them is a
 * state only hydration produces.
 */
class ArticleRecipientRefEditor internal constructor(val entity: ArticleRecipientRef) {
    var buyerArticleNumber: String?
        get() = entity.buyerArticleNumber
        set(value) { entity.rename(value, entity.buyerArticleName) }
    var id: Long?
        get() = entity.id
        set(value) { PersistenceEmulation.assignId(entity, value) }
    var article: Article?
        get() = entity.article
        set(value) { PersistenceEmulation.hydrate(entity, "article", value) }
    var recipient: Recipient?
        get() = entity.recipient
        set(value) { PersistenceEmulation.hydrate(entity, "recipient", value) }
    var version: Long?
        get() = entity.version
        set(value) { PersistenceEmulation.assignVersion(entity, value) }
}

/** Edits [ArticleRecipientRef] in place through its domain operations. */
fun ArticleRecipientRef.edit(block: ArticleRecipientRefEditor.() -> Unit): ArticleRecipientRef = also { ArticleRecipientRefEditor(it).block() }

/**
 * A blank buyer-article mapping; `ArticleRecipientRef.of` validates, so incomplete mappings can only be built
 * blank.
 */
fun anArticleRecipientRef(block: ArticleRecipientRefEditor.() -> Unit = {}): ArticleRecipientRef = ArticleRecipientRef().edit(block)
