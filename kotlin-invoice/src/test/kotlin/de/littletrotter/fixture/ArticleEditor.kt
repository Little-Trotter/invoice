package de.littletrotter.fixture

import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.masterdata.ArticleDetails
import de.littletrotter.domain.masterdata.ArticleTaxVariant
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.ArticleNumber
import de.littletrotter.shared.Money
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Property-style access to the editable state of an [Article]; each write applies at once through its intent
 * method.
 *
 * Identity, lock version and the shipped-default flag have no public write path; they are hydrated the way a
 * loaded row carries them.
 */
class ArticleEditor internal constructor(val entity: Article) {
    var name: String
        get() = entity.name
        set(value) { entity.update(details().copy(name = value)) }
    var description: String?
        get() = entity.description
        set(value) { entity.update(details().copy(description = value)) }
    var category: InvoiceItemCategory
        get() = entity.category
        set(value) { entity.update(details().copy(category = value)) }
    var defaultUnit: InvoiceItemUnit
        get() = entity.defaultUnit
        set(value) { entity.update(details().copy(defaultUnit = value)) }
    var defaultQuantity: BigDecimal
        get() = entity.defaultQuantity
        set(value) { entity.update(details().copy(defaultQuantity = value)) }
    var defaultUnitPriceNet: Money
        get() = entity.defaultUnitPriceNet
        set(value) { entity.update(details().copy(defaultUnitPriceNet = value)) }
    var defaultTaxRateRef: TaxRate?
        get() = entity.defaultTaxRateRef
        set(value) { entity.update(details().copy(defaultTaxRateRef = value)) }
    var articleNumber: ArticleNumber?
        get() = entity.articleNumber
        set(value) { entity.update(details().copy(articleNumber = value)) }
    var shortHash: String?
        get() = entity.shortHash
        set(value) { entity.update(details().copy(shortHash = value)) }
    var articleNumberSequence: Long?
        get() = entity.articleNumberSequence
        set(value) { entity.assignArticleNumber(entity.articleNumberPrefix, value, entity.articleNumber) }
    var id: Long?
        get() = entity.id
        set(value) { PersistenceEmulation.assignId(entity, value) }
    var version: Long?
        get() = entity.version
        set(value) { PersistenceEmulation.assignVersion(entity, value) }
    var isDefault: Boolean
        get() = entity.isDefault
        set(value) { PersistenceEmulation.hydrate(entity, "isDefault", value) }

    private fun details() = ArticleDetails(
        name = entity.name,
        description = entity.description,
        category = entity.category,
        defaultUnit = entity.defaultUnit,
        defaultQuantity = entity.defaultQuantity,
        defaultUnitPriceNet = entity.defaultUnitPriceNet,
        defaultTaxRateRef = entity.defaultTaxRateRef,
        articleNumber = entity.articleNumber,
        shortHash = entity.shortHash,
    )
    fun update(details: ArticleDetails) = entity.update(details)

    fun replaceTaxVariants(next: List<ArticleTaxVariant>, today: LocalDate) = entity.replaceTaxVariants(next, today)
}

/** Edits [Article] in place through its domain operations. */
fun Article.edit(block: ArticleEditor.() -> Unit): Article = also { ArticleEditor(it).block() }

/**
 * An article built through the domain operations; every default is the entity default.
 */
fun anArticle(block: ArticleEditor.() -> Unit = {}): Article = Article().edit(block)
