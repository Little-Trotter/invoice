package de.littletrotter.fixture

import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.country.Country
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Test-only write access to a [TaxRate] whose setters are closed to production code. Tests arrange
 * rows as the database would hand them over, without going through the intent methods; each write
 * applies at once through the persistence field. A rule always has a category, so an unclassified
 * row starts as standard rate (`S`) until the block sets another.
 */
fun TaxRate.edit(block: TaxRateEditor.() -> Unit): TaxRate = also {
    val editor = TaxRateEditor(it)
    if (it.category.isBlank()) editor.category = "S"
    editor.block()
}

/** Property-style access to every persisted field of a [TaxRate]. */
class TaxRateEditor internal constructor(internal val entity: TaxRate) {
    var id: Long?
        get() = entity.id
        set(value) { PersistenceEmulation.assign(entity, "id", value) }

    var name: String
        get() = entity.name
        set(value) { PersistenceEmulation.assign(entity, "name", value) }

    var rate: TaxRatePercent
        get() = entity.rate
        set(value) { PersistenceEmulation.assign(entity, "rate", value) }

    var country: Country
        get() = entity.country
        set(value) { PersistenceEmulation.assign(entity, "country", value) }

    var validFrom: LocalDate?
        get() = entity.validFrom
        set(value) { PersistenceEmulation.assign(entity, "validFrom", value) }

    var validTo: LocalDate?
        get() = entity.validTo
        set(value) { PersistenceEmulation.assign(entity, "validTo", value) }

    var isHotelRate: Boolean
        get() = entity.isHotelRate
        set(value) { PersistenceEmulation.assign(entity, "isHotelRate", value) }

    var isDefault: Boolean
        get() = entity.isDefault
        set(value) { PersistenceEmulation.assign(entity, "isDefault", value) }

    var isReference: Boolean
        get() = entity.isReference
        set(value) { PersistenceEmulation.assign(entity, "isReference", value) }

    var code: String?
        get() = entity.code
        set(value) { PersistenceEmulation.assign(entity, "code", value) }

    var category: String
        get() = entity.category
        set(value) { PersistenceEmulation.assign(entity, "category", value) }

    var exemptionReasonCode: String?
        get() = entity.exemptionReasonCode
        set(value) { PersistenceEmulation.assign(entity, "exemptionReasonCode", value) }

    var exemptionReasonText: String?
        get() = entity.exemptionReasonText
        set(value) { PersistenceEmulation.assign(entity, "exemptionReasonText", value) }

    var legalBasis: String?
        get() = entity.legalBasis
        set(value) { PersistenceEmulation.assign(entity, "legalBasis", value) }

    var isActive: Boolean
        get() = entity.isActive
        set(value) { PersistenceEmulation.assign(entity, "isActive", value) }

    var isSelectable: Boolean
        get() = entity.isSelectable
        set(value) { PersistenceEmulation.assign(entity, "isSelectable", value) }

    var createdAt: OffsetDateTime?
        get() = entity.createdAt
        set(value) { PersistenceEmulation.assign(entity, "createdAt", value) }

    var version: Long?
        get() = entity.version
        set(value) { PersistenceEmulation.assign(entity, "version", value) }
}
