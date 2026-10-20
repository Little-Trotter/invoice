package de.littletrotter.shared.tax

import de.littletrotter.shared.country.Country
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime

/** A confirmed proposal goes stale once the context it was confirmed under changes. */
class TaxRuleConfirmationTest {

    // ── a confirmed proposal becomes stale when its context changes ───

    private fun context(buyerCountry: de.littletrotter.shared.country.Country = de.littletrotter.shared.country.Country.FR) =
        TaxRuleResolutionRequest(
            serviceType = ServiceType.GENERAL_SERVICE,
            serviceDate = LocalDate.of(2026, 6, 1),
            sellerCountry = de.littletrotter.shared.country.Country.DE,
            buyerCountry = buyerCountry,
            customerType = CustomerType.BUSINESS,
            sellerVatId = "DE111111111",
            buyerVatId = "FR12345678901",
            buyerVatIdVerificationStatus = VatIdVerificationStatus.VERIFIED,
        )

    @Test
    fun `R5-T16 - changing the buyer country marks a confirmation as stale`() {
        val confirmation = TaxRuleConfirmation("EU_B2B_RC_SERVICE", context(), OffsetDateTime.now())
        val changed = context(buyerCountry = de.littletrotter.shared.country.Country.NL)

        assertTrue(confirmation.isStaleFor(changed))
    }

    @Test
    fun `an unchanged context is not stale`() {
        val confirmation = TaxRuleConfirmation("EU_B2B_RC_SERVICE", context(), OffsetDateTime.now())

        assertFalse(confirmation.isStaleFor(context()))
    }

    @Test
    fun `changing customer type, service date or buyer vat id each marks a confirmation as stale`() {
        val confirmation = TaxRuleConfirmation("EU_B2B_RC_SERVICE", context(), OffsetDateTime.now())

        assertTrue(confirmation.isStaleFor(context().copy(customerType = CustomerType.CONSUMER)))
        assertTrue(confirmation.isStaleFor(context().copy(serviceDate = LocalDate.of(2026, 7, 1))))
        assertTrue(confirmation.isStaleFor(context().copy(buyerVatId = "FR99999999999")))
    }

    // ── a confirmation made under a different seller VAT registration is stale ───

    @Test
    fun `switching from the German default to a seller VAT registration marks a confirmation as stale`() {
        val confirmation = TaxRuleConfirmation("EU_B2B_RC_SERVICE", context(), OffsetDateTime.now())
        val changed = context().copy(sellerVatRegistrationCountry = de.littletrotter.shared.country.Country.LU)

        assertTrue(confirmation.isStaleFor(changed))
    }

    @Test
    fun `switching between two non-German seller VAT registrations marks a confirmation as stale`() {
        val luContext = context().copy(sellerVatRegistrationCountry = de.littletrotter.shared.country.Country.LU)
        val confirmation = TaxRuleConfirmation("EU_B2B_RC_SERVICE", luContext, OffsetDateTime.now())
        val frContext = context().copy(sellerVatRegistrationCountry = de.littletrotter.shared.country.Country.FR)

        assertTrue(confirmation.isStaleFor(frContext))
    }

    @Test
    fun `switching back to the German default marks a confirmation made under a registration as stale`() {
        val luContext = context().copy(sellerVatRegistrationCountry = de.littletrotter.shared.country.Country.LU)
        val confirmation = TaxRuleConfirmation("EU_B2B_RC_SERVICE", luContext, OffsetDateTime.now())

        assertTrue(confirmation.isStaleFor(context())) // context() has sellerVatRegistrationCountry = null
    }

    @Test
    fun `an unchanged seller VAT registration context is not stale`() {
        val luContext = context().copy(sellerVatRegistrationCountry = de.littletrotter.shared.country.Country.LU)
        val confirmation = TaxRuleConfirmation("EU_B2B_RC_SERVICE", luContext, OffsetDateTime.now())

        assertFalse(confirmation.isStaleFor(luContext))
    }
}
