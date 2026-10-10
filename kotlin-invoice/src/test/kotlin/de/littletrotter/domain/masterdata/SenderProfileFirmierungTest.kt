package de.littletrotter.domain.masterdata

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [SenderProfile.showsCommercialRegister]: the only combination that suppresses the
 * commercial-register data on an invoice is [LegalForm.SOLE_PROPRIETOR] together with
 * [SenderProfile.craftsChamberRegistered]. Every other legal form, and every no-legal-form
 * ("keine Angabe") state, keeps showing it — the same behavior as before this rule existed.
 */
class SenderProfileFirmierungTest {

    private fun sender(legalForm: LegalForm?, craftsChamberRegistered: Boolean): SenderProfile =
        SenderProfile().apply {
            this.legalForm = legalForm
            this.craftsChamberRegistered = craftsChamberRegistered
        }

    @Test
    fun `sole proprietor with crafts chamber registration suppresses the commercial register`() {
        assertFalse(sender(LegalForm.SOLE_PROPRIETOR, true).showsCommercialRegister())
    }

    @Test
    fun `sole proprietor without crafts chamber registration still shows the commercial register`() {
        // e.g. an "eingetragener Kaufmann" — a sole proprietor can well be a registered merchant.
        assertTrue(sender(LegalForm.SOLE_PROPRIETOR, false).showsCommercialRegister())
    }

    @Test
    fun `every non-sole-proprietor legal form shows the commercial register regardless of crafts chamber flag`() {
        for (form in listOf(LegalForm.GMBH, LegalForm.GMBH_CO_KG, LegalForm.KG, LegalForm.OHG)) {
            assertTrue(sender(form, craftsChamberRegistered = false).showsCommercialRegister(), "$form + false")
            assertTrue(sender(form, craftsChamberRegistered = true).showsCommercialRegister(), "$form + true")
        }
    }

    @Test
    fun `no legal form recorded shows the commercial register regardless of crafts chamber flag`() {
        assertTrue(sender(null, craftsChamberRegistered = false).showsCommercialRegister())
        assertTrue(sender(null, craftsChamberRegistered = true).showsCommercialRegister())
    }

    @Test
    fun `default craftsChamberRegistered is false, matching the migration's column default`() {
        assertFalse(SenderProfile().craftsChamberRegistered)
    }

    @Test
    fun `default legalForm is null, meaning keine Angabe`() {
        assertTrue(SenderProfile().legalForm == null)
    }
}
