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

/** R5 "VIES als Port modellieren, damit Tests ohne Netzwerk laufen". No HTTP-backed
 *  implementation ships with R5 — Umsetzung's deployment plan requires rolling out with VIES
 *  switched off first; building and wiring the real client is explicit follow-up work. */
fun interface VatIdVerificationPort {
    fun check(countryCode: String, vatId: String, checkedAt: OffsetDateTime): VatIdCheckResult
}

/** The safe default: every check reports [VatIdCheckOutcome.UNAVAILABLE]. [checkedAt] comes from
 *  the caller's [de.littletrotter.shared.time.TimeSource] — a port implementation may not read
 *  the clock itself. */
class DisabledVatIdVerificationPort : VatIdVerificationPort {
    override fun check(countryCode: String, vatId: String, checkedAt: OffsetDateTime): VatIdCheckResult =
        VatIdCheckResult(VatIdCheckOutcome.UNAVAILABLE, checkedAt, "VIES-Prüfung ist deaktiviert.")
}
