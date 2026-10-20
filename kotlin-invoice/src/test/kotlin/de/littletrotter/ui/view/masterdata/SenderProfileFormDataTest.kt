package de.littletrotter.ui.view.masterdata

import de.littletrotter.application.dto.AddressDto
import de.littletrotter.application.dto.PaymentTermDto
import de.littletrotter.domain.masterdata.LegalForm
import de.littletrotter.shared.country.Country
import de.littletrotter.spi.party.PartyPairState
import de.littletrotter.spi.party.SellerVatRegistrationRow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** SenderProfileFormData's BT-29/30 round-trip (fields exposed by the sender mask). */
class SenderProfileFormDataTest {

    @Test
    fun `toCommand carries every form field and the widget inputs`() {
        val pairs = mapOf("electronicAddress" to PartyPairState("seller@example.org", "EM"))
        val vat = listOf(SellerVatRegistrationRow("LU", "LU12345678"))
        val form = SenderProfileFormData(
            id = 7L,
            name = "Muster GmbH",
            taxNumber = "12/345/67890",
            vatId = "de 123456789",
            iban = "DE89370400440532013000",
            bic = "COBADEFFXXX",
            bankName = "Musterbank",
            defaultPaymentTerm = PaymentTermDto(id = 42L, dueInDays = 30),
            invoicePrefix = "MU-",
            phone = "+49 30 123",
            email = "billing@muster.example",
            website = "https://www.muster.example",
            registeredSeat = "Berlin",
            managingDirector = "M. Muster",
            courtOfJurisdiction = "Berlin",
            registerCourt = "AG Charlottenburg",
            commercialRegisterNumber = "HRB 12345",
            sellerIdentifierValue = "123456789",
            sellerIdentifierScheme = "0088",
            legalRegistrationSchemeId = "0021",
            isDefault = true,
            legalForm = LegalForm.entries.first(),
            craftsChamberRegistered = true,
        )

        val command = form.toCommand(
            SenderProfileSaveInputs(AddressDto(null, "Weg 1", "10115", "Berlin", "AT"), mapOf("de" to "Text"), pairs, vat, "V-001"),
        )

        assertEquals(7L, command.id)
        assertEquals("Muster GmbH", command.name)
        assertEquals("Weg 1", command.street)
        assertEquals("10115", command.postalCode)
        assertEquals("Berlin", command.city)
        assertEquals("AT", command.countryAlpha2)
        assertEquals("12/345/67890", command.taxNumber)
        assertEquals("de 123456789", command.vatId)
        assertEquals("DE89370400440532013000", command.iban)
        assertEquals("COBADEFFXXX", command.bic)
        assertEquals("Musterbank", command.bankName)
        assertEquals(42L, command.defaultPaymentTermId)
        assertEquals("MU-", command.invoicePrefix)
        assertEquals(mapOf("de" to "Text"), command.invoiceNotesByLanguage)
        assertEquals("+49 30 123", command.phone)
        assertEquals("billing@muster.example", command.email)
        assertEquals("https://www.muster.example", command.website)
        assertEquals("Berlin", command.registeredSeat)
        assertEquals("M. Muster", command.managingDirector)
        assertEquals("Berlin", command.courtOfJurisdiction)
        assertEquals("AG Charlottenburg", command.registerCourt)
        assertEquals("HRB 12345", command.commercialRegisterNumber)
        assertEquals("123456789", command.sellerIdentifierValue)
        assertEquals("0088", command.sellerIdentifierSchemeId)
        assertEquals("0021", command.legalRegistrationSchemeId)
        assertEquals(true, command.isDefault)
        assertEquals(LegalForm.entries.first(), command.legalForm)
        assertEquals(true, command.craftsChamberRegistered)
        assertEquals(pairs, command.partyPairs)
        assertEquals(vat, command.vatRegistrations)
        assertEquals("V-001", command.proposedSellerNumber)
    }

    @Test
    fun `toCommand leaves the payment term id empty when none is chosen`() {
        val command = SenderProfileFormData().toCommand(
            SenderProfileSaveInputs(AddressDto(null, "", "", "", "DE"), emptyMap(), emptyMap(), emptyList(), null),
        )

        assertNull(command.id)
        assertNull(command.defaultPaymentTermId)
        assertNull(command.proposedSellerNumber)
    }

    @Test
    fun `SenderProfileView has no editable Leitweg-ID field`() {
        // BT-10/routingId identifies the public *buyer*, never the seller. SenderProfile itself
        // no longer carries the field at all (fix/mustang-kmu-leitweg-v2, P4) — this
        // asserts the UI field never existed/reappears, not merely that it is hidden.
        assertThrows(NoSuchFieldException::class.java) {
            SenderProfileView::class.java.getDeclaredField("routingIdField")
        }
    }

    @Test
    fun `SenderProfileView form data carries no electronic-address pair`() {
        // The electronic address (BT-34) is contributed by a PartyFormExtension bean, not
        // hand-coded form data — it must be absent from the class entirely, not even hidden.
        assertThrows(NoSuchFieldException::class.java) {
            SenderProfileView::class.java.getDeclaredField("electronicAddressValueField")
        }
        assertThrows(NoSuchFieldException::class.java) {
            SenderProfileView::class.java.getDeclaredField("electronicAddressSchemeField")
        }
    }

    @Test
    fun `from reads identifier off the loaded profile`() {
        val formData = SenderProfileFormData.from(
            senderDetail(name = "Seller GmbH", sellerIdentifierValue = "123456789", sellerIdentifierSchemeId = "0088"),
        )

        assertEquals("123456789", formData.sellerIdentifierValue)
        assertEquals("0088", formData.sellerIdentifierScheme)
        // The electronic address is not part of the Community form data anymore (H7).
        assertThrows(NoSuchFieldException::class.java) {
            SenderProfileFormData::class.java.getDeclaredField("electronicAddressValue")
        }
    }

    /** `from()`'s `orEmpty()`/`ifBlank`/elvis chain over every nullable field only ever saw the
     *  "field is null" side in the other fixtures; a profile with every field set exercises the
     *  "field is present" side of each. */
    @Test
    fun `from carries every populated field across, not just the null defaults`() {
        val pairs = mapOf("electronicAddress" to PartyPairState("seller@example.org", "EM"))
        val vat = listOf(SellerVatRegistrationRow("LU", "LU12345678"))
        val formData = SenderProfileFormData.from(
            senderDetail(
                id = 5L,
                version = 3L,
                name = "Muster GmbH",
                billingAddress = AddressDto(1L, "Musterstr. 1", "10115", "Berlin", "AT"),
                taxNumber = "12/345/67890",
                vatId = "DE123456789",
                iban = "DE89370400440532013000",
                bic = "COBADEFFXXX",
                bankName = "Musterbank",
                defaultPaymentTerm = PaymentTermDto(id = 42L, dueInDays = 30),
                invoicePrefix = "MU-",
                invoiceNotesByLanguageCode = mapOf("de" to "Fusszeile"),
                phone = "+49 30 123",
                email = "billing@muster.example",
                website = "https://www.muster.example",
                registeredSeat = "Musterstadt",
                managingDirector = "M. Muster",
                courtOfJurisdiction = "Berlin",
                registerCourt = "AG Charlottenburg",
                commercialRegisterNumber = "HRB 12345",
                legalRegistrationSchemeId = "0021",
                isDefault = true,
                legalForm = LegalForm.entries.first(),
                craftsChamberRegistered = true,
                sellerNumber = "V-001",
                shortHash = "AB12",
                partyPairs = pairs,
                vatRegistrations = vat,
            ),
        )

        assertEquals(5L, formData.id)
        assertEquals(3L, formData.version)
        assertEquals("Musterstr. 1", formData.street)
        assertEquals("10115", formData.postalCode)
        assertEquals("Berlin", formData.city)
        assertEquals(Country.AT, formData.country)
        assertEquals("12/345/67890", formData.taxNumber)
        assertEquals("DE123456789", formData.vatId)
        assertEquals("DE89370400440532013000", formData.iban)
        assertEquals("COBADEFFXXX", formData.bic)
        assertEquals("Musterbank", formData.bankName)
        assertEquals(42L, formData.defaultPaymentTerm?.id)
        assertEquals(30, formData.defaultPaymentTerm?.dueInDays)
        assertEquals("MU-", formData.invoicePrefix)
        assertEquals("Fusszeile", formData.invoiceNotesByLanguageCode["de"])
        assertEquals("+49 30 123", formData.phone)
        assertEquals("billing@muster.example", formData.email)
        assertEquals("https://www.muster.example", formData.website)
        assertEquals("Musterstadt", formData.registeredSeat)
        assertEquals("M. Muster", formData.managingDirector)
        assertEquals("Berlin", formData.courtOfJurisdiction)
        assertEquals("AG Charlottenburg", formData.registerCourt)
        assertEquals("HRB 12345", formData.commercialRegisterNumber)
        assertEquals("0021", formData.legalRegistrationSchemeId)
        assertEquals(true, formData.isDefault)
        assertEquals(LegalForm.entries.first(), formData.legalForm)
        assertEquals(true, formData.craftsChamberRegistered)
        assertEquals("V-001", formData.sellerNumber)
        assertEquals("AB12", formData.shortHash)
        assertEquals(pairs, formData.partyPairs)
        assertEquals(vat, formData.vatRegistrations)
    }

    @Test
    fun `from maps a profile without address or optional values to blank defaults`() {
        val formData = SenderProfileFormData.from(senderDetail(name = "Muster GmbH"))

        assertEquals("", formData.street)
        assertEquals("", formData.postalCode)
        assertEquals("", formData.city)
        assertEquals(Country.DE, formData.country)
        assertEquals("", formData.taxNumber)
        assertEquals("", formData.vatId)
        assertEquals("", formData.iban)
        assertEquals("", formData.bic)
        assertEquals("", formData.bankName)
        assertNull(formData.defaultPaymentTerm)
        assertEquals("", formData.phone)
        assertEquals("", formData.email)
        assertEquals("", formData.sellerNumber)
        assertEquals("", formData.shortHash)
    }

    @Test
    fun `from null yields a blank form`() {
        assertNull(SenderProfileFormData.from(null).id)
    }

    /** The other half of `invoicePrefix`'s `ifBlank` — a blank invoicePrefix falls back to the
     *  "RE-" default. */
    @Test
    fun `from falls back to the RE- prefix when invoicePrefix is blank`() {
        val formData = SenderProfileFormData.from(senderDetail(name = "Muster GmbH", invoicePrefix = ""))

        assertEquals("RE-", formData.invoicePrefix)
    }
}
