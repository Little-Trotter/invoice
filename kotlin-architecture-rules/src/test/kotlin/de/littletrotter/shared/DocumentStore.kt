package de.littletrotter.shared

/** Stand-in for the object store port the shared I/O targets name. */
class DocumentStore {
    fun put(key: String) = key
}
