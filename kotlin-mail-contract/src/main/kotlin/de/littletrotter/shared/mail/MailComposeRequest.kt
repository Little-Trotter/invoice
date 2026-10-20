package de.littletrotter.shared.mail

import com.fasterxml.jackson.databind.JsonNode

/**
 * What the invoicing application hands over when a user presses "E-Mail senden" on a document.
 *
 * Direction: host → component, as the body of `POST /mail/api/compose`.
 * Owner: this contract; assembled by the host's `InvoiceMailComposeMapper`, consumed by the
 * component's `ComposeRequestAssembler`, which turns it into a `MailDraft` and answers with a
 * draft id the host opens in an iframe.
 *
 * **Why this one flow is REST and not NATS.** A human is waiting for a draft id, and
 * the handover creates no durable state that matters — a lost draft means the user clicks the
 * button again. Everything that must survive stays on NATS, in the other direction
 * ([MailSendResultEvent]).
 *
 * **What is deliberately not in here.** No `Invoice`, no `Recipient`, no invoice line items, no
 * subject or body: the component owns templates and rendering, the host owns the document. The
 * request carries only the values a template may substitute ([placeholderValues]) and pointers to
 * the files to attach ([attachments]) — "SPI by value, not by entity".
 *
 * [referenceKey] is the opaque string that ties everything together: it comes back unchanged on
 * [MailSendResultEvent.referenceKey], and that is how the host finds the invoice again without the
 * component ever knowing what the key means.
 */
data class MailComposeRequest(
    /** The invoice/document number this mail is about; echoed back on the result event. */
    val referenceKey: String,
    /** Which template family the component should offer — see [MailDocumentFamily] on why this is its own enum. */
    val documentFamily: MailDocumentFamily,
    /** The recipient's email address. */
    val recipientAddress: String,
    /** The recipient's display name, for the `To:` header and the `recipientName` placeholder. */
    val recipientName: String,
    /**
     * Which [MailPlaceholderCatalogue] [placeholderValues] was filled in against — an invoice
     * dispatch, a quote dispatch, .... Defaults to [MailDocumentKind.INVOICE_DISPATCH] so a
     * schema-version-1 payload (written before this field existed, and every host call site until
     * it is updated) decodes exactly as it always has.
     */
    val documentKind: MailDocumentKind = MailDocumentKind.INVOICE_DISPATCH,
    /** Typed template values, keyed by template key (`invoiceNumber`, `grossTotal`, …). May be empty. */
    val placeholderValues: Map<String, MailPlaceholderValue> = emptyMap(),
    /** Documents to attach, by presigned reference. May be empty — a reminder mail carries none. */
    val attachments: List<MailAttachmentHandover> = emptyList(),
    /**
     * Who the mail is from, for the signature the component renders below the body. Optional: a
     * host that sends none gets the component's own default sender text.
     */
    val senderBlock: MailSenderBlock? = null,
    /** The contract version this instance was written with; see [MailContract.SCHEMA_VERSION]. */
    val schemaVersion: Int = MailContract.SCHEMA_VERSION,
) {
    companion object {

        private const val SCHEMA_VERSION = "schemaVersion"
        private const val REFERENCE_KEY = "referenceKey"
        private const val DOCUMENT_FAMILY = "documentFamily"
        private const val DOCUMENT_KIND = "documentKind"
        private const val RECIPIENT_ADDRESS = "recipientAddress"
        private const val RECIPIENT_NAME = "recipientName"
        private const val PLACEHOLDER_VALUES = "placeholderValues"
        private const val ATTACHMENTS = "attachments"
        private const val SENDER_BLOCK = "senderBlock"
        private const val WHAT = "MailComposeRequest"

        /**
         * Writes the wire shape field by field, in the order pinned by this package's golden-JSON
         * tests — see [MailWireJson] for why the keys are literals here rather than reflected
         * Kotlin property names.
         */
        fun encode(request: MailComposeRequest): String {
            val root = MailWireJson.mapper.createObjectNode()
            root.put(SCHEMA_VERSION, request.schemaVersion)
            root.put(REFERENCE_KEY, request.referenceKey)
            root.put(DOCUMENT_FAMILY, request.documentFamily.name)
            root.put(DOCUMENT_KIND, request.documentKind.name)
            root.put(RECIPIENT_ADDRESS, request.recipientAddress)
            root.put(RECIPIENT_NAME, request.recipientName)
            root.set<JsonNode>(
                PLACEHOLDER_VALUES,
                MailPlaceholderWireCodec.toNode(request.placeholderValues),
            )
            val attachments = root.putArray(ATTACHMENTS)
            request.attachments.forEach { attachments.add(MailAttachmentHandover.toNode(it)) }
            request.senderBlock?.let { root.set<JsonNode>(SENDER_BLOCK, MailSenderBlock.toNode(it)) }
            return MailWireJson.mapper.writeValueAsString(root)
        }

        /**
         * Reads the wire shape. Unknown fields are ignored (only the known keys are asked for);
         * every field above is required and throws [MailContractViolationException] when absent,
         * blank or of the wrong JSON type — including [placeholderValues] and [attachments], whose
         * *empty* forms (`{}` / `[]`) are meaningful and must be sent explicitly rather than
         * arrived at by omission.
         */
        fun decode(json: String?): MailComposeRequest {
            val root = MailWireJson.readObject(json, WHAT)
            val placeholders = MailWireJson.requiredObject(root, PLACEHOLDER_VALUES, WHAT)
            val attachments = MailWireJson.requiredArray(root, ATTACHMENTS, WHAT)
            return MailComposeRequest(
                referenceKey = MailWireJson.requiredText(root, REFERENCE_KEY, WHAT),
                documentFamily = MailWireJson.requiredEnum(
                    root,
                    DOCUMENT_FAMILY,
                    MailDocumentFamily.entries.toTypedArray(),
                    WHAT,
                ),
                documentKind = MailWireJson.optionalEnum(root, DOCUMENT_KIND, MailDocumentKind.INVOICE_DISPATCH, WHAT),
                recipientAddress = MailWireJson.requiredText(root, RECIPIENT_ADDRESS, WHAT),
                recipientName = MailWireJson.requiredText(root, RECIPIENT_NAME, WHAT),
                placeholderValues = MailPlaceholderWireCodec.fromNode(placeholders, WHAT),
                attachments = attachments.map {
                    MailAttachmentHandover.fromNode(
                        MailWireJson.requireObjectElement(it, ATTACHMENTS, WHAT),
                        "$WHAT.attachments",
                    )
                },
                senderBlock = MailWireJson.optionalObject(root, SENDER_BLOCK, WHAT)
                    ?.let { MailSenderBlock.fromNode(it, "$WHAT.$SENDER_BLOCK") },
                schemaVersion = MailWireJson.requiredInt(root, SCHEMA_VERSION, WHAT),
            )
        }
    }
}
