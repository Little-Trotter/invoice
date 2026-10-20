package de.littletrotter.fixture

import de.littletrotter.domain.compliance.ValidationFinding
import java.time.OffsetDateTime

/**
 * Test-only write access to a [ValidationFinding] whose setters are closed to production code. Tests arrange
 * rows as the database would hand them over, without going through the intent methods; each write
 * applies at once through the persistence field.
 */
fun ValidationFinding.edit(block: ValidationFindingEditor.() -> Unit): ValidationFinding = also { ValidationFindingEditor(it).block() }

/** Property-style access to every persisted field of a [ValidationFinding]. */
class ValidationFindingEditor internal constructor(internal val entity: ValidationFinding) {
    var id: Long?
        get() = entity.id
        set(value) { PersistenceEmulation.assign(entity, "id", value) }

    var generationJobId: Long
        get() = entity.generationJobId
        set(value) { PersistenceEmulation.assign(entity, "generationJobId", value) }

    var invoiceId: Long
        get() = entity.invoiceId
        set(value) { PersistenceEmulation.assign(entity, "invoiceId", value) }

    var stage: String?
        get() = entity.stage
        set(value) { PersistenceEmulation.assign(entity, "stage", value) }

    var validator: String?
        get() = entity.validator
        set(value) { PersistenceEmulation.assign(entity, "validator", value) }

    var ruleId: String?
        get() = entity.ruleId
        set(value) { PersistenceEmulation.assign(entity, "ruleId", value) }

    var severity: String
        get() = entity.severity
        set(value) { PersistenceEmulation.assign(entity, "severity", value) }

    var location: String?
        get() = entity.location
        set(value) { PersistenceEmulation.assign(entity, "location", value) }

    var message: String
        get() = entity.message
        set(value) { PersistenceEmulation.assign(entity, "message", value) }

    var validatorVersion: String?
        get() = entity.validatorVersion
        set(value) { PersistenceEmulation.assign(entity, "validatorVersion", value) }

    var createdAt: OffsetDateTime?
        get() = entity.createdAt
        set(value) { PersistenceEmulation.assign(entity, "createdAt", value) }
}
