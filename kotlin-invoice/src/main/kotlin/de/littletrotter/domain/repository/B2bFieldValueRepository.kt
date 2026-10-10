package de.littletrotter.domain.repository

import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.reference.B2bFieldValue
import de.littletrotter.shared.b2b.B2bTerm

/** Plumbing only — the actual writers/readers of owner values live elsewhere. */
interface B2bFieldValueRepository {
    fun findByOwner(ownerType: B2bFieldOwnerType, ownerId: Long): List<B2bFieldValue>
    fun findByOwnerAndBtCode(ownerType: B2bFieldOwnerType, ownerId: Long, term: B2bTerm): B2bFieldValue?

    /** Batched sibling of [findByOwner] for a page of owners at once (e.g. the positions grid's
     *  rows) — one query instead of one per owner. Empty [ownerIds] short-circuits to an empty
     *  list; an `IN ()` with no elements is invalid SQL. */
    fun findByOwners(ownerType: B2bFieldOwnerType, ownerIds: List<Long>): List<B2bFieldValue>

    fun save(value: B2bFieldValue): B2bFieldValue
    fun delete(id: Long)
}
