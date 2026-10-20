package de.littletrotter.arch.specimen.application.event

import jakarta.enterprise.event.Event

/** Violates the rule: an application class fires a CDI event itself instead of handing it to the dispatcher. */
class SpecimenDirectEventFirer(private val bus: Event<String>) {
    fun announce() {
        bus.fireAsync("changed")
    }
}

/** Look-alike that stays silent: its `fire` belongs to the class itself, not to a CDI event. */
class SpecimenLookalikeFirer {
    private val bell = SpecimenBell()

    fun announce() {
        bell.fire("changed")
    }
}

class SpecimenBell {
    fun fire(message: String): String = message
}
