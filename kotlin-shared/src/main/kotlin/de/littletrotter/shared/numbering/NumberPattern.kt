package de.littletrotter.shared.numbering

/**
 * The shape of a number without its date and counter value: optional prefix, how much of the date
 * is embedded, and the zero-padded width of the counter.
 */
data class NumberPattern(val prefix: String?, val datePart: DatePart, val counterWidth: Int)
