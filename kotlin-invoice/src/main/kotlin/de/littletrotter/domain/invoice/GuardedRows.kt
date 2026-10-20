package de.littletrotter.domain.invoice

/**
 * One collection owned by an invoice or a position. Reading is free; every change first runs the
 * owner's [guard], so a locked invoice rejects it. Handed out by the owner for its own rows only,
 * which keeps the mutable list itself private to the entity.
 */
class GuardedRows<T>(private val rows: MutableList<T>, private val guard: () -> Unit) {

    val items: List<T> get() = rows

    fun add(item: T) {
        guard()
        rows.add(item)
    }

    fun replaceAll(newItems: List<T>) {
        guard()
        rows.clear()
        rows.addAll(newItems)
    }

    fun removeIf(predicate: (T) -> Boolean): Boolean {
        guard()
        return rows.removeIf(predicate)
    }

    /** Appends without the guard, for a document that is still being composed in memory. */
    fun compose(newItems: List<T>) {
        rows.addAll(newItems)
    }
}
