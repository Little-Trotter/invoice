package de.littletrotter.domain.masterdata

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.reference.Language
import de.littletrotter.domain.reference.LocalizedTextGroup
import de.littletrotter.domain.reference.LocalizedTextPurpose
import de.littletrotter.domain.shared.CreationStampListener
import de.littletrotter.domain.shared.StampedOnCreation
import de.littletrotter.domain.value.DiscountPercent
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import jakarta.persistence.Version
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.time.OffsetDateTime

@Entity
@Table(name = "payment_term")
@EntityListeners(CreationStampListener::class)
class PaymentTerm : StampedOnCreation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
    protected set

    /**
     * The term's display name, per language ([LocalizedTextPurpose.PAYMENT_TERM_NAME]).
     * Replaces the former `name`/`english_name VARCHAR(150)` columns; the fallback (German)
     * language's entry is mandatory (see [validate]), any other active language's entry is
     * optional, the same shape as [SenderProfile.invoiceNote][de.littletrotter.domain.masterdata.SenderProfile.invoiceNote]
     * minus the "no note at all is normal" case — a payment term without any name is not a
     * valid one.
     */
    @field:NotNull
    @OneToOne(cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "name_group_id", nullable = false)
    var nameGroup: LocalizedTextGroup? = null
    protected set

    /**
     * The resolved name for [language] (a document language code, e.g. `"de"`/`"en"`); see
     * [LocalizedTextGroup.resolvedFor]. The fallback attempt only ever succeeds when the fallback
     * language's own entry is present in [nameGroup], which [validate] already requires.
     */
    fun nameFor(language: String): String? = nameGroup?.resolvedFor(language)

    /**
     * The entry actually captured for [languageCode], or `null` when none exists.
     * Unlike [nameFor], no fallback: for editing/admin-grid display, where "nothing captured" and
     * "same as the German name" must stay distinguishable, not blurred by
     * [LocalizedTextGroup.resolvedFor]'s printing-time fallback.
     */
    fun rawNameFor(languageCode: String): String? =
        nameGroup?.entries?.firstOrNull { it.language?.code == languageCode }?.value

    /**
     * Sets (or clears) [language]'s entry of [nameGroup]. A blank [value] removes the
     * entry; unlike [SenderProfile.changeInvoiceNote][de.littletrotter.domain.masterdata.SenderProfile.changeInvoiceNote]
     * the group itself is never nulled out here even once empty — [validate] rejects a term
     * without a fallback-language name, so a caller that clears the last entry gets a domain
     * exception on save rather than a silently persisted nameless term.
     */
    fun changeName(language: Language, value: String?) {
        val text = value?.trim()?.ifEmpty { null }
        val group = nameGroup ?: LocalizedTextGroup.forPurpose(LocalizedTextPurpose.PAYMENT_TERM_NAME).also {
            nameGroup = it
        }
        if (text == null) {
            group.removeText(language)
            return
        }
        group.putText(language, text)
    }

    @Column(columnDefinition = "TEXT")
    var description: String? = null
    protected set

    var dueInDays: Int = 30
    protected set

    @Column(precision = 5, scale = 2)
    var discountPercent: DiscountPercent = DiscountPercent.ZERO
    protected set

    var discountDays: Int? = null
    protected set
    var isDefault: Boolean = false
    protected set

    /**
     * Legacy payment terms are deactivated, not deleted; existing invoices continue to point
     * unchanged to their (now inactive) row.
     */
    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true
    protected set

    var createdAt: OffsetDateTime? = null
    protected set

    override fun stampCreationIfUnset(now: OffsetDateTime) {
        if (createdAt == null) createdAt = now
    }

    @Version
    var version: Long? = 0
    protected set

    /** Writes the plain editable fields in one step; the localized name goes through [setName]. */
    fun update(details: PaymentTermDetails) {
        description = details.description
        dueInDays = details.dueInDays
        discountPercent = details.discountPercent
        discountDays = details.discountDays
        isDefault = details.isDefault
        isActive = details.isActive
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PaymentTerm) return false
        if (id != null && other.id != null) return id == other.id
        return false
    }

    override fun hashCode(): Int = PaymentTerm::class.java.hashCode()

    fun validate() {
        validateName()
        nameGroup?.validate()
        validateDays()
        validateDiscountCombination()
    }

    // ThrowsCount — validate()'s four checks split across three helpers so each stays
    // under ThrowsCount on its own; behaviour is unchanged.
    private fun validateName() {
        val hasFallbackName = nameGroup?.entries?.any { it.language?.isFallback == true && it.value.isNotBlank() } ?: false
        if (!hasFallbackName) throw InvalidParameterException("Zahlungszielname darf nicht leer sein.")
    }

    private fun validateDays() {
        if (dueInDays < 0) throw InvalidParameterException("Zahlungsziel in Tagen darf nicht negativ sein.")
        if ((discountDays ?: 0) < 0) throw InvalidParameterException("Skontotage dürfen nicht negativ sein.")
    }

    private fun validateDiscountCombination() {
        // The 0..100 range is now DiscountPercent's own constructor invariant; a
        // PaymentTerm can never hold an out-of-range value to check here any more.
        // Discount is only meaningful as a pair: rate without days or days without rate is not a
        // valid payment term (mirrors chk_payment_term_discount_combination, enforced at DB
        // level since V001).
        if ((discountPercent.value > BigDecimal.ZERO) != (discountDays != null))
            throw InvalidParameterException("Skontosatz und Skontofrist müssen entweder beide gesetzt oder beide leer sein.")
    }
}
