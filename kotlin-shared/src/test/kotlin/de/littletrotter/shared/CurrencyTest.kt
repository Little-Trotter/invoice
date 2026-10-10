package de.littletrotter.shared

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.assertj.core.api.Assertions.assertThat

/**
 * Tests for Currency enum.
 */
class CurrencyTest {

    @Test
    fun `Currency EUR has correct code`() {
        assertEquals("EUR", Currency.EUR.code)
    }

    @Test
    fun `Currency CHF has correct code`() {
        assertEquals("CHF", Currency.CHF.code)
    }

    @Test
    fun `Currency GBP has correct code`() {
        assertEquals("GBP", Currency.GBP.code)
    }

    @Test
    fun `Currency USD has correct code`() {
        assertEquals("USD", Currency.USD.code)
    }

    @Test
    fun `Currency NOK has correct code`() {
        assertEquals("NOK", Currency.NOK.code)
    }

    @Test
    fun `Currency SEK has correct code`() {
        assertEquals("SEK", Currency.SEK.code)
    }

    @Test
    fun `Currency enum has six entries`() {
        val entries = Currency.entries
        assertThat(entries).hasSize(6)
    }

    @Test
    fun `Currency enum contains all expected values`() {
        assertThat(Currency.entries).containsExactlyInAnyOrder(
            Currency.EUR, Currency.CHF, Currency.GBP,
            Currency.USD, Currency.NOK, Currency.SEK
        )
    }

    @Test
    fun `Currency valueOf works for all entries`() {
        assertEquals(Currency.EUR, Currency.valueOf("EUR"))
        assertEquals(Currency.CHF, Currency.valueOf("CHF"))
        assertEquals(Currency.GBP, Currency.valueOf("GBP"))
        assertEquals(Currency.USD, Currency.valueOf("USD"))
        assertEquals(Currency.NOK, Currency.valueOf("NOK"))
        assertEquals(Currency.SEK, Currency.valueOf("SEK"))
    }

    @Test
    fun `Currency ordinal is stable`() {
        // Ordinal is used for persistence, so stability is critical
        assertEquals(0, Currency.EUR.ordinal)
        assertEquals(1, Currency.CHF.ordinal)
        assertEquals(2, Currency.GBP.ordinal)
        assertEquals(3, Currency.USD.ordinal)
        assertEquals(4, Currency.NOK.ordinal)
        assertEquals(5, Currency.SEK.ordinal)
    }

    @Test
    fun `Currency name property returns enum name`() {
        assertEquals("EUR", Currency.EUR.name)
        assertEquals("CHF", Currency.CHF.name)
        assertEquals("GBP", Currency.GBP.name)
        assertEquals("USD", Currency.USD.name)
        assertEquals("NOK", Currency.NOK.name)
        assertEquals("SEK", Currency.SEK.name)
    }

    @Test
    fun `Currency codes are ISO 4217 alphabetic`() {
        // All currency codes should be exactly 3 alphabetic characters (ISO 4217)
        for (currency in Currency.entries) {
            assertThat(currency.code)
                .hasSize(3)
                .matches("[A-Z]{3}")
        }
    }

    // ── ofOrdinal, the one shared ordinal-to-currency derivation ──

    @Test
    fun `ofOrdinal resolves every persisted ordinal to its currency`() {
        assertEquals(Currency.EUR, Currency.ofOrdinal(0))
        assertEquals(Currency.CHF, Currency.ofOrdinal(1))
        assertEquals(Currency.GBP, Currency.ofOrdinal(2))
        assertEquals(Currency.USD, Currency.ofOrdinal(3))
        assertEquals(Currency.NOK, Currency.ofOrdinal(4))
        assertEquals(Currency.SEK, Currency.ofOrdinal(5))
    }

    @Test
    fun `ofOrdinal falls back to EUR for a null ordinal`() {
        assertEquals(Currency.EUR, Currency.ofOrdinal(null))
    }

    @Test
    fun `ofOrdinal falls back to EUR for an out-of-range ordinal`() {
        assertEquals(Currency.EUR, Currency.ofOrdinal(99))
    }

    @Test
    fun `ofOrdinal falls back to EUR for a negative ordinal`() {
        assertEquals(Currency.EUR, Currency.ofOrdinal(-1))
    }

    @Test
    fun `Currency codes match ISO 4217 standard`() {
        // EUR - Euro
        assertEquals("EUR", Currency.EUR.code)
        // CHF - Swiss Franc
        assertEquals("CHF", Currency.CHF.code)
        // GBP - British Pound
        assertEquals("GBP", Currency.GBP.code)
        // USD - US Dollar
        assertEquals("USD", Currency.USD.code)
        // NOK - Norwegian Krone
        assertEquals("NOK", Currency.NOK.code)
        // SEK - Swedish Krona
        assertEquals("SEK", Currency.SEK.code)
    }

    // ── InvoiceTotalsDto.currency wire (de)serialization: the range/null fallback that
    //    used to be re-checked by every consumer of an already-materialized InvoiceDto now lives
    //    only here, at the actual wire boundary (CurrencyOrdinalSerializer/-Deserializer). ──

    @Test
    fun `InvoiceTotalsDto currency serializes as a plain ordinal Int, byte-identical to the former Int field`() {
        val mapper = jacksonObjectMapper()
        val json = mapper.writeValueAsString(InvoiceTotalsDto(currency = Currency.CHF))
        assertThat(json).contains("\"currency\":1")
    }

    @Test
    fun `InvoiceTotalsDto currency deserializes a valid ordinal back to its Currency`() {
        val mapper = jacksonObjectMapper()
        val read: InvoiceTotalsDto = mapper.readValue("""{"currency":2}""")
        assertEquals(Currency.GBP, read.currency)
    }

    @Test
    fun `InvoiceTotalsDto currency falls back to EUR for an explicit JSON null`() {
        val mapper = jacksonObjectMapper()
        val read: InvoiceTotalsDto = mapper.readValue("""{"currency":null}""")
        assertEquals(Currency.EUR, read.currency)
    }

    @Test
    fun `InvoiceTotalsDto currency falls back to EUR for an out-of-range ordinal`() {
        val mapper = jacksonObjectMapper()
        val read: InvoiceTotalsDto = mapper.readValue("""{"currency":999}""")
        assertEquals(Currency.EUR, read.currency)
    }

    @Test
    fun `InvoiceTotalsDto currency defaults to EUR when the field is absent (legacy payload)`() {
        val mapper = jacksonObjectMapper()
        val read: InvoiceTotalsDto = mapper.readValue("""{}""")
        assertEquals(Currency.EUR, read.currency)
    }
}
