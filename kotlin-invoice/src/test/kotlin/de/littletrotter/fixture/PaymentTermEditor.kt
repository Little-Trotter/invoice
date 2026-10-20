package de.littletrotter.fixture

import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.masterdata.PaymentTermDetails
import de.littletrotter.domain.reference.Language
import de.littletrotter.domain.reference.LocalizedTextGroup
import de.littletrotter.domain.value.DiscountPercent
import java.time.OffsetDateTime

/**
 * Property-style access to the editable state of a [PaymentTerm]; each write applies at once through its
 * intent method.
 *
 * Identity, lock version, the localized name group and the creation stamp have no domain write path outside
 * `setName`; they are hydrated the way a loaded row carries them.
 */
class PaymentTermEditor internal constructor(val entity: PaymentTerm) {
    var description: String?
        get() = entity.description
        set(value) { entity.update(details().copy(description = value)) }
    var dueInDays: Int
        get() = entity.dueInDays
        set(value) { entity.update(details().copy(dueInDays = value)) }
    var discountPercent: DiscountPercent
        get() = entity.discountPercent
        set(value) { entity.update(details().copy(discountPercent = value)) }
    var discountDays: Int?
        get() = entity.discountDays
        set(value) { entity.update(details().copy(discountDays = value)) }
    var isDefault: Boolean
        get() = entity.isDefault
        set(value) { entity.update(details().copy(isDefault = value)) }
    var isActive: Boolean
        get() = entity.isActive
        set(value) { entity.update(details().copy(isActive = value)) }
    var id: Long?
        get() = entity.id
        set(value) { PersistenceEmulation.assignId(entity, value) }
    var version: Long?
        get() = entity.version
        set(value) { PersistenceEmulation.assignVersion(entity, value) }
    var nameGroup: LocalizedTextGroup?
        get() = entity.nameGroup
        set(value) { PersistenceEmulation.hydrate(entity, "nameGroup", value) }
    var createdAt: OffsetDateTime?
        get() = entity.createdAt
        set(value) { PersistenceEmulation.hydrate(entity, "createdAt", value) }

    private fun details() = PaymentTermDetails(
        description = entity.description,
        dueInDays = entity.dueInDays,
        discountPercent = entity.discountPercent,
        discountDays = entity.discountDays,
        isDefault = entity.isDefault,
        isActive = entity.isActive,
    )

    fun changeName(language: Language, value: String?) = entity.changeName(language, value)

    fun update(details: PaymentTermDetails) = entity.update(details)
}

/** Edits [PaymentTerm] in place through its domain operations. */
fun PaymentTerm.edit(block: PaymentTermEditor.() -> Unit): PaymentTerm = also { PaymentTermEditor(it).block() }

/**
 * A payment term as the master data holds it, named in the fallback language. [name] stays unset
 * when blank, so a snapshot frozen from it carries the empty name an unnamed term resolves to.
 * [configure] edits the remaining state after the named values are applied.
 */
fun aPaymentTerm(
    name: String = "",
    dueInDays: Int = 0,
    discountPercent: DiscountPercent = DiscountPercent.ZERO,
    discountDays: Int? = null,
    configure: PaymentTermEditor.() -> Unit = {},
): PaymentTerm = PaymentTerm().edit {
    if (name.isNotBlank()) changeName(Language().edit { id = 1; code = "de"; isFallback = true }, name)
    this.dueInDays = dueInDays
    this.discountPercent = discountPercent
    this.discountDays = discountDays
    configure()
}
