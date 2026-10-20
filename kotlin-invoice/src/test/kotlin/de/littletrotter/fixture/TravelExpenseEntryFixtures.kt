package de.littletrotter.fixture

import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.reference.PerDiemRate
import de.littletrotter.domain.reference.TravelExpenseEntry
import de.littletrotter.shared.Money
import de.littletrotter.shared.country.Country
import java.time.LocalDate

/**
 * Test-only write access to a [TravelExpenseEntry] whose setters are closed to production code. Tests arrange
 * rows as the database would hand them over, without going through the intent methods; each write
 * applies at once through the persistence field.
 */
fun TravelExpenseEntry.edit(block: TravelExpenseEntryEditor.() -> Unit): TravelExpenseEntry = also { TravelExpenseEntryEditor(it).block() }

/** Property-style access to every persisted field of a [TravelExpenseEntry]. */
class TravelExpenseEntryEditor internal constructor(internal val entity: TravelExpenseEntry) {
    var id: Long?
        get() = entity.id
        set(value) { PersistenceEmulation.assign(entity, "id", value) }

    var shortName: String?
        get() = entity.shortName
        set(value) { PersistenceEmulation.assign(entity, "shortName", value) }

    var invoiceItem: InvoiceItem?
        get() = entity.invoiceItem
        set(value) { PersistenceEmulation.assign(entity, "invoiceItem", value) }

    var country: Country
        get() = entity.country
        set(value) { PersistenceEmulation.assign(entity, "country", value) }

    var description: String
        get() = entity.description
        set(value) { PersistenceEmulation.assign(entity, "description", value) }

    var participant: String
        get() = entity.participant
        set(value) { PersistenceEmulation.assign(entity, "participant", value) }

    var travelDate: LocalDate?
        get() = entity.travelDate
        set(value) { PersistenceEmulation.assign(entity, "travelDate", value) }

    var isArrivalDeparture: Boolean
        get() = entity.isArrivalDeparture
        set(value) { PersistenceEmulation.assign(entity, "isArrivalDeparture", value) }

    var breakfastDeduction: Boolean
        get() = entity.breakfastDeduction
        set(value) { PersistenceEmulation.assign(entity, "breakfastDeduction", value) }

    var lunchDeduction: Boolean
        get() = entity.lunchDeduction
        set(value) { PersistenceEmulation.assign(entity, "lunchDeduction", value) }

    var dinnerDeduction: Boolean
        get() = entity.dinnerDeduction
        set(value) { PersistenceEmulation.assign(entity, "dinnerDeduction", value) }

    var fullDayRateSnapshot: Money
        get() = entity.fullDayRateSnapshot
        set(value) { PersistenceEmulation.assign(entity, "fullDayRateSnapshot", value) }

    var arrivalDepartureRateSnapshot: Money
        get() = entity.arrivalDepartureRateSnapshot
        set(value) { PersistenceEmulation.assign(entity, "arrivalDepartureRateSnapshot", value) }

    var perDiemRate: PerDiemRate?
        get() = entity.perDiemRate
        set(value) { PersistenceEmulation.assign(entity, "perDiemRate", value) }

    var calculatedAmount: Money
        get() = entity.calculatedAmount
        set(value) { PersistenceEmulation.assign(entity, "calculatedAmount", value) }

    var employerContribution: Money
        get() = entity.employerContribution
        set(value) { PersistenceEmulation.assign(entity, "employerContribution", value) }

    var version: Long?
        get() = entity.version
        set(value) { PersistenceEmulation.assign(entity, "version", value) }
}
