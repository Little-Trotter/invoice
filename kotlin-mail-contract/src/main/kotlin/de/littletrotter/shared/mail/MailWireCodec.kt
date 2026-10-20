package de.littletrotter.shared.mail

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.core.type.TypeReference

/**
 * The one JSON codec for the plain view and request types of this package (everything except the
 * compose request and the send-result event, which keep their field-by-field codecs).
 *
 * Both sides of the REST API use it, so a type is written and read identically by host and
 * component: enums by name, `Instant` and `LocalDate` as ISO-8601 text, unknown fields ignored,
 * absent fields filled from the constructor defaults.
 */
object MailWireCodec {

    /** Serialises [value] to its wire JSON. */
    fun encode(value: Any): String = MailWireJson.mapper.writeValueAsString(value)

    /**
     * Parses [json] as the type described by [type] (a [TypeReference] keeps generic shapes such as
     * lists intact). A blank payload or JSON that does not fit it throws
     * [MailContractViolationException] naming [what]; nothing ever decodes to a half-empty object.
     */
    fun <T> decode(json: String?, what: String, type: TypeReference<T>): T {
        if (json.isNullOrBlank()) {
            throw MailContractViolationException("$what: payload is blank")
        }
        return try {
            MailWireJson.mapper.readValue(json, type)
        } catch (e: JacksonException) {
            throw MailContractViolationException("$what: payload does not match the contract", e)
        }
    }
}
