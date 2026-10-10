package de.littletrotter.spi.party

/**
 * Extension point for the master-data party forms. A [PartyFormExtension] contributes
 * value+scheme identifier pairs ([PartyIdentifierSpec]) that dock into the seller (BT-34) and/or
 * buyer (BT-49) forms.
 *
 * Unlike [de.littletrotter.spi.form.InvoiceFormModule], the extension also *owns* the read/write
 * access to the persisted party identifiers. The view renders a pair generically and calls
 * [read]/[write] with the domain object it is already editing; it never references the
 * identifier's storage model directly. Any bean implementing this interface on the classpath is
 * active unconditionally.
 */
interface PartyFormExtension {

    /** Stable identifier of this module (module-scope unique). */
    val id: String

    /** The identifier pairs this module contributes for the given [kind]. */
    fun pairs(kind: PartyKind): List<PartyIdentifierSpec>

    /** Reads the current value+scheme state of [spec] from the given `party` domain object.
     *  `party` is the in-flight `SenderProfile` (for [PartyKind.SELLER]) or `Recipient` (for
     *  [PartyKind.BUYER]). */
    fun read(party: Any, kind: PartyKind, spec: PartyIdentifierSpec): PartyPairState

    /** Writes the given [state] of [spec] onto the `party` domain object. */
    fun write(party: Any, kind: PartyKind, spec: PartyIdentifierSpec, state: PartyPairState)
}
