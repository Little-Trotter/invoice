package de.littletrotter.domain.masterdata

import de.littletrotter.shared.country.Country
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.reference.TaxRate
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.LocalDate

/**
 * A per-country MWST variant of an [Article]: which [TaxRate] applies when this
 * article is sold under a seller VAT registration in [country] (
 * [Country.supportedSellerVatRegistrationCountries]) instead of the German default
 * ([Article.defaultTaxRateRef]). At most one variant per country per article (DB unique index
 * `uq_article_tax_variant_article_country`, checked again here via [assertUniqueCountries]).
 *
 * Generic across every country in the curated set (not Luxembourg-specific by construction) —
 * v1 just doesn't maintain any FR/BE/NL variants yet (the target picture only requires
 * Luxembourg for now, not a code restriction).
 */
@Entity
@Table(name = "article_tax_variant")
class ArticleTaxVariant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
    protected set

    // LAZY: the owning article is already in hand when its variants are read.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id", nullable = false)
    var article: Article? = null
    protected set

    @Convert(converter = CountryAttributeConverter::class)
    @Column(name = "country", nullable = false)
    var country: Country = Country.DE
    protected set

    // LAZY: readers of a stored variant only need the rate id; the rate row is loaded on demand.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tax_rate_id", nullable = false)
    var taxRateRef: TaxRate? = null
    protected set

    @Version
    var version: Long? = 0
    protected set

    /** Points the variant at another tax rate; [validate] checks it belongs to [country]. */
    fun changeTaxRate(taxRateRef: TaxRate?) {
        this.taxRateRef = taxRateRef
    }

    fun validate(today: LocalDate) {
        if (country !in Country.supportedSellerVatRegistrationCountries(today)) {
            throw InvalidParameterException(
                "Land ${country.alpha2} ist für Artikel-Länder-Varianten nicht freigegeben " +
                    "(erlaubt: ${Country.supportedSellerVatRegistrationCountries(today).joinToString { it.alpha2 }})."
            )
        }
        val rate = taxRateRef ?: throw InvalidParameterException("Steuersatz der Variante muss gesetzt sein.")
        if (rate.country != country) {
            throw InvalidParameterException(
                "Der gewählte Steuersatz gehört zu ${rate.country.alpha2}, nicht zur Variante ${country.alpha2}."
            )
        }
    }

    companion object {
        /** [taxRate] stays nullable (not `TaxRate`) so a caller that already proved
         *  non-nullness elsewhere (e.g. [Article.replaceTaxVariants]'s `create` lambda, run only
         *  after `it.validate()` already succeeded for every candidate) never needs a redundant
         *  `requireNotNull`/`!!`: [validate] below already throws [InvalidParameterException] for a
         *  null [taxRateRef], the one authoritative check. */
        fun of(article: Article, country: Country, taxRate: TaxRate?, today: LocalDate): ArticleTaxVariant =
            ArticleTaxVariant().apply {
                this.article = article
                this.country = country
                this.taxRateRef = taxRate
                validate(today)
            }

        /** Rejects a candidate list carrying the same [ArticleTaxVariant.country] twice. The DB
         *  unique index enforces this too, but failing here first gives the maintenance UI a
         *  clear domain message instead of a generic constraint violation. */
        fun assertUniqueCountries(variants: List<ArticleTaxVariant>) {
            val duplicated = variants.groupBy { it.country }.filterValues { it.size > 1 }.keys
            if (duplicated.isNotEmpty()) {
                throw InvalidParameterException(
                    "Land ${duplicated.first().alpha2} ist mehrfach angegeben — je Land ist höchstens " +
                        "eine Variante zulässig."
                )
            }
        }
    }
}
