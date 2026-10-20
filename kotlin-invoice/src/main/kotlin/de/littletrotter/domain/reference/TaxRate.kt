package de.littletrotter.domain.reference

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.CountryAttributeConverter
import de.littletrotter.domain.shared.CreationStampListener
import de.littletrotter.domain.shared.StampedOnCreation
import de.littletrotter.domain.value.TaxRatePercent
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * The editable content of one tax rule. Defaults mirror a blank, selectable, active rule so a
 * caller that only cares about the percentage (a transient rate for a calculation) states just that.
 */
data class TaxRateDefinition(
    val name: String = "",
    val rate: TaxRatePercent = TaxRatePercent(BigDecimal.ZERO),
    val country: Country = Country.DE,
    val validFrom: LocalDate? = null,
    val validTo: LocalDate? = null,
    val isHotelRate: Boolean = false,
    val isDefault: Boolean = false,
    val isReference: Boolean = false,
    val code: String? = null,
    val category: String,
    val exemptionReasonCode: String? = null,
    val exemptionReasonText: String? = null,
    val legalBasis: String? = null,
    val isActive: Boolean = true,
    val isSelectable: Boolean = true,
)

@Entity
@Table(name = "tax_rate")
@EntityListeners(CreationStampListener::class)
class TaxRate : TaxRateTerms, StampedOnCreation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:Size(max = 255)
    var name: String = ""
        protected set

    @Column(precision = 5, scale = 2)
    override var rate: TaxRatePercent = TaxRatePercent(BigDecimal.ZERO)
        protected set

    @jakarta.persistence.Convert(converter = CountryAttributeConverter::class)
    @Column(name = "country")
    override var country: Country = Country.DE
        protected set
    override var validFrom: LocalDate? = null
        protected set
    override var validTo: LocalDate? = null
        protected set
    @Column(name = "is_hotel_rate")
    override var isHotelRate: Boolean = false
        protected set
    var isDefault: Boolean = false
        protected set
    @Column(name = "is_reference")
    override var isReference: Boolean = false
        protected set

    // ── Rule instead of a plain percentage ───────────────────────────────────
    // Nullable on the entity despite the DB NOT NULL (code) resp. DB default (isActive/
    // isSelectable): instances that need to be validated before saving (i.e. before a default
    // value is applied) must be able to represent an empty/blank state — validate() checks
    // exactly that.

    /** Stable, unique rule code (e.g. `DE_STANDARD_19`) — display/export reference this, not
     *  [name]/[rate]. */
    @field:Size(max = 64)
    override var code: String? = null
        protected set

    /** UNTDID 5305 / EN-16931 Duty-and-Tax-Category-Code: `S`, `AE`, `E`, `O`; never blank once validated. */
    @field:Size(max = 4)
    override var category: String = ""
        protected set

    @field:Size(max = 32)
    @Column(name = "exemption_reason_code")
    var exemptionReasonCode: String? = null
        protected set

    @Column(name = "exemption_reason_text", columnDefinition = "TEXT")
    var exemptionReasonText: String? = null
        protected set

    @Column(name = "legal_basis", columnDefinition = "TEXT")
    var legalBasis: String? = null
        protected set

    @Column(name = "is_active")
    override var isActive: Boolean = true
        protected set

    /** `false` = historic or foreign rate; valid for legacy invoices, not for new line items. */
    @Column(name = "is_selectable")
    override var isSelectable: Boolean = true
        protected set

    var createdAt: OffsetDateTime? = null
        protected set

    override fun stampCreationIfUnset(now: OffsetDateTime) {
        if (createdAt == null) createdAt = now
    }

    @Version
    var version: Long? = 0
        protected set

    /** Replaces every editable field at once; id, creation stamp and revision stay the rule's own. */
    fun redefine(def: TaxRateDefinition) {
        name = def.name
        rate = def.rate
        country = def.country
        validFrom = def.validFrom
        validTo = def.validTo
        isHotelRate = def.isHotelRate
        isDefault = def.isDefault
        isReference = def.isReference
        code = def.code
        category = def.category
        exemptionReasonCode = def.exemptionReasonCode
        exemptionReasonText = def.exemptionReasonText
        legalBasis = def.legalBasis
        isActive = def.isActive
        isSelectable = def.isSelectable
    }

    private fun keepStoredIdentity(id: Long?, createdAt: OffsetDateTime?, version: Long?) {
        this.id = id
        this.createdAt = createdAt
        this.version = version
    }

    /** Gives up the default flag when another rule becomes the default. */
    fun clearDefault() {
        isDefault = false
    }

    fun validate() {
        if (name.isBlank()) throw InvalidParameterException("Steuersatzname darf nicht leer sein.")
        // Rate's 0..100 range now throws at TaxRatePercent construction; the former
        // deferred range check here was dead code.
        if (validFrom != null && validTo != null && validFrom!!.isAfter(validTo!!))
            throw InvalidParameterException("Gültigkeitsbeginn darf nicht nach Gültigkeitsende liegen.")
        if (isReference && !isEligibleAsReference) {
            throw InvalidParameterException(
                "Ein Referenzsteuersatz muss aktiv, auswählbar, positiv, kein Hotelsatz und der Kategorie S zugeordnet sein.",
            )
        }
        validateRule()
    }

    /**
     * Domain-side enforcement mirrors the tax-rule database constraints for defense in depth.
     * Every rule must carry a category.
     */
    private fun validateRule() {
        val cat = category
        validateCodeAndCategory(cat)
        validateRateAgainstCategory(cat)
        validateExemptionReason(cat)
    }

    // validateRule()'s six checks are split across three helpers so each stays under detekt's
    // ThrowsCount threshold on its own.
    private fun validateCodeAndCategory(cat: String) {
        val c = code
        if (c != null && c.isBlank())
            throw InvalidParameterException("Regel-Code darf nicht leer sein, wenn gesetzt.")
        if (cat !in setOf("S", "AE", "E", "O"))
            throw InvalidParameterException("Steuerkategorie ist erforderlich und muss S, AE, E oder O sein.")
    }

    private fun validateRateAgainstCategory(cat: String) {
        if (cat in setOf("AE", "E", "O") && rate.value.compareTo(BigDecimal.ZERO) != 0)
            throw InvalidParameterException("Kategorie $cat verlangt einen Steuersatz von 0 %.")
        // The inverse direction: "S" (Regelsatz) is by definition never a zero rate;
        // a genuine 0%-Regelsatz must instead be one of the zero-rate categories above.
        if (cat == "S" && rate.value.compareTo(BigDecimal.ZERO) == 0)
            throw InvalidParameterException("Kategorie S (Regelsatz) darf keinen Steuersatz von 0 % haben.")
    }

    private fun validateExemptionReason(cat: String) {
        if (cat == "AE" && exemptionReasonCode != "VATEX-EU-AE")
            throw InvalidParameterException("Kategorie AE verlangt den Befreiungsgrund VATEX-EU-AE.")
        if (cat in setOf("E", "O") && exemptionReasonText.isNullOrBlank())
            throw InvalidParameterException("Kategorie $cat verlangt einen Befreiungsgrund-Text.")
    }

    /**
     * Whether this rule's validity window overlaps [other]'s, for rules sharing the
     * same [code]. Rules without a code, or with different codes, can never overlap in this
     * sense (a `null` code covers legacy rows deliberately left unclassified —
     * they were never meant to be a single resolvable "rule" with a validity timeline).
     * `null` bounds are open-ended (matches [isApplicableFor]'s own MIN/MAX-less comparison).
     */
    fun overlapsValidityOf(other: TaxRate): Boolean {
        val c = code ?: return false
        if (c != other.code) return false
        val aFrom = validFrom ?: LocalDate.MIN
        val aTo = validTo ?: LocalDate.MAX
        val bFrom = other.validFrom ?: LocalDate.MIN
        val bTo = other.validTo ?: LocalDate.MAX
        return !aTo.isBefore(bFrom) && !bTo.isBefore(aFrom)
    }

    companion object {
        fun create(def: TaxRateDefinition): TaxRate = TaxRate().also { it.redefine(def) }

        /**
         * Rebuilds a detached copy that keeps the stored identity, creation stamp and revision.
         * Needed because the cached rule list is read back from outside the persistence context,
         * where the entity's own lifecycle cannot supply these values.
         */
        fun restore(def: TaxRateDefinition, id: Long?, createdAt: OffsetDateTime?, version: Long?): TaxRate =
            create(def).also { it.keepStoredIdentity(id, createdAt, version) }

        /**
         * Rejects a candidate rule whose validity window overlaps an existing rule of
         * the same [code]. A pure domain check over an explicitly supplied list (not a query)
         * so it stays testable without a repository; the caller supplies the existing rows for
         * the candidate's code.
         */
        fun assertNoOverlappingValidity(existing: List<TaxRate>, candidate: TaxRate) {
            val conflict = existing.firstOrNull { it !== candidate && candidate.overlapsValidityOf(it) }
            if (conflict != null) {
                throw InvalidParameterException(
                    "Regel '${candidate.code}' überlappt zeitlich mit einer bestehenden Regel gleichen Codes."
                )
            }
        }
    }
}
