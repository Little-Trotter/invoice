package de.littletrotter.shared.mail

/**
 * Which dispatch kind [MailComposeRequest.placeholderValues] was filled in for — the key
 * [MailPlaceholderCatalogue] resolves per-kind entries by.
 *
 * Direction: host → component.
 * Owner: this contract.
 *
 * **Not [MailDocumentFamily].** That enum decides the *language* a template renders in (a German vs.
 * an international recipient); this one decides *which template* is offered at all (an invoice
 * dispatch vs. a quote dispatch). The two are independent axes of the same request, which is why
 * both fields exist on [MailComposeRequest] side by side rather than one being folded into the
 * other.
 *
 * **Deliberately the published language's own vocabulary, not either side's persisted one.** The
 * mail component persists template rows under its own `MailTemplateKind` (`SMALLINT` codes, its own
 * growth rule); the host has no enum of its own here at all today. Mapping this contract enum onto
 * `MailTemplateKind` is the component's anti-corruption-layer decision, made once in its compose
 * adapter's `acl` package — this contract does not know the component persists anything, let alone
 * how.
 *
 * Serialized by [name], never by ordinal — see [MailDocumentFamily]'s own KDoc for why an ordinal on
 * the wire would make constant *order* part of the contract.
 */
enum class MailDocumentKind {
    INVOICE_DISPATCH,
    QUOTE_DISPATCH,
}
