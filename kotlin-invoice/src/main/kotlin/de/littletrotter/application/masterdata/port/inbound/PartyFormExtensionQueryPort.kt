package de.littletrotter.application.masterdata.port.inbound

import de.littletrotter.spi.party.PartyFormExtension
import de.littletrotter.spi.party.PartyIdentifierSpec
import de.littletrotter.spi.party.PartyKind

/** Input port through which the customer and sender forms obtain the registered party form extensions. */
interface PartyFormExtensionQueryPort {
    /** Every registered extension. */
    fun extensions(): List<PartyFormExtension>

    /** Every identifier aspect of [kind] paired with the extension that owns it. */
    fun enabledAspects(kind: PartyKind): List<Pair<PartyIdentifierSpec, PartyFormExtension>>
}
