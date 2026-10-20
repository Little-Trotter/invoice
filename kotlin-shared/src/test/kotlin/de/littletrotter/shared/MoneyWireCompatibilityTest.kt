package de.littletrotter.shared

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Proves the claim the product-owner instruction made and required proof for: removing the old
 * `MoneyDto(val amount: BigDecimal)` in favor of putting [Money] directly on `InvoiceDto`'s wire
 * shape is safe because both classes serialize identically — a single JSON object with exactly
 * one field, `amount`.
 *
 * [LegacyMoneyDto] below reconstructs the deleted class's exact shape so this test compares two
 * real serializations against each other (via [jacksonObjectMapper]'s real `writeValueAsString`,
 * the same call NATS publishing uses) instead of a hand-typed "expected" JSON string. That
 * matters: Jackson's own `BigDecimal` number formatting has quirks unrelated to Money (trailing
 * zeros, `ObjectMapper.valueToTree`'s `TokenBuffer` round trip can silently re-scale a value) that
 * would make a hardcoded expected string an assumption instead of a proof. Building the same
 * value into both shapes and diffing the actual wire bytes sidesteps all of that.
 *
 * This test caught a real gap during the migration: [Money]'s [Money.isNegative]/
 * [Money.isPositive]/[Money.isZero] are parameterless no-arg methods, which Jackson's default
 * bean introspection treats as extra JSON properties on *serialization* — `@JsonIgnoreProperties
 * (ignoreUnknown = true)` alone only suppresses them on *deserialization*. Money now carries an
 * explicit `@JsonIgnore` on each of the three (see Money.kt) so this test passes for real, not by
 * accident.
 */
class MoneyWireCompatibilityTest {

    private val mapper = jacksonObjectMapper()

    /** The pre-migration `MoneyDto`'s exact shape, reconstructed here only for this comparison —
     *  a plain one-field data class, nothing else. */
    private data class LegacyMoneyDto(val amount: BigDecimal)

    private data class LegacyInvoiceItemLike(
        val positionId: String,
        val description: String,
        val quantity: BigDecimal,
        val unitPriceNet: LegacyMoneyDto,
        val netAmount: LegacyMoneyDto,
        val vatAmount: LegacyMoneyDto,
    )

    private data class LegacyInvoiceLike(
        val id: Long,
        val invoiceNumber: InvoiceNumberDto,
        val items: List<LegacyInvoiceItemLike>,
        val netAmount: LegacyMoneyDto,
        val vatAmount: LegacyMoneyDto,
        val grossAmount: LegacyMoneyDto,
    )

    @Test
    fun `Money serializes byte-identically to the old MoneyDto`() {
        val amount = BigDecimal("1234.56")

        val legacyJson = mapper.writeValueAsString(LegacyMoneyDto(amount))
        val moneyJson = mapper.writeValueAsString(Money(amount))

        assertEquals(legacyJson, moneyJson,
            "Money must produce byte-identical JSON to the deleted MoneyDto for the same amount")
        assertEquals("""{"amount":1234.56}""", moneyJson,
            "and that shape must be exactly one field, amount — no isNegative/isPositive/isZero")
    }

    @Test
    fun `InvoiceDto's money fields are byte-identical to the pre-migration MoneyDto wire format`() {
        val legacy = LegacyInvoiceLike(
            id = 42L,
            invoiceNumber = InvoiceNumberDto("INV-42"),
            items = listOf(
                LegacyInvoiceItemLike(
                    positionId = "1",
                    description = "Beratung",
                    quantity = BigDecimal("3"),
                    unitPriceNet = LegacyMoneyDto(BigDecimal("100.00")),
                    netAmount = LegacyMoneyDto(BigDecimal("300.00")),
                    vatAmount = LegacyMoneyDto(BigDecimal("57.00")),
                ),
            ),
            netAmount = LegacyMoneyDto(BigDecimal("300.00")),
            vatAmount = LegacyMoneyDto(BigDecimal("57.00")),
            grossAmount = LegacyMoneyDto(BigDecimal("357.00")),
        )
        val current = InvoiceDto(
            id = 42L,
            invoiceNumber = InvoiceNumberDto("INV-42"),
            items = listOf(
                InvoiceItemDto(
                    positionId = "1",
                    description = "Beratung",
                    quantity = BigDecimal("3"),
                    unitPriceNet = Money(BigDecimal("100.00")),
                    netAmount = Money(BigDecimal("300.00")),
                    vatAmount = Money(BigDecimal("57.00")),
                    taxRatePercent = BigDecimal("19.00"),
                ),
            ),
            totals = InvoiceTotalsDto(
                netAmount = Money(BigDecimal("300.00")),
                vatAmount = Money(BigDecimal("57.00")),
                grossAmount = Money(BigDecimal("357.00")),
            ),
        )

        val legacyJson = mapper.writeValueAsString(legacy)
        val currentJson = mapper.writeValueAsString(current)

        // LegacyInvoiceLike/InvoiceDto differ in field count (InvoiceDto has many more optional
        // fields, all defaulted here), so a full-string diff isn't meaningful — but every money
        // field's *own* rendered fragment must match byte for byte.
        assertTrue(currentJson.contains(""""netAmount":{"amount":300.00}"""), currentJson)
        assertTrue(currentJson.contains(""""vatAmount":{"amount":57.00}"""), currentJson)
        assertTrue(currentJson.contains(""""grossAmount":{"amount":357.00}"""), currentJson)
        assertTrue(currentJson.contains(""""unitPriceNet":{"amount":100.00}"""), currentJson)
        assertTrue(legacyJson.contains(""""netAmount":{"amount":300.00}"""), legacyJson)
        assertTrue(legacyJson.contains(""""grossAmount":{"amount":357.00}"""), legacyJson)

        // Round trip: a message produced today deserializes and re-serializes to the exact same
        // JSON string — the plain constructor (not Money.of) does not round, so scale survives
        // unchanged end to end.
        val readBack: InvoiceDto = mapper.readValue(currentJson)
        assertEquals(currentJson, mapper.writeValueAsString(readBack))
    }

    @Test
    fun `a legacy MoneyDto-shaped payload deserializes into Money unchanged`() {
        // A message as it would have looked coming from a producer that still emitted MoneyDto —
        // proves a mixed-version rollout (old producer, new consumer) is safe during deploy.
        val legacyJson = """
            {"id":1,"invoiceNumber":{"value":"INV-1"},"items":[],
             "totals":{"netAmount":{"amount":10.00},"vatAmount":{"amount":1.90},"grossAmount":{"amount":11.90}}}
        """.trimIndent()

        val invoice: InvoiceDto = mapper.readValue(legacyJson)

        assertTrue(Money(BigDecimal("10.00")).sameValueAs(invoice.totals.netAmount))
        assertTrue(Money(BigDecimal("1.90")).sameValueAs(invoice.totals.vatAmount))
        assertTrue(Money(BigDecimal("11.90")).sameValueAs(invoice.totals.grossAmount))
    }
}
