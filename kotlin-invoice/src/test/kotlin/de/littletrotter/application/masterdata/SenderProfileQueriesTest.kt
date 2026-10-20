package de.littletrotter.application.masterdata

import de.littletrotter.application.dto.AddressDto
import de.littletrotter.application.dto.SenderProfileRowDto
import de.littletrotter.application.dto.SenderProfileSort
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.ElectronicAddress
import de.littletrotter.domain.masterdata.LegalForm
import de.littletrotter.domain.masterdata.PartyIdentifier
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.Language
import de.littletrotter.domain.shared.GridSort
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.domain.value.Bic
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.aPaymentTerm
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.spi.party.PartyFormExtension
import de.littletrotter.spi.party.PartyIdentifierSpec
import de.littletrotter.spi.party.PartyKind
import de.littletrotter.spi.party.PartyPairState
import de.littletrotter.spi.party.SellerVatRegistrationRow
import de.littletrotter.spi.party.SellerVatRegistrationsExtension
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** [SenderProfileQueries] maps stored profiles to the grid row and the edit-dialog read models. */
class SenderProfileQueriesTest {

    private val service: SellerProfileService = mock()

    private class FixedPairExtension : PartyFormExtension {
        override val id = "fixed"
        override fun pairs(kind: PartyKind) = listOf(PartyIdentifierSpec(id = "pair", i18nKeyPrefix = "x"))
        override fun read(party: Any, kind: PartyKind, spec: PartyIdentifierSpec) = PartyPairState("addr", "EM")
        override fun write(party: Any, kind: PartyKind, spec: PartyIdentifierSpec, state: PartyPairState) = Unit
    }

    private class FixedVatExtension : SellerVatRegistrationsExtension {
        override val id = "fixedVat"
        override val supportedCountries = listOf("LU")
        override fun read(senderProfile: Any) = listOf(SellerVatRegistrationRow("LU", "LU12345678"))
        override fun write(senderProfile: Any, rows: List<SellerVatRegistrationRow>) = Unit
    }

    private fun queries(withExtensions: Boolean = false) = SenderProfileQueries(
        service,
        PartyFormExtensionRegistry(if (withExtensions) listOf(FixedPairExtension()) else emptyList()),
        SellerVatRegistrationsExtensionRegistry(if (withExtensions) listOf(FixedVatExtension()) else emptyList()),
    )

    private fun lean(id: Long = 1L) = aSenderProfile {
        this.id = id
        name = "Anbieter"
        version = 4L
        isDefault = true
        invoicePrefix = "AN-"
        sellerNumber = "V-001"
        shortHash = "AB12"
    }

    private fun german() = Language().edit { this.id = 1; code = "de"; isFallback = true }

    private fun full(): SenderProfile {
        val profile = lean(5L).edit {
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban("DE89370400440532013000")
            bic = Bic("COBADEFFXXX")
            bankName = "Musterbank"
            phone = "+49 30 123"
            email = Email("billing@muster.example")
            website = "https://www.muster.example"
            registeredSeat = "Musterstadt"
            managingDirector = "M. Muster"
            courtOfJurisdiction = "Berlin"
            registerCourt = "AG Charlottenburg"
            commercialRegisterNumber = "HRB 12345"
            sellerIdentifier = PartyIdentifier.of("123456789", "0088")
            legalRegistrationSchemeId = "0021"
            electronicAddress = ElectronicAddress.of("seller@example.org", "EM")
            legalForm = LegalForm.entries.first()
            craftsChamberRegistered = true
            defaultPaymentTerm = aPaymentTerm { id = 42L; dueInDays = 30; changeName(german(), "30 Tage") }
            changeInvoiceNote(german(), "Fusszeile")
        }
        profile.addAddress(Address.forSender(profile, Address.AddressType.BILLING, "Musterstr. 1", "10115", "Berlin", Country.AT, true))
        return profile
    }

    @Test
    fun `grid rows carry the lean columns and the billing city and country`() {
        val profile = lean()
        profile.addAddress(Address.forSender(profile, Address.AddressType.BILLING, "Weg 1", "10115", "Berlin", Country.AT, true))
        whenever(service.findAll(0, 50, "x")).thenReturn(listOf(profile))
        whenever(service.findAllSorted(1, 50, null, GridSort("name", SortDirection.DESC))).thenReturn(listOf(lean(2L)))
        whenever(service.count("x")).thenReturn(3L)
        whenever(service.findById(1L)).thenReturn(profile)

        val expected = SenderProfileRowDto(1L, 4L, "Anbieter", true, "AN-", "V-001", "AB12", "Berlin", "AT")
        assertEquals(listOf(expected), queries().findAll(0, 50, "x"))
        assertEquals(expected, queries().findRowById(1L))
        assertEquals(3L, queries().count("x"))
        assertEquals(
            listOf(SenderProfileRowDto(2L, 4L, "Anbieter", true, "AN-", "V-001", "AB12", null, null)),
            queries().findAllSorted(1, 50, null, SenderProfileSort("name", SortDirection.DESC)),
        )
    }

    @Test
    fun `an unknown row id yields null`() {
        assertNull(queries().findRowById(404L))
        assertNull(queries().findForEdit(404L))
    }

    @Test
    fun `a row for an unpersisted profile is rejected`() {
        whenever(service.findAll(0, 50, null)).thenReturn(listOf(SenderProfile()))

        assertThrows(IllegalArgumentException::class.java) { queries().findAll(0, 50, null) }
    }

    @Test
    fun `findForEdit maps every field and the extension state`() {
        whenever(service.findByIdForEdit(5L)).thenReturn(full())

        val detail = queries(withExtensions = true).findForEdit(5L)!!

        assertEquals(5L, detail.id)
        assertEquals(4L, detail.version)
        assertEquals("Anbieter", detail.name)
        assertEquals(AddressDto(null, "Musterstr. 1", "10115", "Berlin", "AT"), detail.billingAddress)
        assertEquals("12/345/67890", detail.taxNumber)
        assertEquals("DE123456789", detail.vatId)
        assertEquals("DE89370400440532013000", detail.iban)
        assertEquals("COBADEFFXXX", detail.bic)
        assertEquals("Musterbank", detail.bankName)
        assertEquals(42L, detail.defaultPaymentTerm?.id)
        assertEquals("AN-", detail.invoicePrefix)
        assertEquals(mapOf("de" to "Fusszeile"), detail.invoiceNotesByLanguageCode)
        assertEquals("+49 30 123", detail.phone)
        assertEquals("billing@muster.example", detail.email)
        assertEquals("https://www.muster.example", detail.website)
        assertEquals("Musterstadt", detail.registeredSeat)
        assertEquals("M. Muster", detail.managingDirector)
        assertEquals("Berlin", detail.courtOfJurisdiction)
        assertEquals("AG Charlottenburg", detail.registerCourt)
        assertEquals("HRB 12345", detail.commercialRegisterNumber)
        assertEquals("123456789", detail.sellerIdentifierValue)
        assertEquals("0088", detail.sellerIdentifierSchemeId)
        assertEquals("0021", detail.legalRegistrationSchemeId)
        assertEquals(true, detail.isDefault)
        assertEquals(LegalForm.entries.first(), detail.legalForm)
        assertEquals(true, detail.craftsChamberRegistered)
        assertEquals("V-001", detail.sellerNumber)
        assertEquals("AB12", detail.shortHash)
        assertEquals(mapOf("pair" to PartyPairState("addr", "EM")), detail.partyPairs)
        assertEquals(listOf(SellerVatRegistrationRow("LU", "LU12345678")), detail.vatRegistrations)
    }

    @Test
    fun `findForEdit maps a bare profile to absent values and empty extension state`() {
        whenever(service.findByIdForEdit(1L)).thenReturn(lean())

        val detail = queries().findForEdit(1L)!!

        assertNull(detail.billingAddress)
        assertNull(detail.taxNumber)
        assertNull(detail.vatId)
        assertNull(detail.iban)
        assertNull(detail.bic)
        assertNull(detail.defaultPaymentTerm)
        assertNull(detail.email)
        assertNull(detail.sellerIdentifierValue)
        assertNull(detail.sellerIdentifierSchemeId)
        assertEquals(emptyMap<String, String>(), detail.invoiceNotesByLanguageCode)
        assertEquals(emptyMap<String, PartyPairState>(), detail.partyPairs)
        assertEquals(emptyList<SellerVatRegistrationRow>(), detail.vatRegistrations)
    }

    @Test
    fun `delete use case forwards to the service`() {
        DeleteSenderProfile(service).deleteSenderProfile(7L)

        verify(service).delete(7L)
    }
}
