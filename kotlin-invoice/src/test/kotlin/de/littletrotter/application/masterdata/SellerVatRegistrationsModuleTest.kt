package de.littletrotter.application.masterdata

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.shared.time.ClockTimeSource
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.spi.party.SellerVatRegistrationRow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import java.time.Instant
import de.littletrotter.test.FIXED_TODAY

/** task11/V1 — the seller's additional (non-domestic) VAT registrations. */
class SellerVatRegistrationsModuleTest {

    private val module = SellerVatRegistrationsModule(ClockTimeSource.fixedAt(Instant.parse("2026-06-15T10:00:00Z")))

    private fun senderProfile(): SenderProfile = SenderProfile().apply {
        name = "ACME GmbH"
        invoicePrefix = "ACME"
        email = Email("billing@acme.example")
        taxNumber = TaxNumber("12/345/67890")
        vatId = VatId("DE123456789")
        iban = Iban("DE89370400440532013000")
    }

    @Test
    fun `exposes exactly the curated country set, sorted`() {
        assertEquals(
            listOf(
                "AT", "BE", "BG", "CY", "CZ", "DK", "EE", "ES", "FI", "FR", "GR", "HR", "HU",
                "IE", "IT", "LT", "LU", "LV", "MT", "NL", "PL", "PT", "RO", "SE", "SI", "SK",
            ),
            module.supportedCountries,
        )
    }

    @Test
    fun `read reflects a persisted registration back as a row`() {
        val profile = senderProfile()
        profile.updateVatRegistrations(listOf(de.littletrotter.domain.masterdata.SellerVatRegistrationCommand(de.littletrotter.shared.country.Country.LU, VatId("LU12345678"))), today = FIXED_TODAY)

        val rows = module.read(profile)
        assertEquals(1, rows.size)
        assertEquals(SellerVatRegistrationRow("LU", "LU12345678"), rows.single())
    }

    @Test
    fun `write persists a list of rows onto the profile`() {
        val profile = senderProfile()

        module.write(profile, listOf(SellerVatRegistrationRow("LU", "LU12345678"), SellerVatRegistrationRow("FR", "FR12345678901")))

        assertEquals(2, profile.additionalVatRegistrations.size)
        assertTrue(profile.additionalVatRegistrations.any { it.country.alpha2 == "LU" && it.vatId?.value == "LU12345678" })
        assertTrue(profile.additionalVatRegistrations.any { it.country.alpha2 == "FR" && it.vatId?.value == "FR12345678901" })
    }

    @Test
    fun `write replaces the previous list rather than appending`() {
        val profile = senderProfile()
        module.write(profile, listOf(SellerVatRegistrationRow("LU", "LU12345678")))
        module.write(profile, listOf(SellerVatRegistrationRow("FR", "FR12345678901")))

        assertEquals(1, profile.additionalVatRegistrations.size)
        assertEquals("FR", profile.additionalVatRegistrations.single().country.alpha2)
    }

    @Test
    fun `write with an empty list clears all registrations`() {
        val profile = senderProfile()
        module.write(profile, listOf(SellerVatRegistrationRow("LU", "LU12345678")))
        module.write(profile, emptyList())
        assertTrue(profile.additionalVatRegistrations.isEmpty())
    }

    @Test
    fun `an unknown country code is rejected`() {
        val profile = senderProfile()
        assertThrows(InvalidParameterException::class.java) {
            module.write(profile, listOf(SellerVatRegistrationRow("XX", "XX12345678")))
        }
    }

    @Test
    fun `a duplicate country in one write is rejected`() {
        val profile = senderProfile()
        assertThrows(InvalidParameterException::class.java) {
            module.write(profile, listOf(SellerVatRegistrationRow("LU", "LU11111111"), SellerVatRegistrationRow("LU", "LU22222222")))
        }
    }
}
