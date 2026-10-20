package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.RegistrationTaxNumberFreeze
import de.littletrotter.domain.value.ForeignTaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.country.Country
import de.littletrotter.test.FIXED_TODAY
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** [PartyDtoMapper.senderToDto]'s website/registeredSeat passthrough — every other
 *  field of that mapping is already exercised indirectly through [InvoiceDtoMapperTest]. */
class PartyDtoMapperTest {

    @Test
    fun `senderToDto carries website and registeredSeat through verbatim`() {
        val sender = aSenderProfile {
            name = "Muster GmbH"
            website = "https://www.muster.example/"
            registeredSeat = "Musterstadt"
        }

        val dto = PartyDtoMapper.senderToDto(sender, billing = null, invoiceNote = null)

        assertEquals("https://www.muster.example/", dto.website)
        assertEquals("Musterstadt", dto.registeredSeat)
    }

    @Test
    fun `senderToDto leaves website and registeredSeat null when not set`() {
        val sender = aSenderProfile { name = "Muster GmbH" }

        val dto = PartyDtoMapper.senderToDto(sender, billing = null, invoiceNote = null)

        assertNull(dto.website)
        assertNull(dto.registeredSeat)
    }

    private fun senderWith(country: Country, vatId: String, taxNumber: String?) = aSenderProfile {
        name = "Muster GmbH"
    }.also {
        it.updateVatRegistrations(
            listOf(
                de.littletrotter.domain.masterdata.SellerVatRegistrationCommand(
                    country,
                    VatId(vatId),
                    ForeignTaxNumber.ofNullable(taxNumber),
                ),
            ),
            FIXED_TODAY,
        )
    }

    @Test
    fun `registration carries tax number and its label key`() {
        val dto = PartyDtoMapper.senderToDto(senderWith(Country.LU, "LU12345678", "1990 0101 12345"), null, null)

        val registration = dto.additionalVatRegistrations.single()
        assertEquals("1990010112345", registration.taxNumber)
        assertEquals("foreignTaxNumber.label.matricule", registration.taxNumberLabelKey)
    }

    @Test
    fun `registration without tax number has neither number nor label`() {
        val registration = PartyDtoMapper.senderToDto(senderWith(Country.LU, "LU12345678", null), null, null)
            .additionalVatRegistrations.single()

        assertNull(registration.taxNumber)
        assertNull(registration.taxNumberLabelKey)
    }

    @Test
    fun `a tax number equal to the VAT id without prefix is left out`() {
        val registration = PartyDtoMapper.senderToDto(senderWith(Country.LU, "LU12345678", "12345678"), null, null)
            .additionalVatRegistrations.single()

        assertNull(registration.taxNumber)
        assertNull(registration.taxNumberLabelKey)
    }

    @Test
    fun `a country without own term uses the generic TIN label`() {
        val registration = PartyDtoMapper.senderToDto(senderWith(Country.DK, "DK12345678", "99887766"), null, null)
            .additionalVatRegistrations.single()

        assertEquals("99887766", registration.taxNumber)
        assertEquals("foreignTaxNumber.label.tin", registration.taxNumberLabelKey)
    }

    @Test
    fun `frozen value replaces the live one for the invoice country and clears the others`() {
        val sender = senderWith(Country.LU, "LU12345678", "LIVE111")

        val frozen = PartyDtoMapper.senderToDto(
            sender, null, null, RegistrationTaxNumberFreeze(Country.LU, ForeignTaxNumber.of("FROZEN9")),
        ).additionalVatRegistrations.single()
        val frozenNone = PartyDtoMapper.senderToDto(
            sender, null, null, RegistrationTaxNumberFreeze(Country.LU, null),
        ).additionalVatRegistrations.single()
        val otherCountry = PartyDtoMapper.senderToDto(
            sender, null, null, RegistrationTaxNumberFreeze(Country.FR, ForeignTaxNumber.of("FROZEN9")),
        ).additionalVatRegistrations.single()

        assertEquals("FROZEN9", frozen.taxNumber)
        assertNull(frozenNone.taxNumber)
        assertNull(otherCountry.taxNumber)
    }
}
