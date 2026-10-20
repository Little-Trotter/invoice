package de.littletrotter.shared.mail

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * The sender's contact block a host may attach to a compose request: name, optional phone, email
 * and website, and the legal footer lines.
 *
 * Direction: host → component, as the optional `senderBlock` object of [MailComposeRequest]. Only
 * [name] is required; every other part may be absent on the wire.
 */
data class MailSenderBlock(
    val name: String,
    val phone: String? = null,
    val email: String? = null,
    val website: String? = null,
    val legalLines: List<String> = emptyList(),
) {
    internal companion object {

        private const val NAME = "name"
        private const val PHONE = "phone"
        private const val EMAIL = "email"
        private const val WEBSITE = "website"
        private const val LEGAL_LINES = "legalLines"

        /** Writes the block field by field; absent optional parts are omitted, not written as `null`. */
        fun toNode(block: MailSenderBlock): ObjectNode {
            val node = MailWireJson.mapper.createObjectNode()
            node.put(NAME, block.name)
            block.phone?.let { node.put(PHONE, it) }
            block.email?.let { node.put(EMAIL, it) }
            block.website?.let { node.put(WEBSITE, it) }
            val lines = node.putArray(LEGAL_LINES)
            block.legalLines.forEach { lines.add(it) }
            return node
        }

        /** Reads the block; unknown fields are ignored and a missing `legalLines` reads as empty. */
        fun fromNode(node: ObjectNode, what: String): MailSenderBlock {
            val lines = node.get(LEGAL_LINES)
            if (lines != null && !lines.isNull && lines !is ArrayNode) {
                throw MailContractViolationException("$what: optional field '$LEGAL_LINES' is not an array")
            }
            return MailSenderBlock(
                name = MailWireJson.requiredText(node, NAME, what),
                phone = MailWireJson.optionalText(node, PHONE, what),
                email = MailWireJson.optionalText(node, EMAIL, what),
                website = MailWireJson.optionalText(node, WEBSITE, what),
                legalLines = lines?.map { MailWireJson.requiredElementText(it, LEGAL_LINES, what) } ?: emptyList(),
            )
        }
    }
}
