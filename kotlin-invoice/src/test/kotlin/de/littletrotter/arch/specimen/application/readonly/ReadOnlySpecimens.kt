package de.littletrotter.arch.specimen.application.readonly

import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.isReadOnly

/** Violates the rule: an application class decides on the aggregate's read-only state itself. */
class SpecimenReadOnlyCaller {
    fun skipIfLocked(invoice: Invoice): Boolean = invoice.isReadOnly()
}

/** Look-alike that stays silent: its `isReadOnly` belongs to the class itself, not to the domain. */
class SpecimenLookalikeCaller {
    private val form = SpecimenForm()

    fun skipIfLocked(): Boolean = form.isReadOnly()
}

class SpecimenForm {
    fun isReadOnly(): Boolean = true
}
