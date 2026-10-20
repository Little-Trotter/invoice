package de.littletrotter.application.masterdata.port.outbound

import de.littletrotter.application.dto.VatIdVerificationResult

/**
 * Port for VAT ID verification against the EU's VIES REST API.
 *
 * Master-data level (decision 9): triggered only on explicit user request (button),
 * never automatic, never part of document validation, never print-blocking.
 *
 * State rules:
 * - API answers `isValid: true` → result is [VatIdVerificationResult.VERIFIED]
 * - API answers `isValid: false` with `userError: INVALID` → result is [VatIdVerificationResult.FAILED]
 * - API unreachable / timeout / 5xx / any other `userError` → result is
 *   [VatIdVerificationResult.UNREACHABLE] (unreachability is not a statement about the VAT ID;
 *   the status remains unchanged)
 *
 * Format validation runs before the API call: a formally invalid VAT ID never reaches
 * the HTTP layer. Implementations MAY raise IllegalArgumentException for formally invalid
 * input but are not required to (the caller is responsible for format validation).
 */
fun interface VatIdVerificationPort {

    /**
     * Verify a VAT ID against the EU VIES registry.
     *
     * @param countryCode the ISO-3166-1 alpha-2 country code (e.g., "DE", "FR")
     * @param vatId the VAT ID without country code (e.g., "123456789", not "DE123456789")
     * @return verification result
     */
    fun verify(countryCode: String, vatId: String): VatIdVerificationResult
}
