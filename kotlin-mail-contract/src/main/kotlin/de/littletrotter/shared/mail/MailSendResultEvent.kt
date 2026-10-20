package de.littletrotter.shared.mail

import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The terminal outcome of one send attempt, as it travels on the wire.
 *
 * Direction: component → host.
 * Owner: this contract.
 *
 * **Deliberately not the component's `MailSendStatus`.** That enum has four constants
 * (`CREATED`, `QUEUED`, `SENT`, `FAILED`), is persisted as a SMALLINT code, and models the whole
 * journal lifecycle including the two states the host has no business knowing about. Only the two
 * terminal transitions are ever published, so the contract names exactly those two — the same
 * reasoning as [MailDocumentFamily]: neither side depends on the other's enum, and each maps at
 * its adapter boundary. Do not merge the two enums; a new internal lifecycle state in the
 * component must not become a wire value the host has to handle.
 */
enum class MailSendOutcome {
    /** The SMTP handover succeeded; [MailSendResultEvent.messageId] carries the Message-ID. */
    SENT,

    /** The attempt failed terminally and is never overwritten — a retry is a new journal row. */
    FAILED,
}

/**
 * `mail.send.result` — published by the component after every terminal transition, consumed by the
 * host to flip the invoice's "versendet" flag.
 *
 * Direction: component → host, on [MailSubjects.SEND_RESULT] / [MailStreams.RESULT].
 * Owner: this contract; produced by the component's `MailResultPublisher`, consumed by the host's
 * `MailResultProjection` (registered only when `MAIL_ENABLED=true`).
 *
 * **[referenceKey] is what makes this fire-and-forget.** An earlier version of this event
 * carried `{journalId, status, messageId}` only, which would have forced the host to call back
 * into the component's REST API to learn which invoice a journal id belongs to — turning a
 * fire-and-forget event into a synchronous dependency on a component that may be down. Echoing the
 * key the host itself supplied on [MailComposeRequest.referenceKey] removes that call entirely; the
 * component never has to know what the key means.
 *
 * [journalId] stays on the event as the component's own handle, for support and for a deep link
 * into the mail journal — the host stores it but never interprets it.
 */
data class MailSendResultEvent(
    /** The component's journal row id — opaque to the host, useful only as a link/support handle. */
    val journalId: Long,
    /** Echoed verbatim from [MailComposeRequest.referenceKey]; the host's join key. */
    val referenceKey: String,
    /** SENT or FAILED — see [MailSendOutcome] on why this is not the component's own enum. */
    val status: MailSendOutcome,
    /** The SMTP Message-ID on success; `null` on [MailSendOutcome.FAILED], which produces none. */
    val messageId: String? = null,
    /** When the component recorded the terminal transition, UTC. */
    val sentAt: Instant,
    /** The contract version this instance was written with; see [MailContract.SCHEMA_VERSION]. */
    val schemaVersion: Int = MailContract.SCHEMA_VERSION,
    /**
     * Which kind of document the dispatch carried. Two hosts (invoice and offer) consume the same
     * result subject and must tell their results apart without a lookup of the reference key;
     * absent on the wire reads as [MailDocumentKind.INVOICE_DISPATCH], so older producers stay valid.
     */
    val documentKind: MailDocumentKind = MailDocumentKind.INVOICE_DISPATCH,
) {
    companion object {

        private const val SCHEMA_VERSION = "schemaVersion"
        private const val JOURNAL_ID = "journalId"
        private const val REFERENCE_KEY = "referenceKey"
        private const val STATUS = "status"
        private const val MESSAGE_ID = "messageId"
        private const val SENT_AT = "sentAt"
        private const val DOCUMENT_KIND = "documentKind"
        private const val WHAT = "MailSendResultEvent"

        /**
         * Writes the wire shape field by field — replacing the hand-assembled `ObjectNode` the
         * component's `MailResultPublisher` built, which is precisely the copy this contract
         * exists to delete. [messageId] is written as an explicit JSON `null` rather than omitted,
         * so a FAILED and a SENT event have the same key set and a reader never has to
         * distinguish "absent" from "none".
         */
        fun encode(event: MailSendResultEvent): String {
            val root = MailWireJson.mapper.createObjectNode()
            root.put(SCHEMA_VERSION, event.schemaVersion)
            root.put(JOURNAL_ID, event.journalId)
            root.put(REFERENCE_KEY, event.referenceKey)
            root.put(STATUS, event.status.name)
            if (event.messageId == null) root.putNull(MESSAGE_ID) else root.put(MESSAGE_ID, event.messageId)
            root.put(SENT_AT, event.sentAt.toString())
            root.put(DOCUMENT_KIND, event.documentKind.name)
            return MailWireJson.mapper.writeValueAsString(root)
        }

        /**
         * Reads the wire shape. Unknown fields are ignored; [messageId] may be absent or `null`,
         * every other field is required and throws [MailContractViolationException] otherwise —
         * an event whose [referenceKey] defaulted to `""` would mark the wrong invoice, or no
         * invoice, as sent.
         */
        fun decode(json: String?): MailSendResultEvent {
            val root = MailWireJson.readObject(json, WHAT)
            val rawSentAt = MailWireJson.requiredText(root, SENT_AT, WHAT)
            return MailSendResultEvent(
                journalId = MailWireJson.requiredLong(root, JOURNAL_ID, WHAT),
                referenceKey = MailWireJson.requiredText(root, REFERENCE_KEY, WHAT),
                status = MailWireJson.requiredEnum(root, STATUS, MailSendOutcome.entries.toTypedArray(), WHAT),
                messageId = MailWireJson.optionalText(root, MESSAGE_ID, WHAT),
                sentAt = parseInstant(rawSentAt),
                schemaVersion = MailWireJson.requiredInt(root, SCHEMA_VERSION, WHAT),
                documentKind = MailWireJson.optionalEnum(root, DOCUMENT_KIND, MailDocumentKind.INVOICE_DISPATCH, WHAT),
            )
        }

        private fun parseInstant(raw: String): Instant =
            try {
                Instant.parse(raw)
            } catch (e: DateTimeParseException) {
                throw MailContractViolationException("$WHAT: field '$SENT_AT' is not an ISO-8601 instant: '$raw'", e)
            }
    }
}
