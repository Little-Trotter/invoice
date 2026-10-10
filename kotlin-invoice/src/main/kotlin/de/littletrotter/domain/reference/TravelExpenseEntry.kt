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

@Entity
@Table(name = "travel_expense_entry")
class TravelExpenseEntry : NamedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @field:Size(max = 255)
    @Column(name = "short_name")
    var shortName: String? = null

    @ManyToOne(fetch = FetchType.LAZY)
    var invoiceItem: InvoiceItem? = null

    @Convert(converter = CountryAttributeConverter::class)
    @Column(name = "country")
    var country: Country = Country.DE

    /** The whole per-diem block's own mandatory description (e.g. "Team-Reise Berlin"),
     *  denormalized onto every row of the block — the same pattern [employerContribution]/
     *  [perDiemRate] already use for a value entered once per block but stored per row. */
    @field:Size(max = 255)
    var description: String = ""

    /** The traveler this row's per-diem entitlement belongs to (renamed from the previous
     *  [description], which meant this before the block-level [description] was introduced). */
    @field:Size(max = 255)
    var participant: String = ""
    var travelDate: LocalDate? = null
    var isArrivalDeparture: Boolean = false
    var breakfastDeduction: Boolean = false
    var lunchDeduction: Boolean = false
    var dinnerDeduction: Boolean = false

    @Column(precision = 10, scale = 2)
    @Convert(converter = MoneyConverter::class)
    var fullDayRateSnapshot: Money = Money.ZERO

    @Column(precision = 10, scale = 2)
    @Convert(converter = MoneyConverter::class)
    var arrivalDepartureRateSnapshot: Money = Money.ZERO

    @ManyToOne(fetch = FetchType.LAZY)
    var perDiemRate: PerDiemRate? = null

    @Column(precision = 10, scale = 2)
    @Convert(converter = MoneyConverter::class)
    var calculatedAmount: Money = Money.ZERO

    @Column(precision = 10, scale = 2)
    @Convert(converter = MoneyConverter::class)
    var employerContribution: Money = Money.ZERO

    @Version
    var version: Long? = 0

    fun validate() {
        if (description.isBlank()) throw InvoiceValidationException(InvoiceValidationError.TRAVEL_DESCRIPTION_BLANK, message = "Beschreibung darf nicht fehlen.")
        if (participant.isBlank()) throw InvoiceValidationException(InvoiceValidationError.TRAVEL_PARTICIPANT_BLANK, message = "Teilnehmer darf nicht fehlen.")
        if (travelDate == null) throw InvoiceValidationException(InvoiceValidationError.TRAVEL_DATE_MISSING, message = "Reisedatum darf nicht fehlen.")
        if (fullDayRateSnapshot.isNegative()) throw InvoiceValidationException(InvoiceValidationError.TRAVEL_FULL_DAY_RATE_NEGATIVE, message = "Pauschale kompletter Tag darf nicht negativ sein.")
        if (arrivalDepartureRateSnapshot.isNegative()) throw InvoiceValidationException(InvoiceValidationError.TRAVEL_ARRIVAL_DEPARTURE_RATE_NEGATIVE, message = "An-/Abreisepauschale darf nicht negativ sein.")
        if (calculatedAmount.isNegative()) throw InvoiceValidationException(InvoiceValidationError.TRAVEL_CALCULATED_AMOUNT_NEGATIVE, message = "Berechneter Betrag darf nicht negativ sein.")
        if (employerContribution.isNegative()) throw InvoiceValidationException(InvoiceValidationError.TRAVEL_EMPLOYER_CONTRIBUTION_NEGATIVE, message = "Arbeitgeberleistung darf nicht negativ sein.")
    }
}
