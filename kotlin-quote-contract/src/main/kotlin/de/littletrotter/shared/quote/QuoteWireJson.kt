package de.littletrotter.shared.quote

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID
import com.fasterxml.jackson.module.kotlin.KotlinModule
import de.littletrotter.shared.Money

/**
 * The one mapper behind [QuoteWireCodec]. `internal`: an implementation detail of the contract,
 * never seen by host or component.
 *
 * The Kotlin module lets constructor defaults fill absent fields, the time module writes dates and
 * instants as ISO-8601 text, and unknown fields are ignored so a newer writer never breaks an
 * older reader. A field without a default that is absent fails the read: a body that lacks a
 * required value must never decode to an object with a zero or empty stand-in.
 */
internal object QuoteWireJson {
    val mapper: ObjectMapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .registerModule(JavaTimeModule())
        .registerModule(moneyAsPlainNumber())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, true)
        .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)
        .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)

    /** An amount travels as a bare decimal number, as it did before the views were typed [Money]. */
    private fun moneyAsPlainNumber(): SimpleModule = SimpleModule()
        .addSerializer(
            Money::class.java,
            object : JsonSerializer<Money>() {
                override fun serialize(value: Money, gen: JsonGenerator, serializers: SerializerProvider) =
                    gen.writeNumber(value.amount)
            },
        )
        .addDeserializer(
            Money::class.java,
            object : JsonDeserializer<Money>() {
                override fun deserialize(parser: JsonParser, context: DeserializationContext): Money =
                    Money.of(parser.decimalValue)
            },
        )

    /** Parses [bytes] into an object node; blank, invalid or non-object payloads are violations. */
    fun readObject(bytes: ByteArray?, what: String): ObjectNode {
        if (bytes == null || bytes.isEmpty()) throw QuoteContractViolationException("$what: payload is blank")
        val parsed = try {
            mapper.readTree(bytes)
        } catch (e: JacksonException) {
            throw QuoteContractViolationException("$what: payload is not valid JSON", e)
        }
        if (parsed !is ObjectNode) throw QuoteContractViolationException("$what: payload is not a JSON object")
        return parsed
    }

    /** A present, non-blank textual field; anything else throws. */
    fun requiredText(node: ObjectNode, field: String, what: String): String {
        val value: JsonNode? = node.get(field)
        if (value == null || !value.isTextual || value.asText().isBlank()) {
            throw QuoteContractViolationException("$what: required field '$field' is missing or blank")
        }
        return value.asText()
    }

    /** A textual field that may be absent or JSON null; blank text is still a violation. */
    fun optionalText(node: ObjectNode, field: String, what: String): String? {
        val value = node.get(field)
        if (value == null || value.isNull) return null
        return requiredText(node, field, what)
    }

    /** A date field that may be absent or JSON null; anything else must parse as ISO-8601 date. */
    fun optionalDate(node: ObjectNode, field: String, what: String): LocalDate? {
        val value = node.get(field)
        if (value == null || value.isNull) return null
        return requiredDate(node, field, what)
    }

    /** A present integral field; a missing number never defaults to zero. */
    fun requiredLong(node: ObjectNode, field: String, what: String): Long {
        val value = node.get(field)
        if (value == null || !value.isIntegralNumber) {
            throw QuoteContractViolationException("$what: required field '$field' is missing or not an integer")
        }
        return value.asLong()
    }

    /** A required text field parsed as UUID. */
    fun requiredUuid(node: ObjectNode, field: String, what: String): UUID =
        parse(node, field, what) { UUID.fromString(it) }

    /** A required text field parsed as ISO-8601 instant. */
    fun requiredInstant(node: ObjectNode, field: String, what: String): Instant =
        parse(node, field, what) { Instant.parse(it) }

    /** A required text field parsed as ISO-8601 date. */
    fun requiredDate(node: ObjectNode, field: String, what: String): LocalDate =
        parse(node, field, what) { LocalDate.parse(it) }

    private fun <T> parse(node: ObjectNode, field: String, what: String, parser: (String) -> T): T {
        val raw = requiredText(node, field, what)
        return try {
            parser(raw)
        } catch (e: DateTimeParseException) {
            throw QuoteContractViolationException("$what: field '$field' is malformed: '$raw'", e)
        } catch (e: IllegalArgumentException) {
            throw QuoteContractViolationException("$what: field '$field' is malformed: '$raw'", e)
        }
    }

    /** Writes the fields every offer event starts with, in wire order. */
    fun putEventHead(root: ObjectNode, head: QuoteEventHead) {
        root.put("schemaVersion", head.schemaVersion)
        root.put("eventId", head.eventId.toString())
        root.put("quoteId", head.quoteId.toString())
        root.put("quoteNumber", head.quoteNumber)
    }

    /** Reads the shared event head; every field is required. */
    fun readEventHead(root: ObjectNode, what: String): QuoteEventHead = QuoteEventHead(
        schemaVersion = requiredLong(root, "schemaVersion", what).toInt(),
        eventId = requiredUuid(root, "eventId", what),
        quoteId = requiredUuid(root, "quoteId", what),
        quoteNumber = requiredText(root, "quoteNumber", what),
    )
}

/** What every offer event names first: contract version, event identity and the offer it concerns. */
internal data class QuoteEventHead(
    val schemaVersion: Int,
    val eventId: UUID,
    val quoteId: UUID,
    val quoteNumber: String,
)
