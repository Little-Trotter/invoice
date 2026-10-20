package de.littletrotter.fixture

import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.masterdata.ArticleTaxVariant
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.shared.country.Country

/**
 * Property-style access to the state of an [ArticleTaxVariant].
 *
 * Owner, country and identity are assigned by `ArticleTaxVariant.of` and persistence; a variant without them
 * is a state only hydration produces.
 */
class ArticleTaxVariantEditor internal constructor(val entity: ArticleTaxVariant) {
    var taxRateRef: TaxRate?
        get() = entity.taxRateRef
        set(value) { entity.changeTaxRate(value) }
    var id: Long?
        get() = entity.id
        set(value) { PersistenceEmulation.assignId(entity, value) }
    var article: Article?
        get() = entity.article
        set(value) { PersistenceEmulation.hydrate(entity, "article", value) }
    var country: Country
        get() = entity.country
        set(value) { PersistenceEmulation.hydrate(entity, "country", value) }
    var version: Long?
        get() = entity.version
        set(value) { PersistenceEmulation.assignVersion(entity, value) }
}

/** Edits [ArticleTaxVariant] in place through its domain operations. */
fun ArticleTaxVariant.edit(block: ArticleTaxVariantEditor.() -> Unit): ArticleTaxVariant = also { ArticleTaxVariantEditor(it).block() }

/**
 * A blank tax variant; `ArticleTaxVariant.of` validates, so incomplete variants can only be built blank.
 */
fun anArticleTaxVariant(block: ArticleTaxVariantEditor.() -> Unit = {}): ArticleTaxVariant = ArticleTaxVariant().edit(block)
