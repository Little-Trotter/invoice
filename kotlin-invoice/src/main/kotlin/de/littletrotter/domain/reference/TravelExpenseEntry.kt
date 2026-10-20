package de.littletrotter.domain.reference

import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.CountryAttributeConverter
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.shared.Money
import de.littletrotter.domain.shared.MoneyConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.Version
import jakarta.validation.constraints.Size
import java.time.LocalDate
import de.littletrotter.domain.shared.NamedEntity

/**
 * Everything one per-diem day row records, in one object: the traveler and block label, the day
 * with its meal deductions, the rate figures frozen at creation time and the resulting amount.
 * Passing it as a whole keeps the row from being half-filled by field-wise assignment.
 */
data class TravelExpenseDefinition(
    val shortName: String?,
    val country: Country,
    val description: String,
    val participant: String,
    val travelDate: LocalDate?,
    val isArrivalDeparture: Boolean = false,
    val breakfastDeduction: Boolean = false,
    val lunchDeduction: Boolean = false,
    val dinnerDeduction: Boolean = false,
    val fullDayRateSnapshot: Money = Money.ZERO,
    val arrivalDepartureRateSnapshot: Money = Money.ZERO,
    val perDiemRate: PerDiemRate? = null,
    val calculatedAmount: Money = Money.ZERO,
    val employerContribution: Money = Money.ZERO,
)

@Entity
@Table(name = "travel_expense_entry")
class TravelExpenseEntry : NamedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:Size(max = 255)
    @Column(name = "short_name")
    var shortName: String? = null
        protected set

    @ManyToOne(fetch = FetchType.LAZY)
    var invoiceItem: InvoiceItem? = null
        protected set

    @Convert(converter = CountryAttributeConverter::class)
    @Column(name = "country")
    var country: Country = Country.DE
        protected set

    /** The whole per-diem block's own mandatory description (e.g. "Team-Reise Berlin"),
     *  denormalized onto every row of the block — the same pattern [employerContribution]/
     *  [perDiemRate] already use for a value entered once per block but stored per row. */
    @field:Size(max = 255)
    var description: String = ""
        protected set

    /** The traveler this row's per-diem entitlement belongs to (renamed from the previous
     *  [description], which meant this before the block-level [description] was introduced). */
    @field:Size(max = 255)
    var participant: String = ""
        protected set
    var travelDate: LocalDate? = null
        protected set
    var isArrivalDeparture: Boolean = false
        protected set
    var breakfastDeduction: Boolean = false
        protected set
    var lunchDeduction: Boolean = false
        protected set
    var dinnerDeduction: Boolean = false
        protected set

    @Column(precision = 10, scale = 2)
    @Convert(converter = MoneyConverter::class)
    var fullDayRateSnapshot: Money = Money.ZERO
        protected set

    @Column(precision = 10, scale = 2)
    @Convert(converter = MoneyConverter::class)
    var arrivalDepartureRateSnapshot: Money = Money.ZERO
        protected set

    @ManyToOne(fetch = FetchType.LAZY)
    var perDiemRate: PerDiemRate? = null
        protected set

    @Column(precision = 10, scale = 2)
    @Convert(converter = MoneyConverter::class)
    var calculatedAmount: Money = Money.ZERO
        protected set

    @Column(precision = 10, scale = 2)
    @Convert(converter = MoneyConverter::class)
    var employerContribution: Money = Money.ZERO
        protected set

    @Version
    var version: Long? = 0
        protected set

    /** Re-labels the row with its traveler and the block's mandatory description; the trip form
     *  collects both once per block, after the rate-derived figures were already computed. */
    fun relabel(participant: String, description: String) {
        this.participant = participant
        this.description = description
    }

    /** Puts the row under [item]; the owning side of the item's per-diem collection. */
    fun assignTo(item: InvoiceItem) {
        invoiceItem = item
    }

    /**
     * Deep copy of this entry for [owner], the credit note's new line item.
     *
     * The field list is spelled out on purpose: it is the audit surface of a legal document. A
     * field added to the entity must not start being copied silently, it must be a visible
     * decision in this method. Explicitness is the safety property here, not an oversight.
     */
    fun copyFor(owner: InvoiceItem): TravelExpenseEntry {
        val source = this
        return TravelExpenseEntry().apply {
            invoiceItem = owner
            shortName = source.shortName
            country = source.country
            description = source.description
            participant = source.participant
            travelDate = source.travelDate
            isArrivalDeparture = source.isArrivalDeparture
            breakfastDeduction = source.breakfastDeduction
            lunchDeduction = source.lunchDeduction
            dinnerDeduction = source.dinnerDeduction
            fullDayRateSnapshot = source.fullDayRateSnapshot
            arrivalDepartureRateSnapshot = source.arrivalDepartureRateSnapshot
            perDiemRate = source.perDiemRate
            calculatedAmount = source.calculatedAmount
            employerContribution = source.employerContribution
        }
    }

    private fun define(def: TravelExpenseDefinition) {
        shortName = def.shortName
        country = def.country
        description = def.description
        participant = def.participant
        travelDate = def.travelDate
        isArrivalDeparture = def.isArrivalDeparture
        breakfastDeduction = def.breakfastDeduction
        lunchDeduction = def.lunchDeduction
        dinnerDeduction = def.dinnerDeduction
        fullDayRateSnapshot = def.fullDayRateSnapshot
        arrivalDepartureRateSnapshot = def.arrivalDepartureRateSnapshot
        perDiemRate = def.perDiemRate
        calculatedAmount = def.calculatedAmount
        employerContribution = def.employerContribution
    }

    fun validate() {
        if (description.isBlank()) throw InvoiceValidationException(InvoiceValidationError.TRAVEL_DESCRIPTION_BLANK, message = "Beschreibung darf nicht fehlen.")
        if (participant.isBlank()) throw InvoiceValidationException(InvoiceValidationError.TRAVEL_PARTICIPANT_BLANK, message = "Teilnehmer darf nicht fehlen.")
        if (travelDate == null) throw InvoiceValidationException(InvoiceValidationError.TRAVEL_DATE_MISSING, message = "Reisedatum darf nicht fehlen.")
        if (fullDayRateSnapshot.isNegative()) throw InvoiceValidationException(InvoiceValidationError.TRAVEL_FULL_DAY_RATE_NEGATIVE, message = "Pauschale kompletter Tag darf nicht negativ sein.")
        if (arrivalDepartureRateSnapshot.isNegative()) throw InvoiceValidationException(InvoiceValidationError.TRAVEL_ARRIVAL_DEPARTURE_RATE_NEGATIVE, message = "An-/Abreisepauschale darf nicht negativ sein.")
        if (calculatedAmount.isNegative()) throw InvoiceValidationException(InvoiceValidationError.TRAVEL_CALCULATED_AMOUNT_NEGATIVE, message = "Berechneter Betrag darf nicht negativ sein.")
        if (employerContribution.isNegative()) throw InvoiceValidationException(InvoiceValidationError.TRAVEL_EMPLOYER_CONTRIBUTION_NEGATIVE, message = "Arbeitgeberleistung darf nicht negativ sein.")
    }

    companion object {
        fun create(def: TravelExpenseDefinition): TravelExpenseEntry = TravelExpenseEntry().also { it.define(def) }
    }
}
