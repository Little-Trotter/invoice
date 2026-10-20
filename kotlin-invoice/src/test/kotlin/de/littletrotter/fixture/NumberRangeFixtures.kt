package de.littletrotter.fixture

import de.littletrotter.domain.reference.Continuity
import de.littletrotter.domain.reference.CounterScope
import de.littletrotter.domain.reference.NumberRange
import de.littletrotter.domain.reference.NumberRangeSubject
import de.littletrotter.domain.reference.PrefixDataField
import de.littletrotter.domain.reference.PrefixSource
import de.littletrotter.shared.numbering.DatePart
import de.littletrotter.shared.numbering.ResetCycle
import java.time.OffsetDateTime

/**
 * Test-only write access to a [NumberRange] whose setters are closed to production code. Tests arrange
 * rows as the database would hand them over, without going through the intent methods; each write
 * applies at once through the persistence field.
 */
fun NumberRange.edit(block: NumberRangeEditor.() -> Unit): NumberRange = also { NumberRangeEditor(it).block() }

/** Property-style access to every persisted field of a [NumberRange]. */
class NumberRangeEditor internal constructor(internal val entity: NumberRange) {
    var id: Long?
        get() = entity.id
        set(value) { PersistenceEmulation.assign(entity, "id", value) }

    var code: String
        get() = entity.code
        set(value) { PersistenceEmulation.assign(entity, "code", value) }

    var displayName: String
        get() = entity.displayName
        set(value) { PersistenceEmulation.assign(entity, "displayName", value) }

    var subject: NumberRangeSubject
        get() = entity.subject
        set(value) { PersistenceEmulation.assign(entity, "subject", value) }

    var startValue: Long
        get() = entity.startValue
        set(value) { PersistenceEmulation.assign(entity, "startValue", value) }

    var prefixSource: PrefixSource
        get() = entity.prefixSource
        set(value) { PersistenceEmulation.assign(entity, "prefixSource", value) }

    var fixedPrefix: String?
        get() = entity.fixedPrefix
        set(value) { PersistenceEmulation.assign(entity, "fixedPrefix", value) }

    var prefixDataField: PrefixDataField?
        get() = entity.prefixDataField
        set(value) { PersistenceEmulation.assign(entity, "prefixDataField", value) }

    var datePart: DatePart
        get() = entity.datePart
        set(value) { PersistenceEmulation.assign(entity, "datePart", value) }

    var counterWidth: Int
        get() = entity.counterWidth
        set(value) { PersistenceEmulation.assign(entity, "counterWidth", value) }

    var resetCycle: ResetCycle
        get() = entity.resetCycle
        set(value) { PersistenceEmulation.assign(entity, "resetCycle", value) }

    var scope: CounterScope
        get() = entity.scope
        set(value) { PersistenceEmulation.assign(entity, "scope", value) }

    var continuity: Continuity
        get() = entity.continuity
        set(value) { PersistenceEmulation.assign(entity, "continuity", value) }

    var isActive: Boolean
        get() = entity.isActive
        set(value) { PersistenceEmulation.assign(entity, "isActive", value) }

    var isDefault: Boolean
        get() = entity.isDefault
        set(value) { PersistenceEmulation.assign(entity, "isDefault", value) }

    var createdAt: OffsetDateTime?
        get() = entity.createdAt
        set(value) { PersistenceEmulation.assign(entity, "createdAt", value) }

    var version: Long?
        get() = entity.version
        set(value) { PersistenceEmulation.assign(entity, "version", value) }
}
