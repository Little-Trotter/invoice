package de.littletrotter.fixture

import de.littletrotter.domain.reference.MileageRate
import de.littletrotter.domain.reference.VehicleType
import de.littletrotter.shared.country.Country
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Test-only write access to a [MileageRate] whose setters are closed to production code. Tests arrange
 * rows as the database would hand them over, without going through the intent methods; each write
 * applies at once through the persistence field.
 */
fun MileageRate.edit(block: MileageRateEditor.() -> Unit): MileageRate = also { MileageRateEditor(it).block() }

/** Property-style access to every persisted field of a [MileageRate]. */
class MileageRateEditor internal constructor(internal val entity: MileageRate) {
    var id: Long?
        get() = entity.id
        set(value) { PersistenceEmulation.assign(entity, "id", value) }

    var country: Country?
        get() = entity.country
        set(value) { PersistenceEmulation.assign(entity, "country", value) }

    var ratePerKm: BigDecimal
        get() = entity.ratePerKm
        set(value) { PersistenceEmulation.assign(entity, "ratePerKm", value) }

    var validFrom: LocalDate?
        get() = entity.validFrom
        set(value) { PersistenceEmulation.assign(entity, "validFrom", value) }

    var validTo: LocalDate?
        get() = entity.validTo
        set(value) { PersistenceEmulation.assign(entity, "validTo", value) }

    var vehicleType: VehicleType?
        get() = entity.vehicleType
        set(value) { PersistenceEmulation.assign(entity, "vehicleType", value) }

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
