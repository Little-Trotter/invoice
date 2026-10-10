package de.littletrotter.ui.format

import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Formats monetary amounts as a German-formatted number with exactly two decimals, a plain
 * space, then the ISO 4217 currency code (task R5, PO decision: money-currency-display) — e.g.
 * `1.234,56 EUR`, `12,00 CHF`, `-45,90 GBP`.
 *
 * This replaced `NumberFormat.getCurrencyInstance(Locale.GERMANY)`, which always printed the
 * currency **glyph** (`€`) regardless of the amount's real currency — wrong for a non-EUR invoice
 * — and separated the amount from it with a non-breaking space, which made string assertions
 * brittle. Neither problem exists here: the ISO code is a literal, and the separator is an
 * ordinary space, so the whole format is fixed and no longer depends on the runtime locale (the
 * locale parameter only controls the amount's own digit grouping/decimal separators, always
 * German-formatted by the default).
 *
 * `currency` defaults to [Currency.EUR]: master data (an article's list price, a per-diem or
 * mileage reference rate) has no invoice and no currency of its own, so a master-data call site
 * reads cleanly as `format(money)` instead of inventing a currency it doesn't have. An invoice
 * call site still passes the invoice's own [Currency] explicitly — the default is a convenience
 * for callers with nothing to pass, never licence for one that has a real currency to stop
 * passing it.
 */
object MoneyFormatter {

    private const val PATTERN = "#,##0.00"

    fun format(amount: BigDecimal?, currency: Currency = Currency.EUR, locale: Locale = Locale.GERMANY): String {
        val number = DecimalFormat(PATTERN, DecimalFormatSymbols.getInstance(locale))
            .format(amount ?: BigDecimal.ZERO)
        return "$number ${currency.code}"
    }

    fun format(money: Money?, currency: Currency = Currency.EUR, locale: Locale = Locale.GERMANY): String =
        format(money?.amount, currency, locale)
}
