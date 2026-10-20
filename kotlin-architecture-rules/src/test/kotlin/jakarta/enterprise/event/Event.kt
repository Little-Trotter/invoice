package jakarta.enterprise.event

/** Stand-in for the CDI event bus; only the method names matter to the rules. */
open class Event<T> {
    fun fire(payload: T) = payload

    fun fireAsync(payload: T) = payload

    fun select(): Event<T> = this
}
