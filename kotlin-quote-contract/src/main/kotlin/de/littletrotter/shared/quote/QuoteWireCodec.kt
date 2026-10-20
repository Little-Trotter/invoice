package de.littletrotter.shared.quote

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.core.type.TypeReference

/**
 * The one JSON codec for every view and request type of this package. Host and component use it
 * for both directions, so a type is written and read identically on both sides: enums by name,
 * identifiers as text, dates and instants as ISO-8601 text, amounts as decimal numbers.
 */
object QuoteWireCodec {

    /** Serialises [value] to its wire JSON. */
    fun encode(value: Any): String = QuoteWireJson.mapper.writeValueAsString(value)

    /** Parses [json] as the plain type [type]; see the [TypeReference] overload for the failure rules. */
    fun <T> decode(json: String?, what: String, type: Class<T>): T =
        read(json, what) { QuoteWireJson.mapper.readValue(it, type) }

    /**
     * Parses [json] as the type described by [type] (a [TypeReference] keeps generic shapes such as
     * lists intact). A blank payload or JSON that does not fit it throws
     * [QuoteContractViolationException] naming [what].
     */
    fun <T> decode(json: String?, what: String, type: TypeReference<T>): T =
        read(json, what) { QuoteWireJson.mapper.readValue(it, type) }

    private fun <T> read(json: String?, what: String, parse: (String) -> T): T {
        if (json.isNullOrBlank()) {
            throw QuoteContractViolationException("$what: payload is blank")
        }
        return try {
            parse(json)
        } catch (e: JacksonException) {
            throw QuoteContractViolationException("$what: payload does not match the contract", e)
        }
    }
}
