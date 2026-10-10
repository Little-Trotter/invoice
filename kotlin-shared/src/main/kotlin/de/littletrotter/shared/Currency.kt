package de.littletrotter.shared

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider

/**
 * The currency an invoice is billed in.
 *
 * Kept deliberately separate from [Money] — the value object stays currency-agnostic,
 * the currency lives once on the invoice header. One currency per invoice, no mixing across items
 * (product decision).
 *
 * **Persistence (project-wide convention):** this enum is persisted as its
 * **Integer ordinal** via `OrdinalEnumConverter`, exactly like `InvoiceStatus`/`InvoiceItemUnit`
 * and every other DB enum in the project — never as a VARCHAR code. The [code] (ISO 4217
 * alphabetic) is used only as the NATS wire format between processes and as
 * `ram:InvoiceCurrencyCode` in the ZUGFeRD export. Consequently the enum is **additively
 * extensible only at the end of the `entries` list**: reordering changes the currency of every
 * existing row, and a new constant at the end needs no schema change (the DB CHECK is
 * `0 <= value <= max`).
 */
enum class Currency(val code: String) {
    EUR("EUR"),
    CHF("CHF"),
    GBP("GBP"),
    USD("USD"),
    NOK("NOK"),
    SEK("SEK");

    companion object {
        /**
         * Resolves the persisted ordinal to a [Currency], defaulting to [EUR] for `null` or an
         * out-of-range value — the same fallback every consuming side (NATS wire, ZUGFeRD export,
         * printed reports) already applied ad hoc before this became the one shared derivation.
         */
        fun ofOrdinal(ordinal: Int?): Currency = entries.getOrNull(ordinal ?: EUR.ordinal) ?: EUR
    }
}

/**
 * Writes [Currency] as its ordinal `Int`, matching the wire shape a plain `Int?` field had
 * before [de.littletrotter.shared.InvoiceTotalsDto.currency] became typed: this is the one place
 * that range/`null` fallback (see [Currency.ofOrdinal]) now lives, instead of every consumer of an
 * already-materialized [de.littletrotter.shared.InvoiceDto] repeating it defensively on a value that,
 * past this boundary, can never actually be invalid.
 */
class CurrencyOrdinalSerializer : JsonSerializer<Currency>() {
    override fun serialize(value: Currency, gen: JsonGenerator, serializers: SerializerProvider) {
        gen.writeNumber(value.ordinal)
    }
}

/** Counterpart to [CurrencyOrdinalSerializer] — see its KDoc. [getNullValue] covers an explicit
 *  JSON `null` (a legacy producer that never wrote the field at all is covered by the Kotlin
 *  default parameter instead, same as any other optional wire field in this module). */
class CurrencyOrdinalDeserializer : JsonDeserializer<Currency>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Currency = Currency.ofOrdinal(p.intValue)

    override fun getNullValue(ctxt: DeserializationContext): Currency = Currency.EUR
}
