package de.littletrotter.domain.repository

import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.reference.B2bProfileField
import de.littletrotter.domain.shared.SortDirection

interface B2bProfileRepository {
    fun findAll(
        pageIndex: Int = 0,
        pageSize: Int = 60,
        filterText: String? = null,
        sortDir: SortDirection = SortDirection.ASC,
    ): List<B2bProfile>
    fun count(filterText: String? = null): Long
    /** Count of profiles with `active = false`, same [filterText] semantics as
     *  [count]; the header counter's "N inaktiv" half. */
    fun countInactive(filterText: String? = null): Long
    fun findById(id: Long): B2bProfile?
    fun findAllActive(): List<B2bProfile>
    fun findByName(name: String): B2bProfile?
    fun save(profile: B2bProfile): B2bProfile

    /** Like [save], but with an immediate flush so the ids of newly defined fields are assigned
     *  on return. The default delegates to [save] for test doubles without a database. */
    fun saveAndFlush(profile: B2bProfile): B2bProfile = save(profile)

    /** The field [fieldId] without its profile's other fields, for edit dialogs that start from
     *  a field id; changes go back through [B2bProfile.defineField]. */
    fun findFieldById(fieldId: Long): B2bProfileField?

    /** The profile that owns the field [fieldId]. */
    fun findByFieldId(fieldId: Long): B2bProfile?

    /** The fields of every profile in [profileIds] in ONE query, ordered by position and term,
     *  keyed by profile id; a profile without fields has no entry. */
    fun findFieldsByProfileIds(profileIds: Collection<Long>): Map<Long, List<B2bProfileField>>
    fun delete(id: Long)
}
