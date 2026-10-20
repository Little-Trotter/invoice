package de.littletrotter.domain.compliance

import de.littletrotter.shared.tax.VatIdVerificationStatus
import java.time.OffsetDateTime

// ── VIES port ────────────────────────────────────────────────────────────────

enum class VatIdCheckOutcome { VALID, INVALID, UNAVAILABLE }

data class VatIdCheckResult(
    val outcome: VatIdCheckOutcome,
    val checkedAt: OffsetDateTime,
    val message: String? = null,
) {
    /** R5 "wird nie als gültig gespeichert": [VatIdCheckOutcome.UNAVAILABLE] maps to
     *  [VatIdVerificationStatus.UNVERIFIED], never [VatIdVerificationStatus.VERIFIED]. */
    fun toVerificationStatus(): VatIdVerificationStatus = when (outcome) {
        VatIdCheckOutcome.VALID -> VatIdVerificationStatus.VERIFIED
        VatIdCheckOutcome.INVALID -> VatIdVerificationStatus.FAILED
        VatIdCheckOutcome.UNAVAILABLE -> VatIdVerificationStatus.UNVERIFIED
    }

    /** Whether this result should surface as a technical warning (checked but inconclusive)
     *  rather than a definitive yes/no — distinguishes "VIES said no" from "VIES didn't answer". */
    val isTechnicalWarning: Boolean get() = outcome == VatIdCheckOutcome.UNAVAILABLE
}
