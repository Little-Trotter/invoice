package de.littletrotter.arch.specimen.entity

import jakarta.persistence.Embeddable

/**
 * Test-only specimens for [de.littletrotter.arch.EntitySetterArchitectureTest]. They sit outside the
 * domain package on purpose, so the callers below count as foreign code. The accepted classes show
 * the intended shape and must stay unreported.
 */
@Embeddable
class ViolatingOpenSetterValue {
    var label: String = ""
}

@Embeddable
class AcceptedClosedSetterValue {
    var label: String = ""
        protected set

    fun relabel(label: String) {
        this.label = label
    }
}

class ViolatingSetterCaller {
    fun rename(value: ViolatingOpenSetterValue) {
        value.label = "renamed"
    }
}

class AcceptedIntentCaller {
    fun rename(value: AcceptedClosedSetterValue) {
        value.relabel("renamed")
    }
}
