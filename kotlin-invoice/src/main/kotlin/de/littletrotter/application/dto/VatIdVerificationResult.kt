package de.littletrotter.application.dto

/**
 * Result of a VIES verification attempt.
 */
sealed class VatIdVerificationResult {
    /**
     * VIES confirmed the VAT ID is valid.
     *
     * @param name the registered trader name VIES returned, `null` when VIES did not deliver one
     *   (a placeholder such as "---" or a blank value also normalises to `null`)
     * @param address the registered trader address VIES returned, `null` under the same rule
     */
    data class VERIFIED(val name: String?, val address: String?) : VatIdVerificationResult()

    /**
     * VIES confirmed the VAT ID is invalid (`userError: INVALID`).
     */
    object FAILED : VatIdVerificationResult()

    /**
     * VIES is unreachable, or answered with anything other than a clear valid/invalid verdict
     * (timeout, 5xx, no HTTP response, an unreadable body, or a `userError` other than `INVALID`
     * — `MS_UNAVAILABLE`, `TIMEOUT`, `MS_MAX_CONCURRENT_REQ`, …). The status remains unchanged;
     * unreachability is not a statement about the VAT ID.
     *
     * @param reason the VIES `userError` code that caused this, `null` when the cause was a
     *   technical failure (timeout, HTTP error, unparseable body) rather than a `userError`
     */
    data class UNREACHABLE(val reason: String?) : VatIdVerificationResult()
}
