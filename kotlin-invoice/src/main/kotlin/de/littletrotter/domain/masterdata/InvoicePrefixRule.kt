package de.littletrotter.domain.masterdata

/**
 * Rule for a sender profile's configured invoice prefix. After `trim()` it must be 1-20 characters
 * (DB `VARCHAR(20)`): letters, digits and hyphens, first character alphanumeric (inner and trailing
 * hyphens allowed, e.g. `DSC-00001`, `RR-CD-`). Rejects hostile values (`${'$'}{7*7}`,
 * `../../etc/passwd`, `Feld 1`) at profile-save time. A blank prefix is not judged here (it falls
 * back to `RE-`).
 *
 * Single source of truth: [PREFIX_REGEX_SOURCE] is also embedded by every invoice-number pattern in
 * [de.littletrotter.domain.invoice.InvoiceNumber], so profile validation and number validation
 * cannot drift apart.
 *
 * Trailing hyphen: the rule accepts it (`RR-CD-`) and so do the number patterns' prefix part, but
 * `InvoiceNumberService` strips trailing hyphens before composing (`trimEnd('-')`) because
 * `NumberFormatter.compose` joins segments with `-`; `RR-CD-` therefore yields `RR-CD-2026-03-00001`.
 */
object InvoicePrefixRule {
    /** Regex source (no anchors, no groups) of a valid prefix after `trim()`. */
    const val PREFIX_REGEX_SOURCE = "[A-Za-z0-9][A-Za-z0-9-]{0,19}"

    private val PREFIX_PATTERN = Regex("^$PREFIX_REGEX_SOURCE$")

    fun isValid(raw: String): Boolean =
        raw.isBlank() || PREFIX_PATTERN.matches(raw.trim())
}
