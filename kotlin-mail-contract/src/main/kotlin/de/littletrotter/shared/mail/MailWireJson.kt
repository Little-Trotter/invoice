package de.littletrotter.shared.mail

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule

/**
 * The strict JSON reader every codec in this package shares. `internal`: an implementation detail
 * of the contract, not part of the published language — neither the host nor the component ever
 * sees it.
 *
 * **Why the field shape is written by hand instead of by reflection.** Every encoder in this
 * package builds its `ObjectNode` field by field and every decoder reads it field by field,
 * deliberately, rather than letting `jackson-module-kotlin` derive both from the data class. The
 * reason is the same one the mail component's own codecs give: with reflection the *wire contract*
 * is a side effect of a Kotlin property name, so a refactoring rename in one repository silently
 * renames a field the other repository parses, and nothing fails until a mail goes out wrong. Here
 * the JSON key is a string literal in this file, pinned by golden-JSON tests, and a rename of the
 * Kotlin property leaves the wire shape untouched.
 *
 * The two halves of the independent-deployment rule (package KDoc) live here:
 * - **unknown fields are ignored on read** — the readers only ask for the keys they know, so a key
 *   a newer producer added is invisible to an older reader by construction, with no
 *   `FAIL_ON_UNKNOWN_PROPERTIES` switch to forget;
 * - **required fields fail loudly** — [requiredText] and friends throw
 *   [MailContractViolationException] on absent, null, blank or wrongly-typed input, never
 *   `""`/`emptyList()` (see that exception's KDoc for the defect this prevents).
 */
internal object MailWireJson {

    /**
     * Shared by the hand-written tree codecs (which only build and walk `JsonNode`s) and by
     * [MailWireCodec], whose plain view/request data classes are bound by reflection. The Kotlin
     * module makes constructor defaults apply to absent fields, the time module writes `Instant`
     * and `LocalDate` as ISO-8601 text, and unknown fields are ignored so a newer writer never
     * breaks an older reader.
     */
    val mapper: ObjectMapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .registerModule(JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)

    /** An optional object field: absent and JSON `null` read as `null`, any other non-object type throws. */
    fun optionalObject(node: ObjectNode, field: String, what: String): ObjectNode? {
        val value = node.get(field) ?: return null
        if (value.isNull) return null
        if (value !is ObjectNode) {
            throw MailContractViolationException("$what: optional field '$field' is not an object")
        }
        return value
    }

    /** Parses [json] into an object node, or throws — a blank or non-object payload is a violation. */
    fun readObject(json: String?, what: String): ObjectNode {
        if (json.isNullOrBlank()) {
            throw MailContractViolationException("$what: payload is blank")
        }
        val parsed = try {
            mapper.readTree(json)
        } catch (e: JacksonException) {
            throw MailContractViolationException("$what: payload is not valid JSON", e)
        }
        if (parsed !is ObjectNode) {
            throw MailContractViolationException("$what: payload is not a JSON object")
        }
        return parsed
    }

    /** A present, non-null, non-blank textual field — anything else throws. */
    fun requiredText(node: ObjectNode, field: String, what: String): String {
        val value = node.get(field)
        if (value == null || value.isBlankOrNonText()) {
            throw MailContractViolationException("$what: required field '$field' is missing or blank")
        }
        return value.asText()
    }

    /** True for JSON `null`, a non-textual node, or blank text; the receiver must already be non-null. */
    private fun JsonNode.isBlankOrNonText(): Boolean = isNull || !isTextual || asText().isBlank()

    /** A textual field that may legitimately be absent or JSON `null`; blank text is still a violation. */
    fun optionalText(node: ObjectNode, field: String, what: String): String? {
        val value = node.get(field) ?: return null
        if (value.isNull) return null
        if (!value.isTextual || value.asText().isBlank()) {
            throw MailContractViolationException("$what: optional field '$field' is present but blank")
        }
        return value.asText()
    }

    /** A present integral field — a missing count/id must never default to `0`. */
    fun requiredLong(node: ObjectNode, field: String, what: String): Long {
        val value = node.get(field)
        if (value == null || value.isNull || !value.isIntegralNumber) {
            throw MailContractViolationException("$what: required field '$field' is missing or not an integer")
        }
        return value.asLong()
    }

    /** A present integral field read as [Int]; used for `schemaVersion` only. */
    fun requiredInt(node: ObjectNode, field: String, what: String): Int =
        requiredLong(node, field, what).toInt()

    /** A present object field. */
    fun requiredObject(node: ObjectNode, field: String, what: String): ObjectNode {
        val value = node.get(field)
        if (value !is ObjectNode) {
            throw MailContractViolationException("$what: required field '$field' is missing or not an object")
        }
        return value
    }

    /** A present array field — a missing attachment list must never default to "no attachments". */
    fun requiredArray(node: ObjectNode, field: String, what: String): ArrayNode {
        val value = node.get(field)
        if (value !is ArrayNode) {
            throw MailContractViolationException("$what: required field '$field' is missing or not an array")
        }
        return value
    }

    /** An element of [requiredArray] that has to be an object. */
    fun requireObjectElement(element: JsonNode, field: String, what: String): ObjectNode {
        if (element !is ObjectNode) {
            throw MailContractViolationException("$what: element of '$field' is not a JSON object")
        }
        return element
    }

    /** A non-blank text element of an array field. */
    fun requiredElementText(element: JsonNode, field: String, what: String): String {
        if (!element.isTextual || element.asText().isBlank()) {
            throw MailContractViolationException("$what: element of '$field' is not a non-blank text")
        }
        return element.asText()
    }

    /** A required textual field mapped onto one of [values] by [Enum.name]. */
    fun <E : Enum<E>> requiredEnum(node: ObjectNode, field: String, values: Array<E>, what: String): E {
        val raw = requiredText(node, field, what)
        return values.firstOrNull { it.name == raw }
            ?: throw MailContractViolationException("$what: field '$field' has unknown value '$raw'")
    }

    /**
     * An enum field that a schema-version-1 writer never sent: absence (and JSON `null`) fall back
     * to [default] rather than failing, which is what lets a field added in a later schema version
     * stay optional for every payload written before that version existed. A *present* but unknown
     * value still fails loudly — that is a genuine violation, not a version-skew omission.
     *
     * `reified`/`enumValues<E>()` rather than an `Array<E>` parameter (the shape [requiredEnum]
     * takes): a fifth parameter here trips the project's `LongParameterList` threshold, and the
     * caller already names [E] via [default]'s type, so asking it to also pass that type's constant
     * array would be redundant on top of being one parameter too many.
     */
    inline fun <reified E : Enum<E>> optionalEnum(node: ObjectNode, field: String, default: E, what: String): E {
        val raw = optionalText(node, field, what) ?: return default
        return enumValues<E>().firstOrNull { it.name == raw }
            ?: throw MailContractViolationException("$what: field '$field' has unknown value '$raw'")
    }
}
