package de.littletrotter.shared.mail

/**
 * The document family a recipient is billed in, **as it travels on the wire** in
 * [MailComposeRequest].
 *
 * Direction: host → component.
 * Owner: this contract — and that is the whole point of the type.
 *
 * ## Deliberately a third enum. Do not unify.
 *
 * Three enums with the same two constants exist on purpose, one per context:
 *
 * | Type | Lives in | Meaning |
 * |---|---|---|
 * | `InvoiceFormat` (`Recipient.invoiceFormat`) | invoicing context | which invoice layout/format the host produces for that recipient |
 * | **[MailDocumentFamily]** | this contract | the value that crosses the boundary |
 * | `DocumentFamily` (`de.littletrotter.email.core`) | mail-dispatch context | which template family the component picks |
 *
 * A future reader will be tempted to "clean this up" into one shared enum. Doing so would couple
 * the two contexts' release cycles to each other: the host could not add an invoice format without
 * the component shipping a matching constant, and the component could not add a template family
 * without touching the host's persisted `invoiceFormat` ordinals (which are stored as ordinals —
 * see [de.littletrotter.shared.Currency]'s KDoc for why that list may only grow at the end).
 * Neither side may depend on the other's enum; each maps at its own adapter boundary
 * (`InvoiceMailComposeMapper` on the host, `ComposeRequestAssembler` in the component), and a
 * value one side does not know is an anti-corruption-layer decision, not a compile error.
 *
 * Serialized by [name], never by ordinal: an ordinal on the wire would make constant *order* part
 * of the contract, and both mapping sides read this as a string.
 */
enum class MailDocumentFamily {
    GERMAN,
    INTERNATIONAL,
}
