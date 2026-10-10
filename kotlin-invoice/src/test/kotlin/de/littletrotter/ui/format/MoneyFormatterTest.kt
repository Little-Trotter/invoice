package de.littletrotter.ui.format

import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Locale

/**
 * R5 (PO decision: money-currency-display) — [MoneyFormatter] now renders a German-formatted
 * number with exactly two decimals, a plain space, then the ISO currency code (`1.234,56 EUR`),
 * never the currency glyph. The format is fixed rather than locale-dependent, so every expectation
 * here is a literal string — that would not have been reliable against the old
 * `NumberFormat.getCurrencyInstance` output, which separated the glyph with a non-breaking space.
 */
class MoneyFormatterTest {

    @Test
    fun `a null BigDecimal amount formats as zero euros`() {
        val text = MoneyFormatter.format(null as BigDecimal?, Currency.EUR, Locale.GERMANY)

        assertEquals("0,00 EUR", text)
    }

    @Test
    fun `a non-null BigDecimal amount formats with the given currency's ISO code, not its glyph`() {
        val text = MoneyFormatter.format(BigDecimal("42.50"), Currency.USD, Locale.GERMANY)

        assertEquals("42,50 USD", text)
    }

    @Test
    fun `a null Money formats as zero euros`() {
        val text = MoneyFormatter.format(null as Money?, Currency.EUR, Locale.GERMANY)

        assertEquals("0,00 EUR", text)
    }

    @Test
    fun `a non-null Money formats using its amount`() {
        val money = Money.of(BigDecimal("12.34"))

        val text = MoneyFormatter.format(money, Currency.EUR, Locale.GERMANY)

        assertEquals("12,34 EUR", text)
    }

    @Test
    fun `a four-digit amount gets the German thousands separator`() {
        val text = MoneyFormatter.format(Money.of(BigDecimal("1234.56")), Currency.EUR)

        assertEquals("1.234,56 EUR", text)
    }

    @Test
    fun `a Swiss-franc amount renders with the ISO code, never the euro glyph`() {
        val text = MoneyFormatter.format(Money.of(BigDecimal("12.00")), Currency.CHF)

        assertEquals("12,00 CHF", text)
        assertFalse(text.contains("€"), "expected no euro glyph in: $text")
    }

    @Test
    fun `a negative amount keeps its sign and two decimals`() {
        val text = MoneyFormatter.format(Money.of(BigDecimal("-45.90")), Currency.GBP)

        assertEquals("-45,90 GBP", text)
    }

    @Test
    fun `a whole amount is not shortened by trimming trailing zeros`() {
        val text = MoneyFormatter.format(Money.of(BigDecimal("12.00")), Currency.CHF)

        assertEquals("12,00 CHF", text)
    }

    @Test
    fun `an amount without a currency renders as euro by default`() {
        val text = MoneyFormatter.format(Money.of(BigDecimal("5.00")))

        assertEquals("5,00 EUR", text)
    }

    @Test
    fun `NOK and SEK amounts render with their own ISO code`() {
        assertEquals("1,00 NOK", MoneyFormatter.format(Money.of(BigDecimal("1.00")), Currency.NOK))
        assertEquals("1,00 SEK", MoneyFormatter.format(Money.of(BigDecimal("1.00")), Currency.SEK))
    }
}
