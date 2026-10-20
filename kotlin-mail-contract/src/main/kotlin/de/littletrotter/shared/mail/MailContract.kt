/**
 * **Package `de.littletrotter.shared.mail` — the published language of the mail-dispatch bounded
 * context.**
 *
 * Invoicing (this repository) and mail dispatch (`little-trotter-email`) are two bounded contexts
 * in a Customer/Supplier relationship. This package is the *only* vocabulary they share: data
 * classes, enums, constants and one placeholder codec — **no** domain logic, **no** entities,
 * **no** service interface of either side. The host never sees `MailJournalEntry`, the component
 * never sees `Invoice`. Each side maps this contract into its own model in an anti-corruption
 * layer at its adapter boundary (host `InvoiceMailComposeMapper`, component
 * `ComposeRequestAssembler`), which is why the contract carries its *own* enums rather than
 * either side's — see [MailDocumentFamily] and [MailSendOutcome].
 *
 * **Target home.** A component's published language should not live in the host's repository. Its
 * target home is a dedicated `email-contract` artifact owned by `little-trotter-email`, created
 * together with artifact publishing in the new org repository. Until that exists the package sits
 * in `kotlin-shared`, which both repositories already depend on. The
 * move is a package rename; nothing here is built to prepare for it.
 *
 * **Independent deployment.** Host and component are deployed separately, so a version skew of
 * minutes exists on every upgrade. Two rules follow, and both are enforced by the codecs in this
 * package rather than left to convention:
 * - every event carries [MailContract.SCHEMA_VERSION], written on encode and read back on decode;
 * - **unknown fields are ignored on read** — a field a newer producer added must never fail an
 *   older reader.
 *
 * **What is deliberately absent.** `MailDispatchRequestedEvent` (the later UI-less automatic
 * dispatch) is designed in the concept but not implemented here: an unused wire type
 * is ballast, and adding it later is additive.
 */
package de.littletrotter.shared.mail

/**
 * Contract-wide constants for this published language.
 *
 * Direction: neither — this is metadata both sides write and read.
 * Owner: the mail-dispatch context (`little-trotter-email`), hosted here per the package KDoc.
 */
object MailContract {

    /**
     * The version stamped onto every event and request in this package. Bumped only when the wire
     * shape changes in a way a reader has to branch on; until the first production instance runs,
     * breaking a contract is a rename in two repositories and needs no ceremony.
     *
     * Decoders record what they read into the DTO's own `schemaVersion` field rather than
     * rejecting an unfamiliar value: rejecting would turn a minutes-long deployment skew into an
     * outage, which is the exact failure this field exists to avoid.
     *
     * **2**: [MailComposeRequest] gained [MailComposeRequest.documentKind] (which dispatch-kind
     * catalogue of [MailPlaceholderCatalogue] the placeholder values belong to), written as an
     * optional string that defaults to `INVOICE_DISPATCH` on decode — a producer still writing
     * version 1 (the field absent) is read exactly as before, so the bump changes nothing for an
     * old reader or an old writer, only adds a field a new one of either may use.
     *
     * **3**: REST view and request types added for the mail component API; [MailComposeRequest] gained [MailComposeRequest.senderBlock].
     *
     * **4**: [MailSendResultEvent] gained [MailSendResultEvent.documentKind], optional on the wire and
     * defaulting to `INVOICE_DISPATCH`, so the invoice and offer hosts can tell results on the shared subject apart.
     *
     * **5**: [MailJournalEntryView] gained [MailJournalEntryView.archiveHandoverStatus] with
     * [MailJournalEntryView.archiveHandoverAt] and [MailJournalEntryView.archiveHandoverError]; the status
     * defaults to `NOT_CONFIGURED` when absent, so an older producer is read as before.
     */
    const val SCHEMA_VERSION: Int = 5
}
