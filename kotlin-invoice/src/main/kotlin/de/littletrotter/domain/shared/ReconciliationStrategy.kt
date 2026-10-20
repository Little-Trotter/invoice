package de.littletrotter.domain.shared

/**
 * How [reconcileOwnedChildren] reconciles one aggregate's owned children — as opposed to
 * [reconcileOwnedChildren]'s own `current`/`proposed` (*what* is being reconciled). Bundled so
 * that function takes one parameter for it instead of four (LongParameterList).
 *
 * [key] derives the domain key two rows are matched by. [validate] runs once, before any mutation
 * (over the full proposed list). [create] must return a fresh child owned by the receiving
 * aggregate, without copying persistence identity. [update] only assigns already validated
 * fields; it must not change the domain key or fail.
 */
data class ReconciliationStrategy<Child, Key>(
    val key: (Child) -> Key,
    val validate: (List<Child>) -> Unit,
    val create: (Child) -> Child,
    val update: (Child, Child) -> Unit,
)
