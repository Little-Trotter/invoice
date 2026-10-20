package de.littletrotter.fixture

import de.littletrotter.domain.message.SystemMessage
import de.littletrotter.domain.message.SystemMessageSeverity
import de.littletrotter.domain.message.SystemMessageSource
import java.time.OffsetDateTime

/**
 * Test-only write access to a [SystemMessage] whose setters are closed to production code. Tests arrange
 * rows as the database would hand them over, without going through the intent methods; each write
 * applies at once through the persistence field.
 */
fun SystemMessage.edit(block: SystemMessageEditor.() -> Unit): SystemMessage = also { SystemMessageEditor(it).block() }

/** Property-style access to every persisted field of a [SystemMessage]. */
class SystemMessageEditor internal constructor(internal val entity: SystemMessage) {
    var id: Long?
        get() = entity.id
        set(value) { PersistenceEmulation.assign(entity, "id", value) }

    var occurredAt: OffsetDateTime
        get() = entity.occurredAt
        set(value) { PersistenceEmulation.assign(entity, "occurredAt", value) }

    var severity: SystemMessageSeverity
        get() = entity.severity
        set(value) { PersistenceEmulation.assign(entity, "severity", value) }

    var source: SystemMessageSource
        get() = entity.source
        set(value) { PersistenceEmulation.assign(entity, "source", value) }

    var version: Long?
        get() = entity.version
        set(value) { PersistenceEmulation.assign(entity, "version", value) }
}
