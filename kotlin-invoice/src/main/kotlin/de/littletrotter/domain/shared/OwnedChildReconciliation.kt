package de.littletrotter.domain.shared

import de.littletrotter.domain.exception.InvalidParameterException

/**
 * Reconciles owned entity rows by their domain key while retaining managed entity identities.
 * Aggregates compose this function; it does not own transactions or persistence operations.
 *
 * All validation and creation happens before the current collection changes — see
 * [ReconciliationStrategy] for [strategy]'s own contract. The proposed list is snapshotted so
 * callers may submit the current read-only collection.
 */
fun <Child, Key> reconcileOwnedChildren(
    current: MutableList<Child>,
    proposed: List<Child>,
    strategy: ReconciliationStrategy<Child, Key>,
) {
    val key = strategy.key
    val validate = strategy.validate
    val create = strategy.create
    val update = strategy.update
    val candidates = proposed.toList()
    validate(candidates)
    val candidateKeys = candidates.map(key)
    if (candidateKeys.toSet().size != candidateKeys.size) {
        throw InvalidParameterException("Ein Eintrag ist mehrfach angegeben.")
    }
    val existingByKey = current.associateBy(key)
    val additions = candidates.filter { key(it) !in existingByKey }.map(create)
    val retainedKeys = candidateKeys.toSet()

    current.removeIf { key(it) !in retainedKeys }
    candidates.forEach { candidate ->
        existingByKey[key(candidate)]?.let { retained -> update(retained, candidate) }
    }
    current.addAll(additions)
}
