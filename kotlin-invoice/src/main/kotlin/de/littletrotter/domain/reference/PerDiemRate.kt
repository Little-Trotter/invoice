package de.littletrotter.domain.reference

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.CountryAttributeConverter
import de.littletrotter.domain.shared.CreationStampListener
import de.littletrotter.domain.shared.MoneyConverter
import de.littletrotter.domain.shared.NamedEntity
import de.littletrotter.domain.shared.StampedOnCreation
import de.littletrotter.shared.Money
import de.littletrotter.shared.PerDiemMealDeductions
import jakarta.persistence.Column
import jakarta.persistence.Convert
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

@Entity
@Table(name = "per_diem_rate")
@EntityListeners(CreationStampListener::class)
class PerDiemRate : NamedEntity, ReferenceRateValidity, StampedOnCreation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Convert(converter = CountryAttributeConverter::class)
    @Column(name = "country")
    override var country: Country? = null

    @field:Size(max = 255)
    override var region: String? = null

    @Column(precision = 10, scale = 2)
    @Convert(converter = MoneyConverter::class)
    var fullDayRate: Money = Money.ZERO

    @Column(precision = 10, scale = 2)
    @Convert(converter = MoneyConverter::class)
    var arrivalDepartureRate: Money = Money.ZERO

    var inlandRules: Boolean = false
    var isDefault: Boolean = false
    override var validFrom: LocalDate? = null
    override var validTo: LocalDate? = null

    /** FALSE for a legacy row superseded by a sourced, year-dated BMF row; kept for
     *  historical invoices' [de.littletrotter.domain.reference.TravelExpenseEntry] references,
     *  no longer resolved for new positions (see [isApplicableFor]). */
    @Column(name = "is_active")
    override var isActive: Boolean = true

    /** The BMF-Schreiben (or, for the German Inland row, the EStG paragraph) this row's
     *  figures come from. Required for every active row (`chk_per_diem_rate_active_needs_source`). */
    var source: String? = null

    var createdAt: OffsetDateTime? = null

    override fun stampCreationIfUnset(now: OffsetDateTime) {
        if (createdAt == null) createdAt = now
    }

    @Version
    var version: Long? = 0

    private val figures: PerDiemRateFigures
        get() = PerDiemRateFigures(fullDayRate, arrivalDepartureRate, inlandRules)

    /**
     * Signature uses Money for monetary amounts — [calculateClaimableAmount] is called from
     * [de.littletrotter.application.reference.PerDiemService] and directly from a UI
     * dialog, both of which now pass Money. Internally routed through
     * [Money] throughout.
     */
    fun calculateDeductionWithContribution(
        isArrivalDeparture: Boolean,
        deductions: PerDiemMealDeductions,
        employerContribution: Money,
    ): BigDecimal = perDiemDeductionWithContribution(figures, isArrivalDeparture, deductions, employerContribution)

    fun calculateClaimableAmount(
        isArrivalDeparture: Boolean,
        deductions: PerDiemMealDeductions,
        employerContribution: Money = Money.ZERO,
    ): BigDecimal = perDiemClaimableAmount(figures, isArrivalDeparture, deductions, employerContribution).amount

    fun validate() {
        if (country == null) throw InvalidParameterException("Land darf nicht leer sein.")
        if (fullDayRate.isNegative()) throw InvalidParameterException("Pauschale kompletter Tag darf nicht negativ sein.")
        if (arrivalDepartureRate.isNegative()) throw InvalidParameterException("An-/Abreisepauschale darf nicht negativ sein.")
        if (validFrom != null && validTo != null && validFrom!!.isAfter(validTo!!))
            throw InvalidParameterException("Gültigkeitsbeginn darf nicht nach Gültigkeitsende liegen.")
    }
}
