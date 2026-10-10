package de.littletrotter.domain.compliance

import de.littletrotter.shared.tax.VatIdVerificationStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/** The VIES port outcome is distinguishable from, and never silently promoted to,
 *  a verified VAT id. */
class VatIdVerificationTest {

    @Test
    fun `R5-T13 - VIES reports invalid maps to FAILED, not VERIFIED`() {
        val result = VatIdCheckResult(VatIdCheckOutcome.INVALID, OffsetDateTime.now(), "VIES: ungültig")
        assertEquals(VatIdVerificationStatus.FAILED, result.toVerificationStatus())
        assertFalse(result.isTechnicalWarning)
    }

    @Test
    fun `R5-T14 - VIES unavailable maps to UNVERIFIED, never VERIFIED, and is flagged as a technical warning`() {
        val result = VatIdCheckResult(VatIdCheckOutcome.UNAVAILABLE, OffsetDateTime.now(), "VIES: nicht erreichbar")
        assertEquals(VatIdVerificationStatus.UNVERIFIED, result.toVerificationStatus())
        assertNotEquals(VatIdVerificationStatus.VERIFIED, result.toVerificationStatus())
        assertTrue(result.isTechnicalWarning)
    }

    @Test
    fun `a valid VIES result maps to VERIFIED and carries no technical warning`() {
        val result = VatIdCheckResult(VatIdCheckOutcome.VALID, OffsetDateTime.now())
        assertEquals(VatIdVerificationStatus.VERIFIED, result.toVerificationStatus())
        assertFalse(result.isTechnicalWarning)
    }

    @Test
    fun `DisabledVatIdVerificationPort always reports UNAVAILABLE`() {
        val port: VatIdVerificationPort = DisabledVatIdVerificationPort()
        val result = port.check("FR", "FR12345678901", OffsetDateTime.now())
        assertEquals(VatIdCheckOutcome.UNAVAILABLE, result.outcome)
        assertEquals(VatIdVerificationStatus.UNVERIFIED, result.toVerificationStatus())
    }
}
