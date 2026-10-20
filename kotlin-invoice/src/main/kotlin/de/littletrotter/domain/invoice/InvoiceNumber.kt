package de.littletrotter.domain.invoice

import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.masterdata.InvoicePrefixRule
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * Value Object representing a validated invoice number.
 * Blank value is allowed (represents an unassigned/draft invoice — a draft carries no
 * number at all until it is finalized, so a whole invoice life cycle now spends most of its time
 * in this state, not just the instant before [Invoice.invoiceNumber] is first set).
 *
 * Three accepted shapes, oldest first — a later one is never a replacement for an earlier one,
 * every existing number must keep parsing:
 *  - `LEGACY_PATTERN` — `PREFIX-YYYY-NNNN`, the original per-sender/per-year format.
 *  - `NEW_PATTERN` — `PREFIX-YYYY-MM-NNNNN`, per-sender/per-month, fixed five-digit counter.
 *  - `DAILY_PATTERN` — `PREFIX-YYYY-MM-DD-NNNNN…`, the counter is `\d{5,}`
 *    (**open-ended**, not `\d{5}`): the lückenlos-Zähler behind this format has no per-period
 *    reset ceiling by design (N1-04) and must be allowed to grow past five digits instead of
 *    ever truncating — truncation would mean issuing the same number twice.
 */
data class InvoiceNumber(val value: String) {

    init {
        if (value.isNotBlank() && !isValidInvoiceNumber(value)) {
            throw InvoiceValidationException(InvoiceValidationError.INVOICE_NUMBER_INVALID_FORMAT, message = "Rechnungsnummer hat ungültiges Format: $value")
        }
        // IMP-5 — a second validation was removed: it was provably unreachable. All three patterns
        // already bind the prefix to InvoicePrefixRule.PREFIX_REGEX_SOURCE (max 20 chars). A value that passes
        // format check cannot have a longer prefix.
    }

    private fun isValidInvoiceNumber(value: String): Boolean {
        // old legacy format: PREFIX-YYYY-XXXX (e.g., DEVEDGE-2026-0001)
        // new format: PREFIX-YYYY-MM-NNNNN (e.g., DEVEDGE-2026-03-00042)
        // daily format: PREFIX-YYYY-MM-DD-NNNNN… (e.g., DEVEDGE-2026-08-21-04712)
        return NEW_PATTERN.matches(value) || LEGACY_PATTERN.matches(value) || DAILY_PATTERN.matches(value)
    }

    fun isAssigned(): Boolean = value.isNotBlank()

    override fun toString(): String = value

    companion object {
        val UNASSIGNED = InvoiceNumber("")

        /**
         * Whether a sender profile's configured invoice prefix can ever yield a valid number.
         * Delegates to [InvoicePrefixRule]: trimmed, 1-20 letters/digits/hyphens, first char
         * alphanumeric. A blank prefix is not judged here (it falls back to `RE-`).
         */
        fun isValidPrefix(raw: String): Boolean =
            InvoicePrefixRule.isValid(raw)

        // The prefix part is [InvoicePrefixRule.PREFIX_REGEX_SOURCE] (may contain inner hyphens). The
        // regex is greedy but anchored on the fixed trailing numeric segments (`$` + `\d{n}` groups),
        // so backtracking resolves `H272-0001-2026-0001` to prefix `H272-0001`.
        private const val P = InvoicePrefixRule.PREFIX_REGEX_SOURCE
        private val NEW_PATTERN = Regex("^$P-\\d{4}-\\d{2}-\\d{5}$")
        private val LEGACY_PATTERN = Regex("^$P-\\d{4}-\\d{4}$")
        private val DAILY_PATTERN = Regex("^$P-\\d{4}-\\d{2}-\\d{2}-\\d{5,}$")
    }
}

@Converter(autoApply = true)
class InvoiceNumberConverter : AttributeConverter<InvoiceNumber?, String?> {
    override fun convertToDatabaseColumn(attribute: InvoiceNumber?): String? = attribute?.value
    override fun convertToEntityAttribute(dbData: String?): InvoiceNumber? =
        dbData?.let { InvoiceNumber(it) }
}
