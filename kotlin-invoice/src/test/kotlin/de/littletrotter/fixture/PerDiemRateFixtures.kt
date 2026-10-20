package de.littletrotter.fixture

import de.littletrotter.domain.reference.PerDiemRate
import de.littletrotter.shared.Money
import de.littletrotter.shared.country.Country
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Test-only write access to a [PerDiemRate] whose setters are closed to production code. Tests arrange
 * rows as the database would hand them over, without going through the intent methods; each write
 * applies at once through the persistence field.
 */
fun PerDiemRate.edit(block: PerDiemRateEditor.() -> Unit): PerDiemRate = also { PerDiemRateEditor(it).block() }

/** Property-style access to every persisted field of a [PerDiemRate]. */
class PerDiemRateEditor internal constructor(internal val entity: PerDiemRate) {
    var id: Long?
        get() = entity.id
        set(value) { PersistenceEmulation.assign(entity, "id", value) }

    var country: Country?
        get() = entity.country
        set(value) { PersistenceEmulation.assign(entity, "country", value) }

    var region: String?
        get() = entity.region
        set(value) { PersistenceEmulation.assign(entity, "region", value) }

    var fullDayRate: Money
        get() = entity.fullDayRate
        set(value) { PersistenceEmulation.assign(entity, "fullDayRate", value) }

    var arrivalDepartureRate: Money
        get() = entity.arrivalDepartureRate
        set(value) { PersistenceEmulation.assign(entity, "arrivalDepartureRate", value) }

    var inlandRules: Boolean
        get() = entity.inlandRules
        set(value) { PersistenceEmulation.assign(entity, "inlandRules", value) }

    var isDefault: Boolean
        get() = entity.isDefault
        set(value) { PersistenceEmulation.assign(entity, "isDefault", value) }

    var validFrom: LocalDate?
        get() = entity.validFrom
        set(value) { PersistenceEmulation.assign(entity, "validFrom", value) }

    var validTo: LocalDate?
        get() = entity.validTo
        set(value) { PersistenceEmulation.assign(entity, "validTo", value) }

    var isActive: Boolean
        get() = entity.isActive
        set(value) { PersistenceEmulation.assign(entity, "isActive", value) }

    var source: String?
        get() = entity.source
        set(value) { PersistenceEmulation.assign(entity, "source", value) }

    var createdAt: OffsetDateTime?
        get() = entity.createdAt
        set(value) { PersistenceEmulation.assign(entity, "createdAt", value) }

    var version: Long?
        get() = entity.version
        set(value) { PersistenceEmulation.assign(entity, "version", value) }
}
