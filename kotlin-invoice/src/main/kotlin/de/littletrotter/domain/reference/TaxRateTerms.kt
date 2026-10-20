package de.littletrotter.domain.reference

import de.littletrotter.shared.tax.TaxRuleCode
import de.littletrotter.shared.tax.VatCategory
import com.fasterxml.jackson.annotation.JsonIgnore
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.value.TaxRatePercent
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The facts of a tax rule that decide where it applies and what it may be used for: its rate,
 * country, validity window, category and flags. Both the stored [TaxRate] row and the read row the
 * item dialogs and the catalogue grid work with implement it, so "does this rule apply on that
 * day", "which category is it" and "may it auto-populate a country's rate" are answered by one
 * rule, never by a second copy of it.
 *
 * The derived members carry `@JsonIgnore`: computed getters would otherwise serialize and then
 * fail to deserialize as unknown properties.
 */
interface TaxRateTerms {
    val rate: TaxRatePercent
    val country: Country
    val validFrom: LocalDate?
    val validTo: LocalDate?
    val isHotelRate: Boolean
    val isReference: Boolean

    /** Stable, unique rule code (e.g. `DE_STANDARD_19`); display and export reference this. */
    val code: String?

    /** UNTDID 5305 duty-and-tax category code (`S`, `AE`, `E`, `O`, `Z`, `G`, `K`); every rule has one. */
    val category: String
    val isActive: Boolean

    /** `false` = historic or foreign rate; valid for existing invoices, not for new line items. */
    val isSelectable: Boolean

    /** Typed view of [category]; `category` is the persisted truth. */
    @get:JsonIgnore
    val categoryEnum: VatCategory get() = VatCategory.fromCode(category)

    /** Typed view of [code]; `code` is the persisted truth. */
    @get:JsonIgnore
    val ruleCode: TaxRuleCode? get() = code?.let(TaxRuleCodes::parse)

    /** Whether [date] lies within the validity window; an unset bound is open-ended. */
    fun isApplicableFor(date: LocalDate): Boolean {
        val startsBeforeOrAt = validFrom?.isAfter(date) != true
        val endsAfterOrAt = validTo?.isBefore(date) != true
        return startsBeforeOrAt && endsAfterOrAt
    }

    fun isApplicableFor(
        requestCountry: Country,
        date: LocalDate,
        requireHotelRate: Boolean? = null,
    ): Boolean {
        val countryMatches = country == requestCountry
        val hotelRateMatches = requireHotelRate == null || isHotelRate == requireHotelRate
        return countryMatches && hotelRateMatches && isApplicableFor(date)
    }

    /**
     * Whether this rule may serve as a country's reference rate: active, selectable, a positive
     * standard-rate (`S`) rule and no hotel rate. [TaxRate.validate] rejects a reference row
     * that fails this.
     */
    @get:JsonIgnore
    val isEligibleAsReference: Boolean
        get() = isActive && isSelectable && category == "S" && !isHotelRate && rate.value > BigDecimal.ZERO

    /**
     * Whether this row actually is a usable reference rate to auto-populate a country's tax rate
     * (article tax variants, seller-VAT suggestions): marked as reference and eligible as one.
     * Checked explicitly because candidate rows may not all be references, or may predate
     * validation.
     */
    @get:JsonIgnore
    val isUsableReferenceRate: Boolean
        get() = isReference && isEligibleAsReference
}
