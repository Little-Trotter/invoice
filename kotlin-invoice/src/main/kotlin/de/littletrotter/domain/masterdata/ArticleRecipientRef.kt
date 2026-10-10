package de.littletrotter.domain.masterdata

import de.littletrotter.domain.exception.InvalidParameterException
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.Version
import jakarta.validation.constraints.Size

/**
 * A per-recipient identifier mapping for an [Article]: the buyer's own article
 * number ([buyerArticleNumber], → BT-156) and, optionally, the buyer's own article designation
 * ([buyerArticleName], → BT-153 prefill for a new position). Neither can live on [Article] itself
 * (seller-side, shared across every recipient, → BT-155) nor on a B2B profile default (shared
 * across every recipient of that profile) — the whole point of this entity is that the same
 * article carries a *different* buyer-side identifier per customer.
 *
 * At most one row per (article, recipient) — DB unique index `uq_article_recipient_ref_article_recipient`,
 * checked again here via [assertUniqueRecipients] so the maintenance UI gets a domain message
 * instead of a generic constraint violation. Follows [ArticleTaxVariant]'s no-arg-constructor
 * style (plain class, body-level `var`s) rather than a data class or the `kotlin-noarg` plugin.
 */
@Entity
@Table(name = "article_recipient_ref")
class ArticleRecipientRef {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @ManyToOne
    @JoinColumn(name = "article_id", nullable = false)
    var article: Article? = null

    @ManyToOne
    @JoinColumn(name = "recipient_id", nullable = false)
    var recipient: Recipient? = null

    /** → BT-156 *Buyer's item identification*, prefilled into the item dialog's always-visible
     *  buyer-item-number field when this mapping applies. */
    @field:Size(max = 50)
    @Column(name = "buyer_article_number")
    var buyerArticleNumber: String? = null

    /** → BT-153 *Item name*, prefilled as the description of a **new** position only (an existing
     *  position's description is never retroactively overwritten). */
    @field:Size(max = 255)
    @Column(name = "buyer_article_name")
    var buyerArticleName: String? = null

    @Version
    var version: Long? = 0

    /** An empty mapping row (neither identifier set) is meaningless — reject it. */
    fun validate() {
        if (recipient?.id == null) {
            throw InvalidParameterException("Bitte ein gespeichertes Kundenprofil für die Artikelkennung auswählen.")
        }
        if (buyerArticleNumber.isNullOrBlank() && buyerArticleName.isNullOrBlank()) {
            throw InvalidParameterException(
                "Mindestens Käufer-Artikelnummer oder Käufer-Artikelbezeichnung muss angegeben werden."
            )
        }
    }

    companion object {
        /** [recipient] stays nullable so a caller that already proved non-nullness
         *  elsewhere (e.g. [Article.replaceRecipientRefs]'s `create` lambda, run only after
         *  `it.validate()` already succeeded for every candidate) never needs a redundant
         *  `requireNotNull`/`!!`: [validate] below already throws [InvalidParameterException] for a
         *  null (or unsaved) [recipient], the one authoritative check. */
        fun of(article: Article, recipient: Recipient?, buyerArticleNumber: String?, buyerArticleName: String?): ArticleRecipientRef =
            ArticleRecipientRef().apply {
                this.article = article
                this.recipient = recipient
                this.buyerArticleNumber = buyerArticleNumber?.trim()?.ifBlank { null }
                this.buyerArticleName = buyerArticleName?.trim()?.ifBlank { null }
                validate()
            }

        /** Rejects a candidate list carrying the same [ArticleRecipientRef.recipient] twice — each
         *  recipient may be mapped at most once per article. The DB unique index enforces this
         *  too, but failing here first gives the maintenance UI a clear domain message. */
        fun assertUniqueRecipients(refs: List<ArticleRecipientRef>) {
            val duplicated = refs.groupBy { it.recipient?.id }.filterValues { it.size > 1 }.keys
            if (duplicated.isNotEmpty()) {
                throw InvalidParameterException(
                    "Dieser Kunde ist mehrfach angegeben — je Kunde ist höchstens eine Kennung zulässig."
                )
            }
        }
    }
}
