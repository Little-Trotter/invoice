package de.littletrotter.fixture

import de.littletrotter.domain.masterdata.SellerVatRegistration
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.value.VatId
import de.littletrotter.shared.country.Country

/**
 * Property-style access to the state of a [SellerVatRegistration].
 *
 * Owner, country and identity are assigned by `SellerVatRegistration.of` and persistence; a registration
 * without them is a state only hydration produces.
 */
class SellerVatRegistrationEditor internal constructor(val entity: SellerVatRegistration) {
    var vatId: VatId?
        get() = entity.vatId
        set(value) { entity.changeVatId(value) }
    var id: Long?
        get() = entity.id
        set(value) { PersistenceEmulation.assignId(entity, value) }
    var senderProfile: SenderProfile?
        get() = entity.senderProfile
        set(value) { PersistenceEmulation.hydrate(entity, "senderProfile", value) }
    var country: Country
        get() = entity.country
        set(value) { PersistenceEmulation.hydrate(entity, "country", value) }
    var version: Long?
        get() = entity.version
        set(value) { PersistenceEmulation.assignVersion(entity, value) }
}

/** Edits [SellerVatRegistration] in place through its domain operations. */
fun SellerVatRegistration.edit(block: SellerVatRegistrationEditor.() -> Unit): SellerVatRegistration = also { SellerVatRegistrationEditor(it).block() }

/**
 * A blank VAT registration; `SellerVatRegistration.of` validates, so incomplete registrations can only be
 * built blank.
 */
fun aSellerVatRegistration(block: SellerVatRegistrationEditor.() -> Unit = {}): SellerVatRegistration = SellerVatRegistration().edit(block)
