package de.littletrotter.application.shared

import jakarta.enterprise.context.ApplicationScoped

/**
 * Lets a non-interactive entry point (a message consumer) act in the name of the person who
 * caused the event, so audit columns, the change journal and calls to the offer service name that
 * person instead of "system".
 *
 * Thread-bound rather than request-scoped: the consumer runs the whole unit of work, transaction
 * included, on the thread that received the message, and nothing outside that block may ever see
 * the override.
 */
@ApplicationScoped
class ActorOverride {

    private val current = ThreadLocal<String?>()

    /** The actor the current thread acts for, or `null` when no override is active. */
    fun current(): String? = current.get()

    /** Runs [block] acting for [actor]; the previous state is restored afterwards, even on failure. */
    fun <T> runAs(actor: String, block: () -> T): T {
        val previous = current.get()
        current.set(actor)
        try {
            return block()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }
}
