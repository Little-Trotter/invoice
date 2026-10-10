package de.littletrotter.domain.reference

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.CountryAttributeConverter
import de.littletrotter.domain.shared.CreationStampListener
import de.littletrotter.domain.shared.StampedOnCreation
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.Money
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
import java.math.RoundingMode
import java.time.LocalDate
import java.time.OffsetDateTime

@Entity
@Table(name = "tax_rate")
@EntityListeners(CreationStampListener::class)
class TaxRate : TaxRateTerms, StampedOnCreation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @field:Size(max = 255)
    var name: String = ""

    @Column(precision = 5, scale = 2)
    override var rate: TaxRatePercent = TaxRatePercent(BigDecimal.ZERO)

    @jakarta.persistence.Convert(converter = CountryAttributeConverter::class)
    @Column(name = "country")
    override var country: Country = Country.DE
    override var validFrom: LocalDate? = null
    override var validTo: LocalDate? = null
    @Column(name = "is_hotel_rate")
    override var isHotelRate: Boolean = false
    var isDefault: Boolean = false
    @Column(name = "is_reference")
    override var isReference: Boolean = false

    // ── Rule instead of a plain percentage ───────────────────────────────────
    // Nullable on the entity despite the DB NOT NULL (code) resp. DB default (isActive/
    // isSelectable): instances that need to be validated before saving (i.e. before a default
    // value is applied) must be able to represent an empty/blank state — validate() checks
    // exactly that.

    /** Stable, unique rule code (e.g. `DE_STANDARD_19`) — display/export reference this, not
     *  [name]/[rate]. */
    @field:Size(max = 64)
    override var code: String? = null

    /** UNTDID 5305 / EN-16931 Duty-and-Tax-Category-Code: `S`, `AE`, `E`, `O`. `null` = legacy. */
    @field:Size(max = 4)
    override var category: String? = null

    @field:Size(max = 32)
    @Column(name = "exemption_reason_code")
    var exemptionReasonCode: String? = null

    @Column(name = "exemption_reason_text", columnDefinition = "TEXT")
    var exemptionReasonText: String? = null

    @Column(name = "legal_basis", columnDefinition = "TEXT")
    var legalBasis: String? = null

    @Column(name = "is_active")
    override var isActive: Boolean = true

    /** `false` = historic or foreign rate; valid for legacy invoices, not for new line items. */
    @Column(name = "is_selectable")
    override var isSelectable: Boolean = true

    var createdAt: OffsetDateTime? = null

    override fun stampCreationIfUnset(now: OffsetDateTime) {
        if (createdAt == null) createdAt = now
    }

    @Version
    var version: Long? = 0

    /** A factor (rate/100), not a monetary amount — stays BigDecimal, feeds into Money.percentage()-style math elsewhere. */
    fun rateFraction(): BigDecimal = rate.value.divide(BigDecimal("100"), RATE_FRACTION_SCALE, RoundingMode.HALF_UP)

    /**
     * Money in, money out — computed via [Money.percentage] instead of a hand-rolled
     * multiply/divide chain.
     */
    fun calculateVatFor(netAmount: Money): Money {
        if (netAmount.isNegative()) throw InvalidParameterException("Nettobetrag darf nicht negativ sein.")
        return netAmount.percentage(rate.value)
    }

    fun calculateGrossFor(netAmount: Money): Money {
        if (netAmount.isNegative()) throw InvalidParameterException("Nettobetrag darf nicht negativ sein.")
        return netAmount + calculateVatFor(netAmount)
    }

    fun calculateNetFor(grossAmount: Money): Money {
        if (grossAmount.isNegative()) throw InvalidParameterException("Bruttobetrag darf nicht negativ sein.")
        val divisor = BigDecimal.ONE.add(rateFraction())
        return grossAmount / divisor
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
     * Exception: "selectable requires category" stays database-only so callers that build a
     * TaxRate without the rule fields keep working.
     */
    private fun validateRule() {
        val cat = category
        validateCodeAndCategory(cat)
        validateRateAgainstCategory(cat)
        validateExemptionReason(cat)
    }

    // validateRule()'s six checks are split across three helpers so each stays under detekt's
    // ThrowsCount threshold on its own.
    private fun validateCodeAndCategory(cat: String?) {
        val c = code
        if (c != null && c.isBlank())
            throw InvalidParameterException("Regel-Code darf nicht leer sein, wenn gesetzt.")
        if (cat != null && cat !in setOf("S", "AE", "E", "O"))
            throw InvalidParameterException("Steuerkategorie muss S, AE, E oder O sein (oder unklassifiziert bleiben).")
    }

    private fun validateRateAgainstCategory(cat: String?) {
        if (cat in setOf("AE", "E", "O") && rate.value.compareTo(BigDecimal.ZERO) != 0)
            throw InvalidParameterException("Kategorie $cat verlangt einen Steuersatz von 0 %.")
        // The inverse direction: "S" (Regelsatz) is by definition never a zero rate;
        // a genuine 0%-Regelsatz would be indistinguishable from an unclassified legacy row and
        // must instead be one of the zero-rate categories above.
        if (cat == "S" && rate.value.compareTo(BigDecimal.ZERO) == 0)
            throw InvalidParameterException("Kategorie S (Regelsatz) darf keinen Steuersatz von 0 % haben.")
    }

    private fun validateExemptionReason(cat: String?) {
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
        /** Decimal places kept by [rateFraction]'s rate/100 division. */
        private const val RATE_FRACTION_SCALE = 6

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
