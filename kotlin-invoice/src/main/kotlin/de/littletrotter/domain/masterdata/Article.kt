package de.littletrotter.domain.masterdata

import de.littletrotter.shared.country.Country
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.catalogue.LeistungsverzeichnisRules
import de.littletrotter.domain.shared.ReconciliationStrategy
import de.littletrotter.domain.shared.reconcileOwnedChildren
import de.littletrotter.domain.value.ArticleNumber
import de.littletrotter.shared.Money
import jakarta.persistence.Convert
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import jakarta.persistence.Version
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.LocalDate

@Entity
@Table(name = "article")
class Article {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
    protected set

    @field:NotBlank
    @field:Size(max = 255)
    var name: String = ""
    protected set

    @Column(columnDefinition = "TEXT")
    var description: String? = null
    protected set

    @Convert(converter = de.littletrotter.domain.shared.converters.InvoiceItemCategoryConverter::class)
    @Column(nullable = false)
    var category: InvoiceItemCategory = InvoiceItemCategory.SERVICE
    protected set

    @Convert(converter = de.littletrotter.domain.shared.converters.InvoiceItemUnitConverter::class)
    @Column(nullable = false)
    var defaultUnit: InvoiceItemUnit = InvoiceItemUnit.HOUR
    protected set

    @Column(precision = 14, scale = 4)
    var defaultQuantity: BigDecimal = BigDecimal.ONE
    protected set

    @Column(precision = 14, scale = 2)
    @Convert(converter = de.littletrotter.domain.shared.MoneyConverter::class)
    var defaultUnitPriceNet: Money = Money.ZERO
    protected set

    // Stays EAGER: the maintenance UI maps a detached article to its DTO and reads the rate
    // percentage after the repository transaction has ended.
    @ManyToOne(fetch = jakarta.persistence.FetchType.EAGER)
    var defaultTaxRateRef: TaxRate? = null
    protected set

    @Column(name = "is_default")
    var isDefault: Boolean = false
    protected set

    // ── Kennungen: Kurzname + Artikelnummer ───────────────────────

    /** Technical handle; editable in UI, auto-assigned if empty. */
    @field:Size(max = 10)
    @Column(name = "short_hash", length = 10, unique = true)
    var shortHash: String? = null
    protected set

    /** Article number (e.g., ART-00042); assigned once. `null` = never numbered. */
    @Column(name = "article_number")
    var articleNumber: ArticleNumber? = null
    protected set

    /** Prefix as separate column for reporting. */
    @field:Size(max = 30)
    @Column(name = "article_number_prefix")
    var articleNumberPrefix: String? = null
    protected set

    /** Counter as number, not text pattern (for sorting/gap-check). */
    @Column(name = "article_number_sequence")
    var articleNumberSequence: Long? = null
    protected set

    /** 0..n per-country MWST variants (curated country set,
     *  [Country.SUPPORTED_SELLER_VAT_REGISTRATION_COUNTRIES]). Stays empty until a variant is
     *  actually added via the maintenance UI. */
    @OneToMany(mappedBy = "article", cascade = [jakarta.persistence.CascadeType.ALL], orphanRemoval = true)
    private var taxVariantRows: MutableList<ArticleTaxVariant> = mutableListOf()

    val taxVariants: List<ArticleTaxVariant>
        get() = java.util.Collections.unmodifiableList(taxVariantRows)

    /** 0..n per-recipient identifier mappings (buyer's article number/name). Stays
     *  empty until a mapping is actually added via the maintenance UI. */
    @OneToMany(mappedBy = "article", cascade = [jakarta.persistence.CascadeType.ALL], orphanRemoval = true)
    private var recipientRefRows: MutableList<ArticleRecipientRef> = mutableListOf()

    val recipientRefs: List<ArticleRecipientRef>
        get() = java.util.Collections.unmodifiableList(recipientRefRows)

    @Version
    var version: Long? = 0
    protected set

    /** Writes the plain editable fields in one step; tax variants and recipient mappings go
     *  through [replaceTaxVariants] / [replaceRecipientRefs]. */
    fun update(details: ArticleDetails) {
        name = details.name
        description = details.description
        category = details.category
        defaultUnit = details.defaultUnit
        defaultQuantity = details.defaultQuantity
        defaultUnitPriceNet = details.defaultUnitPriceNet
        defaultTaxRateRef = details.defaultTaxRateRef
        articleNumber = details.articleNumber
        shortHash = details.shortHash
    }

    /** Stores the allocated article number together with the range parts it was built from. */
    fun assignArticleNumber(prefix: String?, sequence: Long?, number: ArticleNumber?) {
        articleNumberPrefix = prefix
        articleNumberSequence = sequence
        articleNumber = number
    }

    /** Drops the article number so a fresh one can be allocated; prefix and counter are
     *  overwritten by that allocation. */
    fun clearArticleNumber() {
        articleNumber = null
    }

    /** The [TaxRate] this article sells at under a seller VAT registration in
     *  [country], or `null` when no such variant is maintained. `null` must be treated by callers
     *  as "blocked", never as "fall back to the German default" — the same never-silently-
     *  guess philosophy as [de.littletrotter.shared.tax.TaxRuleResolver]. */
    fun taxVariantFor(country: Country): TaxRate? = taxVariants.firstOrNull { it.country == country }?.taxRateRef

    /** Replaces the full set of tax variants. The maintenance UI always submits the whole edited
     *  list, not incremental add/remove operations. */
    fun replaceTaxVariants(next: List<ArticleTaxVariant>, today: LocalDate) {
        reconcileOwnedChildren(
            current = taxVariantRows,
            proposed = next,
            strategy = ReconciliationStrategy(
                key = { it.country },
                validate = { candidates ->
                    ArticleTaxVariant.assertUniqueCountries(candidates)
                    candidates.forEach { it.validate(today) }
                },
                // `it.taxRateRef` is guaranteed non-null here: `validate` above already ran
                // `it.validate()` on every candidate in this same list, and ArticleTaxVariant.validate()
                // throws for a null taxRateRef before reconcileOwnedChildren ever reaches `create`. No
                // requireNotNull/`!!` here — ArticleTaxVariant.of accepts the still-nullable field type
                // and re-asserts via its own (already fully branch-covered) validate() call instead of
                // duplicating a provably-dead null check in this lambda.
                create = { ArticleTaxVariant.of(this, it.country, it.taxRateRef, today) },
                update = { retained, candidate -> retained.changeTaxRate(candidate.taxRateRef) },
            ),
        )
    }

    /** This article's mapping for the recipient with id [recipientId], or `null` when
     *  none is maintained. */
    fun recipientRefFor(recipientId: Long): ArticleRecipientRef? =
        recipientRefs.firstOrNull { it.recipient?.id == recipientId }

    /** Replaces the full set of recipient mappings. The maintenance UI always submits the whole
     *  edited list, not incremental add/remove operations — mirrors [replaceTaxVariants]. */
    fun replaceRecipientRefs(next: List<ArticleRecipientRef>) {
        reconcileOwnedChildren(
            current = recipientRefRows,
            proposed = next,
            strategy = ReconciliationStrategy(
                key = { it.recipient?.id },
                validate = { candidates ->
                    ArticleRecipientRef.assertUniqueRecipients(candidates)
                    candidates.forEach { it.validate() }
                },
                // Same reasoning as replaceTaxVariants above: `it.recipient` is guaranteed
                // non-null here by `it.validate()` already having run in `validate`; ArticleRecipientRef.of
                // takes the still-nullable field and re-asserts via its own validate() instead of a
                // second, provably-dead null check in this lambda.
                create = { ArticleRecipientRef.of(this, it.recipient, it.buyerArticleNumber, it.buyerArticleName) },
                update = { retained, candidate ->
                    retained.rename(candidate.buyerArticleNumber, candidate.buyerArticleName)
                },
            ),
        )
    }

    /** Apply a validated edit to the managed aggregate without replacing persistent children. */
    fun copyEditableFieldsFrom(other: Article, today: LocalDate) {
        other.validate(today)
        replaceTaxVariants(other.taxVariants, today)
        replaceRecipientRefs(other.recipientRefs)
        name = other.name
        description = other.description
        category = other.category
        defaultUnit = other.defaultUnit
        defaultQuantity = other.defaultQuantity
        defaultUnitPriceNet = other.defaultUnitPriceNet
        defaultTaxRateRef = other.defaultTaxRateRef
        isDefault = other.isDefault
        shortHash = other.shortHash
        articleNumber = other.articleNumber
    }

    fun validate(today: LocalDate) {
        ArticleTaxVariant.assertUniqueCountries(taxVariants)
        taxVariants.forEach { it.validate(today) }
        ArticleRecipientRef.assertUniqueRecipients(recipientRefs)
        recipientRefs.forEach { it.validate() }
        validateNameAndQuantity()
        validatePriceAndUnit()
        validateNumberSequence()
    }

    // ThrowsCount — validate()'s five field checks split across three helpers so each
    // stays under ThrowsCount on its own; behaviour is unchanged.
    private fun validateNameAndQuantity() {
        if (name.isBlank()) throw InvalidParameterException("Artikelname darf nicht leer sein.")
        if (defaultQuantity <= BigDecimal.ZERO) throw InvalidParameterException("Standardmenge muss größer als 0 sein.")
    }

    private fun validatePriceAndUnit() {
        if (defaultUnitPriceNet.isNegative()) throw InvalidParameterException("Standardpreis netto darf nicht negativ sein.")
        if (!LeistungsverzeichnisRules.isAllowed(category, defaultUnit))
            throw InvalidParameterException("Die Einheit ist für diese Leistungsverzeichnis-Kategorie nicht zulässig.")
    }

    private fun validateNumberSequence() {
        // A blank, non-null article number is now unrepresentable: ArticleNumber's own
        // init throws at construction instead of here (see ArticleNumberTest).
        // IMP-5 — changed from `<= 0` which contradicted the number range this counter comes from.
        // NumberRange.validate() explicitly allows startValue == 0, and startValue IS the first
        // assigned number. An article range with startValue 0 made creation of the first article
        // impossible: assignment would return 0, then this check rejected it.
        if (articleNumberSequence != null && articleNumberSequence!! < 0)
            throw InvalidParameterException("Zähler der Artikelnummer darf nicht negativ sein, wenn gesetzt.")
    }

    /**
     * Ensures a short hash when saving, if none exists (stock and new records both get one).
     * Returns `true` if a new one was assigned.
     */
    fun ensureShortHash(): Boolean {
        if (shortHash == null) {
            shortHash = de.littletrotter.domain.shared.ShortHash.random()
            return true
        }
        return false
    }

    companion object {
        /** An unmanaged edit copy that carries the stored article's identity (id, revision and
         *  the shipped-default flag, which the form cannot change) so the save path can match it
         *  against the stored row. */
        fun detachedEdit(id: Long?, version: Long?, isDefault: Boolean): Article = Article().also {
            it.id = id
            it.version = version
            it.isDefault = isDefault
        }
    }
}
