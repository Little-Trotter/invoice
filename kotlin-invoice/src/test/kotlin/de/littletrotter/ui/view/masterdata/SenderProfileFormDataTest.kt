package de.littletrotter.ui.view.masterdata

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.ElectronicAddress
import de.littletrotter.domain.masterdata.PartyIdentifier
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.Bic
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/** SenderProfileFormData's BT-29/30 round-trip (fields exposed by the sender mask). */
class SenderProfileFormDataTest {

    /**
     * Pins the mirror contract between the form's `applyTo` and the entity's
     * `copyEditableFieldsFrom` (the save path for existing profiles): a field the form writes
     * that the copy drops is a silently discarded edit. This exact class of bug happened twice —
     * six fields historically, and four more (both PDF templates, legalForm,
     * craftsChamberRegistered) found when the hand-maintained list moved into the entity.
     * [defaultPaymentTerm] is deliberately outside this contract now (see both methods' own
     * KDoc) — `SellerProfileServiceTest` covers its resolution instead.
     */
    @Test
    fun `copyEditableFieldsFrom mirrors every field applyTo writes`() {
        val written = SenderProfile()
        SenderProfileFormData(
            name = "Muster GmbH",
            taxNumber = "12/345/67890",
            vatId = "de 123456789",
            iban = "DE89370400440532013000",
            bic = "COBADEFFXXX",
            bankName = "Musterbank",
            invoicePrefix = "MU-",
            phone = "+49 30 123",
            email = "billing@muster.example",
            website = "https://www.muster.example",
            registeredSeat = "Berlin",
            managingDirector = "M. Muster",
            courtOfJurisdiction = "Berlin",
            registerCourt = "AG Charlottenburg",
            commercialRegisterNumber = "HRB 12345",
            legalRegistrationSchemeId = "0021",
            isDefault = true,
            legalForm = de.littletrotter.domain.masterdata.LegalForm.entries.first(),
            craftsChamberRegistered = true,
        ).applyTo(written)

        val copied = SenderProfile()
        copied.copyEditableFieldsFrom(written)

        // One assertion per editable field — extend BOTH lists (applyTo + copyEditableFieldsFrom)
        // and this test when the form grows.
        assertEquals(written.name, copied.name)
        assertEquals(written.taxNumber, copied.taxNumber)
        assertEquals(written.vatId, copied.vatId)
        assertEquals(written.iban, copied.iban)
        assertEquals(written.bic, copied.bic)
        assertEquals(written.bankName, copied.bankName)
        assertEquals(written.invoicePrefix, copied.invoicePrefix)
        assertEquals(written.phone, copied.phone)
        assertEquals(written.email, copied.email)
        assertEquals(written.website, copied.website)
        assertEquals(written.registeredSeat, copied.registeredSeat)
        assertEquals(written.managingDirector, copied.managingDirector)
        assertEquals(written.courtOfJurisdiction, copied.courtOfJurisdiction)
        assertEquals(written.registerCourt, copied.registerCourt)
        assertEquals(written.commercialRegisterNumber, copied.commercialRegisterNumber)
        assertEquals(written.sellerIdentifier, copied.sellerIdentifier)
        assertEquals(written.legalRegistrationSchemeId, copied.legalRegistrationSchemeId)
        assertEquals(written.electronicAddress, copied.electronicAddress)
        assertEquals(written.legalForm, copied.legalForm)
        assertEquals(written.craftsChamberRegistered, copied.craftsChamberRegistered)
        assertEquals(written.isDefault, copied.isDefault)
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
    fun `applyTo maps identifier and legalRegistrationSchemeId onto the entity`() {
        val formData = SenderProfileFormData(
            name = "Seller GmbH",
            sellerIdentifierValue = "123456789", sellerIdentifierScheme = "0088",
            legalRegistrationSchemeId = "0002",
        )
        val target = SenderProfile()
        formData.applyTo(target)

        assertEquals("123456789", target.sellerIdentifier?.value)
        assertEquals("0088", target.sellerIdentifier?.schemeId)
        assertEquals("0002", target.legalRegistrationSchemeId)
        assertNull(target.electronicAddress)
    }

    @Test
    fun `applyTo leaves identifier null when both value and scheme are blank`() {
        val formData = SenderProfileFormData(name = "Seller GmbH")
        val target = SenderProfile()
        formData.applyTo(target)

        assertNull(target.sellerIdentifier)
        assertNull(target.electronicAddress)
    }

    @Test
    fun `applyTo rejects a scheme-less identifier instead of silently dropping the value`() {
        val formData = SenderProfileFormData(
            name = "Seller GmbH", sellerIdentifierValue = "123456789", sellerIdentifierScheme = "",
        )
        assertThrows(InvalidParameterException::class.java) { formData.applyTo(SenderProfile()) }
    }

    @Test
    fun `from reads identifier off the entity`() {
        val profile = SenderProfile().apply {
            name = "Seller GmbH"
            sellerIdentifier = PartyIdentifier.of("123456789", "0088")
            electronicAddress = ElectronicAddress.of("seller@example.org", "EM")
        }
        val formData = SenderProfileFormData.from(profile)

        assertEquals("123456789", formData.sellerIdentifierValue)
        assertEquals("0088", formData.sellerIdentifierScheme)
        // The electronic address is not part of the Community form data anymore (H7).
        assertThrows(NoSuchFieldException::class.java) {
            SenderProfileFormData::class.java.getDeclaredField("electronicAddressValue")
        }
    }

    /** `from()`'s `orEmpty()`/`ifBlank`/elvis chain over every nullable entity field
     *  only ever saw the "field is null" side before this test (an empty [SenderProfile] is what
     *  every other fixture in this file and in [SenderProfileViewKaribuTest] loads). A profile
     *  with every one of those fields actually set exercises the "field is present" side of each,
     *  the branch half a bare `SenderProfile()` can never reach. */
    @Test
    fun `from carries every populated field across, not just the null defaults`() {
        val profile = SenderProfile().apply {
            name = "Muster GmbH"
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban("DE89370400440532013000")
            bic = Bic("COBADEFFXXX")
            bankName = "Musterbank"
            invoicePrefix = "MU-"
            // FooterNote is gone; the German invoice note now lives in invoiceNote.
            setInvoiceNote(de.littletrotter.domain.reference.Language().apply { id = 1; code = "de"; isFallback = true }, "Fusszeile")
            phone = "+49 30 123"
            email = Email("billing@muster.example")
            website = "https://www.muster.example"
            registeredSeat = "Musterstadt"
            managingDirector = "M. Muster"
            courtOfJurisdiction = "Berlin"
            registerCourt = "AG Charlottenburg"
            commercialRegisterNumber = "HRB 12345"
            legalRegistrationSchemeId = "0021"
        }
        profile.addresses.add(
            Address.forSender(profile, Address.AddressType.BILLING, "Musterstr. 1", "10115", "Berlin", Country.AT, true),
        )

        val formData = SenderProfileFormData.from(profile)

        assertEquals("Musterstr. 1", formData.street)
        assertEquals("10115", formData.postalCode)
        assertEquals("Berlin", formData.city)
        assertEquals(Country.AT, formData.country)
        assertEquals("12/345/67890", formData.taxNumber)
        assertEquals("DE123456789", formData.vatId)
        assertEquals("DE89370400440532013000", formData.iban)
        assertEquals("COBADEFFXXX", formData.bic)
        assertEquals("Musterbank", formData.bankName)
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
    }

    /** The other half of `invoicePrefix`'s `ifBlank` — a blank invoicePrefix (never actually
     *  possible via the UI/validation, but the entity field itself is a bare non-null String)
     *  falls back to the "RE-" default. */
    @Test
    fun `from falls back to the RE- prefix when invoicePrefix is blank`() {
        val profile = SenderProfile().apply { name = "Muster GmbH"; invoicePrefix = "" }

        val formData = SenderProfileFormData.from(profile)

        assertEquals("RE-", formData.invoicePrefix)
    }

    /** The opposite direction of the test above: `applyTo`'s own `ifBlank` (writing the form back
     *  onto the entity) — [copyEditableFieldsFrom mirrors every field applyTo writes] above only
     *  ever exercises a non-blank invoicePrefix. */
    @Test
    fun `applyTo falls back to the RE- prefix when the form's invoicePrefix is blank`() {
        val target = SenderProfile()

        SenderProfileFormData(name = "Muster GmbH", invoicePrefix = "   ").applyTo(target)

        assertEquals("RE-", target.invoicePrefix)
    }

    @Test
    fun `applyTo never writes defaultPaymentTerm, from maps it from the entity`() {
        val term = de.littletrotter.domain.masterdata.PaymentTerm().apply {
            id = 42L
            dueInDays = 30
        }
        val formData = SenderProfileFormData(
            name = "Muster GmbH",
            defaultPaymentTerm = de.littletrotter.application.dto.PaymentTermDto(id = 42L, dueInDays = 30),
        )
        val target = SenderProfile()
        formData.applyTo(target)
        // Resolved by SellerProfileService.save from the id instead — see applyTo's own KDoc.
        assertNull(target.defaultPaymentTerm)

        val profile = SenderProfile().apply {
            name = "Muster GmbH"
            defaultPaymentTerm = term
        }
        val fromFormData = SenderProfileFormData.from(profile)
        assertEquals(42L, fromFormData.defaultPaymentTerm?.id)
        assertEquals(30, fromFormData.defaultPaymentTerm?.dueInDays)
    }
}
