package de.littletrotter.shared.mail

import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * One document the host hands over to be attached — by reference, never by value.
 *
 * Direction: host → component, inside [MailComposeRequest].
 * Owner: this contract.
 *
 * **Why a presigned URL and not a bucket key.** The two contexts own separate S3 buckets with
 * separate access keys (`invoice-documents` here, `mail-journal` there); handing over a bare key
 * would force the component to hold credentials for the host's bucket, which is exactly the
 * coupling the standalone component exists to remove. [downloadUrl] is a short-lived presigned GET
 * the host mints for this one object. The component **copies the bytes into its own bucket** while
 * assembling the draft, so nothing in the send path — the audit-relevant part — depends on a URL
 * that will expire or on a bucket the mail context does not own.
 *
 * [sizeBytes] and [sha256] are the host's statement about the object *at handover time*: the
 * component checks the fetched bytes against both, so the PDF that reaches the customer is provably
 * the one the user approved and not merely the one sitting at that key now. Both
 * are therefore required — a missing checksum must fail, never silently disable the check.
 */
data class MailAttachmentHandover(
    /** The filename the recipient sees in the MIME part, e.g. `Rechnung-2026-0001.pdf`. */
    val filename: String,
    /** The MIME type of the object, e.g. `application/pdf`. */
    val contentType: String,
    /** Byte length of the object, for the component's total-mail-size budget check. */
    val sizeBytes: Long,
    /** Lowercase hex SHA-256 of the object's bytes, verified after the fetch. */
    val sha256: String,
    /** Short-lived presigned GET URL the component fetches the bytes from exactly once. */
    val downloadUrl: String,
) {
    companion object {

        private const val FILENAME = "filename"
        private const val CONTENT_TYPE = "contentType"
        private const val SIZE_BYTES = "sizeBytes"
        private const val SHA256 = "sha256"
        private const val DOWNLOAD_URL = "downloadUrl"

        internal fun toNode(handover: MailAttachmentHandover): ObjectNode {
            val node = MailWireJson.mapper.createObjectNode()
            node.put(FILENAME, handover.filename)
            node.put(CONTENT_TYPE, handover.contentType)
            node.put(SIZE_BYTES, handover.sizeBytes)
            node.put(SHA256, handover.sha256)
            node.put(DOWNLOAD_URL, handover.downloadUrl)
            return node
        }

        internal fun fromNode(node: ObjectNode, what: String): MailAttachmentHandover =
            MailAttachmentHandover(
                filename = MailWireJson.requiredText(node, FILENAME, what),
                contentType = MailWireJson.requiredText(node, CONTENT_TYPE, what),
                sizeBytes = MailWireJson.requiredLong(node, SIZE_BYTES, what),
                sha256 = MailWireJson.requiredText(node, SHA256, what),
                downloadUrl = MailWireJson.requiredText(node, DOWNLOAD_URL, what),
            )

        /** Encodes a single handover; the usual carrier is [MailComposeRequest.attachments]. */
        fun encode(handover: MailAttachmentHandover): String =
            MailWireJson.mapper.writeValueAsString(toNode(handover))

        /** Decodes a single handover, throwing on a missing or blank required field. */
        fun decode(json: String?): MailAttachmentHandover =
            fromNode(MailWireJson.readObject(json, WHAT), WHAT)

        private const val WHAT = "MailAttachmentHandover"
    }
}
