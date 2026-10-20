package de.littletrotter.shared.mail

import de.littletrotter.shared.Money
import java.time.LocalDate

/**
 * The value type a placeholder carries on the wire. Same three kinds, and the same three names, the
 * mail component's own `PlaceholderValueType` uses — the constants are the wire tokens, so they
 * must stay spelled exactly like this.
 *
 * Direction: host → component (inside [MailComposeRequest]).
 * Owner: this contract.
 */
enum class MailPlaceholderValueType {
    STRING,
    DATE,
    MONEY,
}

/**
 * One typed placeholder value, keyed in [MailComposeRequest.placeholderValues] by the placeholder's
 * template key (the literal name written inside `{{ }}`, e.g. `invoiceNumber`, `grossTotal`).
 *
 * Direction: host → component.
 * Owner: this contract.
 *
 * **Typed, and the type travels with the value.** The mail component's REST API takes placeholders
 * as a flat `Map<String, String?>` and decides each entry's type from a server-side, per-placeholder
 * table (`PlaceholderValueTypes`). That works inside one context, but it cannot be the *contract*:
 * it would force the host to know the component's `Placeholder` whitelist and its type table in
 * order to serialize correctly, and a new placeholder in the component would silently misparse
 * host input until the host was redeployed with a copy of the new table. Carrying
 * [MailPlaceholderValueType] alongside the value makes the payload self-describing, so neither
 * side needs the other's table. The key stays a plain [String] for the same reason: the contract
 * must not depend on the component's `Placeholder` enum.
 *
 * [MailPlaceholderWireCodec] is the only place these values are turned into text and back — see its
 * KDoc for why exactly one codec exists rather than one per side.
 */
sealed interface MailPlaceholderValue {

    /** The wire type tag written next to [wireValue]. */
    val type: MailPlaceholderValueType

    /**
     * The value's textual wire form — ISO-8601 for [Date], a plain decimal at [Money.SCALE] for
     * [Amount], the string itself for [Text]. Identical to what each value's own `toString()`
     * produces, which is what makes [MailPlaceholderWireCodec.flatten] a pure re-keying rather than
     * a second, drift-prone encoding.
     */
    val wireValue: String

    /** A plain string placeholder — invoice number, recipient/sender name, IBAN. */
    data class Text(val value: String) : MailPlaceholderValue {
        override val type: MailPlaceholderValueType get() = MailPlaceholderValueType.STRING
        override val wireValue: String get() = value
    }

    /** A calendar date — invoice date, due date. ISO-8601 on the wire, never a locale format. */
    data class Date(val value: LocalDate) : MailPlaceholderValue {
        override val type: MailPlaceholderValueType get() = MailPlaceholderValueType.DATE
        override val wireValue: String get() = value.toString()
    }

    /**
     * A monetary amount — the gross total. Carried as [Money] rather than a raw `BigDecimal`, so
     * the project's one rounding rule applies here too (and `MoneyArchitectureTest` keeps it that
     * way). The wire form has exactly [Money.SCALE] decimals, so a value built with `Money.exact`
     * arrives rounded; a placeholder is a displayed, final amount, never an intermediate.
     */
    data class Amount(val value: Money) : MailPlaceholderValue {
        override val type: MailPlaceholderValueType get() = MailPlaceholderValueType.MONEY
        override val wireValue: String get() = value.toString()
    }
}
