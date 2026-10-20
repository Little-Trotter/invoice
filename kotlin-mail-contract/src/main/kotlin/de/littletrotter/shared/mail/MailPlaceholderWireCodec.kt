package de.littletrotter.shared.mail

import com.fasterxml.jackson.databind.node.ObjectNode
import de.littletrotter.shared.Money
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The one codec for typed placeholder values — the wire-shape half of this package's behaviour
 * ([MailPlaceholderCatalogue] is the other, deciding which keys and types are legal per dispatch
 * kind; this object never asks that question, only encodes and decodes whatever it is given).
 *
 * Direction: encodes on the host, decodes in the component; [parse]/[flatten] are used by both.
 * Owner: this contract.
 *
 * **Why one codec and not one per side.** Before this contract existed the same translation existed twice: the
 * component's `PlaceholderValueCodec.decode` parsed the wire strings into typed values, and
 * `email-ui`'s `PlaceholderWireCodec.encode` produced them — two files in two modules, kept in
 * step only by the fact that all three current value kinds happen to round-trip through
 * `toString()`. The first value kind that does not (a percentage, a quantity with a unit, anything
 * locale-shaped) would break exactly one of the two copies, and the symptom would be a wrong value
 * in a customer's mail rather than a compile error. Both sides use *this* object instead; the
 * component's own two copies are retired separately (its repository is untouched by
 * this change).
 *
 * The wire forms are unchanged from what the component already accepts, so this is a move, not a
 * redesign: [MailPlaceholderValueType.DATE] is `LocalDate.parse`-compatible ISO-8601,
 * [MailPlaceholderValueType.MONEY] is a plain `BigDecimal(String)`-compatible decimal (see
 * [Money.toString]), [MailPlaceholderValueType.STRING] is itself.
 */
object MailPlaceholderWireCodec {

    private const val TYPE_FIELD = "type"
    private const val VALUE_FIELD = "value"

    /**
     * Parses one wire form back into its typed value. The inverse of
     * [MailPlaceholderValue.wireValue], and the exact behaviour of the component's existing
     * `PlaceholderValueCodec.parse` — a malformed date or amount throws
     * [MailContractViolationException] rather than degrading to the raw string, because a mail that
     * renders `{{grossTotal}}` as `"12,34 EUR!"` is worse than one that is never sent.
     */
    fun parse(type: MailPlaceholderValueType, raw: String, what: String = "placeholder"): MailPlaceholderValue =
        try {
            when (type) {
                MailPlaceholderValueType.STRING -> MailPlaceholderValue.Text(raw)
                MailPlaceholderValueType.DATE -> MailPlaceholderValue.Date(LocalDate.parse(raw))
                MailPlaceholderValueType.MONEY -> MailPlaceholderValue.Amount(Money.of(BigDecimal(raw)))
            }
        } catch (e: DateTimeParseException) {
            throw MailContractViolationException("$what: '$raw' is not a valid $type value", e)
        } catch (e: NumberFormatException) {
            throw MailContractViolationException("$what: '$raw' is not a valid $type value", e)
        }

    /**
     * Drops the type tags and keeps insertion order — the flat `Map<String, String>` shape the
     * component's `POST /api/send` body already carries. Nothing is re-encoded here: each entry
     * contributes its own [MailPlaceholderValue.wireValue], so the flat and the typed
     * representation can never disagree about a value.
     */
    fun flatten(values: Map<String, MailPlaceholderValue>): Map<String, String> =
        values.entries.associateTo(LinkedHashMap()) { (key, value) -> key to value.wireValue }

    /**
     * The inverse of [flatten] for a caller that has a flat map plus a type per key — the shape a
     * `GET /api/placeholders` row carries. A key absent from [types] is skipped rather than
     * failing the whole map: an unknown placeholder is the component's business, not the codec's.
     */
    fun expand(
        raw: Map<String, String>,
        types: Map<String, MailPlaceholderValueType>,
    ): Map<String, MailPlaceholderValue> =
        raw.entries.mapNotNull { (key, value) ->
            types[key]?.let { type -> key to parse(type, value, "placeholder '$key'") }
        }.toMap(LinkedHashMap())

    /** Encodes the typed map as its standalone JSON object form (see [decode]). */
    fun encode(values: Map<String, MailPlaceholderValue>): String =
        MailWireJson.mapper.writeValueAsString(toNode(values))

    /** Decodes the JSON object form; every entry must carry both a `type` and a `value`. */
    fun decode(json: String?): Map<String, MailPlaceholderValue> =
        fromNode(MailWireJson.readObject(json, WHAT), WHAT)

    /**
     * `{"invoiceDate": {"type": "DATE", "value": "2026-09-07"}, ...}` — written field by field, so
     * the JSON keys are literals in this file and not Kotlin property names (see [MailWireJson]).
     */
    internal fun toNode(values: Map<String, MailPlaceholderValue>): ObjectNode {
        val root = MailWireJson.mapper.createObjectNode()
        values.forEach { (key, value) ->
            val entry = root.putObject(key)
            entry.put(TYPE_FIELD, value.type.name)
            entry.put(VALUE_FIELD, value.wireValue)
        }
        return root
    }

    internal fun fromNode(node: ObjectNode, what: String): Map<String, MailPlaceholderValue> {
        val values = LinkedHashMap<String, MailPlaceholderValue>()
        node.fieldNames().forEach { key ->
            val entry = MailWireJson.requiredObject(node, key, what)
            val entryWhat = "$what: placeholder '$key'"
            val type = MailWireJson.requiredEnum(
                entry,
                TYPE_FIELD,
                MailPlaceholderValueType.entries.toTypedArray(),
                entryWhat,
            )
            values[key] = parse(type, MailWireJson.requiredText(entry, VALUE_FIELD, entryWhat), entryWhat)
        }
        return values
    }

    private const val WHAT = "placeholderValues"
}
