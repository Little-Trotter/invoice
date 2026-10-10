package de.littletrotter.domain.repository

import de.littletrotter.domain.reference.B2bProfile
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
    fun delete(id: Long)
}
