package de.littletrotter.fixture

import de.littletrotter.domain.system.DatabaseMaintenanceEvent
import de.littletrotter.domain.system.DatabaseMaintenanceStatus
import de.littletrotter.domain.system.DatabaseMaintenanceType
import java.time.OffsetDateTime

/**
 * Test-only write access to a [DatabaseMaintenanceEvent] whose setters are closed to production code. Tests arrange
 * rows as the database would hand them over, without going through the intent methods; each write
 * applies at once through the persistence field.
 */
fun DatabaseMaintenanceEvent.edit(block: DatabaseMaintenanceEventEditor.() -> Unit): DatabaseMaintenanceEvent = also { DatabaseMaintenanceEventEditor(it).block() }

/** Property-style access to every persisted field of a [DatabaseMaintenanceEvent]. */
class DatabaseMaintenanceEventEditor internal constructor(internal val entity: DatabaseMaintenanceEvent) {
    var id: Long?
        get() = entity.id
        set(value) { PersistenceEmulation.assign(entity, "id", value) }

    var createdAt: OffsetDateTime
        get() = entity.createdAt
        set(value) { PersistenceEmulation.assign(entity, "createdAt", value) }

    var type: DatabaseMaintenanceType
        get() = entity.type
        set(value) { PersistenceEmulation.assign(entity, "type", value) }

    var status: DatabaseMaintenanceStatus
        get() = entity.status
        set(value) { PersistenceEmulation.assign(entity, "status", value) }

    var details: String?
        get() = entity.details
        set(value) { PersistenceEmulation.assign(entity, "details", value) }
}
