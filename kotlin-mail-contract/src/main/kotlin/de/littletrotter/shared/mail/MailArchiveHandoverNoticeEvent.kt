package de.littletrotter.shared.mail

import java.time.Instant
import java.time.format.DateTimeParseException

/** Why administrators are told about the archive hand-over of one sent mail. */
enum class MailArchiveHandoverNoticeReason {
    /** A mail was sent while no archive address is set up, so no copy could be handed over. */
    ADDRESS_MISSING,

    /** Handing the copy over to the archive address failed; [MailArchiveHandoverNoticeEvent.error] says why. */
    HANDOVER_FAILED,
}

/**
 * `mail.archive-handover.notice` — published by the component when an administrator has to act on the
 * archive hand-over: the address is missing, or a hand-over failed.
 *
 * Direction: component → host, on [MailSubjects.ARCHIVE_HANDOVER_NOTICE] / [MailStreams.RESULT].
 * Owner: this contract. Delivery is at-least-once, so a consumer must tolerate the same notice twice
 * and should show one notice per [journalId] and [reason].
 */
data class MailArchiveHandoverNoticeEvent(
    /** The component's journal row id of the sent mail concerned. */
    val journalId: Long,
    /** Echoed verbatim from the compose request, so the host can name the document without a lookup. */
    val referenceKey: String,
    val reason: MailArchiveHandoverNoticeReason,
    /** The failure text for [MailArchiveHandoverNoticeReason.HANDOVER_FAILED]; `null` otherwise. */
    val error: String? = null,
    /** When the component recorded the fact, UTC. */
    val occurredAt: Instant,
    val documentKind: MailDocumentKind = MailDocumentKind.INVOICE_DISPATCH,
    val schemaVersion: Int = MailContract.SCHEMA_VERSION,
) {
    companion object {

        private const val SCHEMA_VERSION = "schemaVersion"
        private const val JOURNAL_ID = "journalId"
        private const val REFERENCE_KEY = "referenceKey"
        private const val REASON = "reason"
        private const val ERROR = "error"
        private const val OCCURRED_AT = "occurredAt"
        private const val DOCUMENT_KIND = "documentKind"
        private const val WHAT = "MailArchiveHandoverNoticeEvent"

        fun encode(event: MailArchiveHandoverNoticeEvent): String {
            val root = MailWireJson.mapper.createObjectNode()
            root.put(SCHEMA_VERSION, event.schemaVersion)
            root.put(JOURNAL_ID, event.journalId)
            root.put(REFERENCE_KEY, event.referenceKey)
            root.put(REASON, event.reason.name)
            if (event.error == null) root.putNull(ERROR) else root.put(ERROR, event.error)
            root.put(OCCURRED_AT, event.occurredAt.toString())
            root.put(DOCUMENT_KIND, event.documentKind.name)
            return MailWireJson.mapper.writeValueAsString(root)
        }

        /** Unknown fields are ignored; every field except [error] and [documentKind] is required. */
        fun decode(json: String?): MailArchiveHandoverNoticeEvent {
            val root = MailWireJson.readObject(json, WHAT)
            val rawOccurredAt = MailWireJson.requiredText(root, OCCURRED_AT, WHAT)
            val occurredAt = try {
                Instant.parse(rawOccurredAt)
            } catch (e: DateTimeParseException) {
                throw MailContractViolationException("$WHAT: field '$OCCURRED_AT' is not an ISO-8601 instant: '$rawOccurredAt'", e)
            }
            return MailArchiveHandoverNoticeEvent(
                journalId = MailWireJson.requiredLong(root, JOURNAL_ID, WHAT),
                referenceKey = MailWireJson.requiredText(root, REFERENCE_KEY, WHAT),
                reason = MailWireJson.requiredEnum(root, REASON, MailArchiveHandoverNoticeReason.entries.toTypedArray(), WHAT),
                error = MailWireJson.optionalText(root, ERROR, WHAT),
                occurredAt = occurredAt,
                documentKind = MailWireJson.optionalEnum(root, DOCUMENT_KIND, MailDocumentKind.INVOICE_DISPATCH, WHAT),
                schemaVersion = MailWireJson.requiredInt(root, SCHEMA_VERSION, WHAT),
            )
        }
    }
}
