package de.littletrotter.application.reference

import jakarta.persistence.OptimisticLockException

/**
 * How a catalogue save finds the row it writes into: [find] loads a stored row by id,
 * [versionOf] reads its optimistic-lock revision, [create] makes a fresh one.
 */
internal class CatalogueWrites<E : Any>(
    private val find: (Long) -> E?,
    private val versionOf: (E) -> Long?,
    private val create: () -> E,
) {
    /**
     * A fresh row when [id] is `null`, otherwise the stored row, which must still exist and still
     * carry the [version] the editor started from. A `null` [version] skips the revision check
     * for callers that carry none.
     */
    fun rowFor(id: Long?, version: Long?): E {
        if (id == null) return create()
        val stored = find(id) ?: throw OptimisticLockException("Der Eintrag wurde inzwischen gelöscht.")
        if (version != null && versionOf(stored) != version) {
            throw OptimisticLockException("Der Eintrag wurde inzwischen von jemand anderem geändert. Bitte neu laden.")
        }
        return stored
    }
}
