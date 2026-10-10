package de.littletrotter.domain.repository

/**
 * Told by the persistence adapters about every master data write, so a publisher can follow it
 * without the adapters knowing who listens. Implementations ignore entities that are not master
 * data and must take part in the caller's transaction.
 */
interface MasterdataChangeListener {

    /** [entity] was persisted or merged. */
    fun saved(entity: Any)

    /** [entity] is about to be removed; called before the removal so its owners can still be read. */
    fun deleting(entity: Any)
}
