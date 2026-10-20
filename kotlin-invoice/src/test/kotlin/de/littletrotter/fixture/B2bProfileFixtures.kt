package de.littletrotter.fixture

import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.reference.B2bProfileField
import de.littletrotter.domain.repository.B2bProfileRepository
import de.littletrotter.shared.country.Country
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

/**
 * Test-only write access to a [B2bProfile] whose setters are closed to production code. Tests arrange
 * rows as the database would hand them over, without going through the intent methods; each write
 * applies at once through the persistence field.
 */
fun B2bProfile.edit(block: B2bProfileEditor.() -> Unit): B2bProfile = also { B2bProfileEditor(it).block() }

/** Property-style access to every persisted field of a [B2bProfile]. */
class B2bProfileEditor internal constructor(internal val entity: B2bProfile) {
    var id: Long?
        get() = entity.id
        set(value) { PersistenceEmulation.assign(entity, "id", value) }

    var name: String
        get() = entity.name
        set(value) { PersistenceEmulation.assign(entity, "name", value) }

    var description: String?
        get() = entity.description
        set(value) { PersistenceEmulation.assign(entity, "description", value) }

    var active: Boolean
        get() = entity.active
        set(value) { PersistenceEmulation.assign(entity, "active", value) }

    var fields: List<B2bProfileField>
        get() = entity.fieldList
        set(value) { PersistenceEmulation.assign(entity, "fields", value.toMutableList()) }

    var countries: MutableSet<Country>
        get() = entity.countries
        set(value) { PersistenceEmulation.assign(entity, "countries", value) }

    var version: Long?
        get() = entity.version
        set(value) { PersistenceEmulation.assign(entity, "version", value) }
}

private val givenFieldsByRepository = java.util.WeakHashMap<B2bProfileRepository, MutableMap<Long, List<B2bProfileField>>>()

/**
 * Arranges what the profile repository answers for [profileId]: the batched field read and the
 * owning profile that holds the same [fields], as the database would hand them over. Several
 * calls on one repository add up, like several profiles in one table.
 */
fun B2bProfileRepository.givenFields(profileId: Long, fields: List<B2bProfileField>) {
    val registry = givenFieldsByRepository.getOrPut(this) { mutableMapOf() }
    registry[profileId] = fields
    val owner = B2bProfile().edit { id = profileId }
    fields.forEach { owner.defineField(it) }
    whenever(findById(profileId)).thenReturn(owner)
    whenever(findFieldsByProfileIds(any())).thenAnswer { invocation ->
        invocation.getArgument<Collection<Long>>(0)
            .mapNotNull { id -> registry[id]?.takeIf { it.isNotEmpty() }?.let { id to it } }
            .toMap()
    }
}
