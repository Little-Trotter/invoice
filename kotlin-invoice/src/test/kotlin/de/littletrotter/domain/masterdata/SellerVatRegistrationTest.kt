package de.littletrotter.domain.masterdata

import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.ForeignTaxNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.test.FIXED_TODAY

/** Additional, non-domestic seller VAT registrations. */
class SellerVatRegistrationTest {

    private fun validSenderProfile(): SenderProfile = aSenderProfile {
        name = "ACME GmbH"
        invoicePrefix = "ACME"
        email = Email("billing@acme.example")
        taxNumber = TaxNumber("12/345/67890")
        vatId = VatId("DE123456789")
        iban = Iban("DE89370400440532013000")
    }

    @Test
    fun `a curated-country registration with a matching VAT id prefix is valid`() {
        val profile = validSenderProfile()
        assertDoesNotThrow { SellerVatRegistration.of(profile, Country.LU, VatId("LU12345678"), today = FIXED_TODAY) }
    }

    @Test
    fun `a Greek registration uses the EL VAT prefix for the GR country`() {
        val profile = validSenderProfile()

        val registration = SellerVatRegistration.of(profile, Country.GR, VatId("EL123456789"), today = FIXED_TODAY)

        assertEquals(Country.GR, registration.country)
        assertEquals("EL123456789", registration.vatId?.value)
    }

    @Test
    fun `a country outside the curated set is rejected`() {
        val profile = validSenderProfile()
        assertThrows(InvalidParameterException::class.java) {
            SellerVatRegistration.of(profile, Country.CH, VatId("CH123456789"), today = FIXED_TODAY)
        }
    }

    @Test
    fun `the seller's own domestic country is excluded — this is for non-domestic registrations only`() {
        val profile = validSenderProfile()
        assertThrows(InvalidParameterException::class.java) {
            SellerVatRegistration.of(profile, Country.DE, VatId("DE123456789"), today = FIXED_TODAY)
        }
    }

    @Test
    fun `a VAT id whose prefix doesn't match the country is rejected`() {
        val profile = validSenderProfile()
        assertThrows(InvalidParameterException::class.java) {
            SellerVatRegistration.of(profile, Country.LU, VatId("FR12345678901"), today = FIXED_TODAY)
        }
    }

    @Test
    fun `a structurally invalid VAT id is rejected even with the right prefix`() {
        val profile = validSenderProfile()
        // LU expects exactly 8 digits after the prefix (VatIdValidator) — this has only 4.
        assertThrows(InvalidParameterException::class.java) {
            SellerVatRegistration.of(profile, Country.LU, VatId("LU1234"), today = FIXED_TODAY)
        }
    }

    @Test
    fun `whitespace and lower case are normalized before validation`() {
        // normalisation moved from SellerVatRegistration.of() into VatId.of() itself
        // (the raw VatId(...) constructor only validates, tolerant of unnormalised input, but no
        // longer force-normalises the stored value — see VatId's own KDoc).
        val profile = validSenderProfile()
        val reg = SellerVatRegistration.of(profile, Country.LU, VatId.of(" lu 1234 5678 "), today = FIXED_TODAY)
        assertEquals("LU12345678", reg.vatId?.value)
    }

    @Test
    fun `a blank VAT id is rejected`() {
        val profile = validSenderProfile()
        assertThrows(InvalidParameterException::class.java) {
            SellerVatRegistration.of(profile, Country.LU, VatId("   "), today = FIXED_TODAY)
        }
    }

    @Test
    fun `duplicate countries in a candidate list are rejected`() {
        val profile = validSenderProfile()
        val a = SellerVatRegistration.of(profile, Country.LU, VatId("LU12345678"), today = FIXED_TODAY)
        val b = SellerVatRegistration.of(profile, Country.LU, VatId("LU87654321"), today = FIXED_TODAY)
        assertThrows(InvalidParameterException::class.java) {
            SellerVatRegistration.assertUniqueCountries(listOf(a, b))
        }
    }

    @Test
    fun `distinct countries in a candidate list pass`() {
        val profile = validSenderProfile()
        val a = SellerVatRegistration.of(profile, Country.LU, VatId("LU12345678"), today = FIXED_TODAY)
        val b = SellerVatRegistration.of(profile, Country.FR, VatId("FR12345678901"), today = FIXED_TODAY)
        assertDoesNotThrow { SellerVatRegistration.assertUniqueCountries(listOf(a, b)) }
    }

    @Test
    fun `the curated set is every current EU member except Germany`() {
        assertEquals(
            setOf(
                Country.AT, Country.BE, Country.BG, Country.CY, Country.CZ, Country.DK,
                Country.EE, Country.ES, Country.FI, Country.FR, Country.GR, Country.HR,
                Country.HU, Country.IE, Country.IT, Country.LT, Country.LU, Country.LV,
                Country.MT, Country.NL, Country.PL, Country.PT, Country.RO, Country.SE,
                Country.SI, Country.SK,
            ),
            Country.supportedSellerVatRegistrationCountries(today = FIXED_TODAY),
        )
    }

    @Test
    fun `neither Germany nor the UK (past EU VAT area membership) are in the curated set`() {
        assertFalse(Country.DE in Country.supportedSellerVatRegistrationCountries(today = FIXED_TODAY))
        assertFalse(Country.GB in Country.supportedSellerVatRegistrationCountries(today = FIXED_TODAY))
    }

    // ── SenderProfile.updateVatRegistrations ─────────────────────────

    @Test
    fun `replacing sets the owning profile on every row and rejects duplicates`() {
        val profile = validSenderProfile()
        profile.updateVatRegistrations(listOf(SellerVatRegistrationCommand(Country.LU, VatId("LU12345678"))), today = FIXED_TODAY)

        assertEquals(1, profile.additionalVatRegistrations.size)
        assertEquals("LU12345678", profile.additionalVatRegistrations.single().vatId?.value)
        assertTrue(profile.additionalVatRegistrations.single().senderProfile === profile)
    }

    @Test
    fun `replacing with a duplicate country throws and leaves the previous list untouched`() {
        val profile = validSenderProfile()
        profile.updateVatRegistrations(listOf(SellerVatRegistrationCommand(Country.LU, VatId("LU12345678"))), today = FIXED_TODAY)
        val existing = profile.additionalVatRegistrations.single()

        assertThrows(InvalidParameterException::class.java) {
            profile.updateVatRegistrations(
                listOf(
                    SellerVatRegistrationCommand(Country.LU, VatId("LU11111111")),
                    SellerVatRegistrationCommand(Country.LU, VatId("LU22222222")),
                ), today = FIXED_TODAY,
            )
        }
        assertEquals(1, profile.additionalVatRegistrations.size)
        assertTrue(profile.additionalVatRegistrations.single() === existing)
    }

    @Test
    fun `replacing with an empty list clears all registrations`() {
        val profile = validSenderProfile()
        profile.updateVatRegistrations(listOf(SellerVatRegistrationCommand(Country.LU, VatId("LU12345678"))), today = FIXED_TODAY)
        profile.updateVatRegistrations(emptyList(), today = FIXED_TODAY)
        assertTrue(profile.additionalVatRegistrations.isEmpty())
    }

    @Test
    fun `changeVatId replaces the registration's VAT id`() {
        val registration = SellerVatRegistration.of(SenderProfile(), Country.LU, VatId("LU12345678"), FIXED_TODAY)
        registration.changeVatId(VatId("LU87654321"))
        assertEquals(VatId("LU87654321"), registration.vatId)
    }

    // ── Foreign tax number (pair with the VAT id) ─────────────────────

    @Test
    fun `a registration keeps an optional tax number`() {
        val profile = validSenderProfile()
        val withNumber = SellerVatRegistration.of(profile, Country.LU, VatId("LU12345678"), FIXED_TODAY, ForeignTaxNumber.of("1990 0101 12345"))
        val without = SellerVatRegistration.of(profile, Country.LU, VatId("LU12345678"), FIXED_TODAY)

        assertEquals("1990010112345", withNumber.taxNumber?.value)
        assertEquals(null, without.taxNumber)
    }

    @Test
    fun `a tax number without a VAT id is rejected as a pair violation`() {
        val profile = validSenderProfile()
        val ex = assertThrows(InvalidParameterException::class.java) {
            SellerVatRegistration.of(profile, Country.LU, null, FIXED_TODAY, ForeignTaxNumber.of("123"))
        }
        assertTrue(ex.message!!.contains("zusammen mit der USt-IdNr."))
    }

    @Test
    fun `a missing VAT id without tax number keeps the plain message`() {
        val profile = validSenderProfile()
        val ex = assertThrows(InvalidParameterException::class.java) {
            SellerVatRegistration.of(profile, Country.LU, null, FIXED_TODAY)
        }
        assertEquals("USt-IdNr. darf nicht leer sein.", ex.message)
    }

    @Test
    fun `updating sets, changes and clears the tax number on the retained row`() {
        val profile = validSenderProfile()
        fun command(taxNumber: String?) =
            listOf(SellerVatRegistrationCommand(Country.LU, VatId("LU12345678"), ForeignTaxNumber.ofNullable(taxNumber)))

        profile.updateVatRegistrations(command("111"), FIXED_TODAY)
        assertEquals("111", profile.additionalVatRegistrations.single().taxNumber?.value)

        profile.updateVatRegistrations(command("222"), FIXED_TODAY)
        assertEquals("222", profile.additionalVatRegistrations.single().taxNumber?.value)

        profile.updateVatRegistrations(command(null), FIXED_TODAY)
        assertEquals(null, profile.additionalVatRegistrations.single().taxNumber)
    }

    @Test
    fun `removing the registration removes its tax number with it`() {
        val profile = validSenderProfile()
        profile.updateVatRegistrations(
            listOf(SellerVatRegistrationCommand(Country.LU, VatId("LU12345678"), ForeignTaxNumber.of("111"))),
            FIXED_TODAY,
        )
        profile.updateVatRegistrations(emptyList(), FIXED_TODAY)
        assertTrue(profile.additionalVatRegistrations.isEmpty())
    }
}
