package de.littletrotter.fixture

import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.value.PostalCode
import de.littletrotter.shared.country.Country

/**
 * Property-style access to the stored state of an [Address]; street and city move through `relocate`.
 *
 * Owner, type, postal code and country are fixed at creation by the factories (which validate them); an
 * address in a state they refuse, and the identity, can only be hydrated.
 */
class AddressEditor internal constructor(val entity: Address) {
    var street: String
        get() = entity.street
        set(value) { entity.relocate(value, entity.postalCode.value, entity.city, entity.country) }
    var city: String
        get() = entity.city
        set(value) { entity.relocate(entity.street, entity.postalCode.value, value, entity.country) }
    var isDefault: Boolean
        get() = entity.isDefault
        set(value) { if (value) entity.markAsDefault() else entity.revokeDefault() }
    var id: Long?
        get() = entity.id
        set(value) { PersistenceEmulation.assignId(entity, value) }
    var recipient: Recipient?
        get() = entity.recipient
        set(value) { PersistenceEmulation.hydrate(entity, "recipient", value) }
    var senderProfile: SenderProfile?
        get() = entity.senderProfile
        set(value) { PersistenceEmulation.hydrate(entity, "senderProfile", value) }
    var postalCode: PostalCode
        get() = entity.postalCode
        set(value) { PersistenceEmulation.hydrate(entity, "postalCode", value) }
    var country: Country
        get() = entity.country
        set(value) { PersistenceEmulation.hydrate(entity, "country", value) }
    var version: Long?
        get() = entity.version
        set(value) { PersistenceEmulation.assignVersion(entity, value) }
}

/** Edits [Address] in place through its domain operations. */
fun Address.edit(block: AddressEditor.() -> Unit): Address = also { AddressEditor(it).block() }
