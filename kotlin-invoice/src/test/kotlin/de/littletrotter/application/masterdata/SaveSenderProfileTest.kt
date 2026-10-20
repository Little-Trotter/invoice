package de.littletrotter.application.masterdata

import de.littletrotter.application.dto.SaveSenderProfileCommand
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.ElectronicAddress
import de.littletrotter.domain.masterdata.LegalForm
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.masterdata.copyEditableFieldsFrom
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
import de.littletrotter.spi.party.PartyFormExtension
import de.littletrotter.spi.party.PartyIdentifierSpec
import de.littletrotter.spi.party.PartyKind
import de.littletrotter.spi.party.PartyPairState
import de.littletrotter.spi.party.SellerVatRegistrationRow
import de.littletrotter.spi.party.SellerVatRegistrationsExtension
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** [SaveSenderProfile] applies the submitted form values onto the profile and saves it with its address. */
class SaveSenderProfileTest {

    private val service: SellerProfileService = mock()

    private class RecordingPairExtension : PartyFormExtension {
        override val id = "recording"
        val written = mutableListOf<PartyPairState>()
        override fun pairs(kind: PartyKind) = listOf(PartyIdentifierSpec(id = "pair", i18nKeyPrefix = "x"))
        override fun read(party: Any, kind: PartyKind, spec: PartyIdentifierSpec) = PartyPairState()
        override fun write(party: Any, kind: PartyKind, spec: PartyIdentifierSpec, state: PartyPairState) {
            written += state
            (party as SenderProfile).edit { electronicAddress = ElectronicAddress.of(state.value.ifBlank { "a@b.example" }, "EM") }
        }
    }

    private class RecordingVatExtension : SellerVatRegistrationsExtension {
        override val id = "recordingVat"
        override val supportedCountries = listOf("LU")
        var written: List<SellerVatRegistrationRow>? = null
        override fun read(senderProfile: Any) = emptyList<SellerVatRegistrationRow>()
        override fun write(senderProfile: Any, rows: List<SellerVatRegistrationRow>) {
            written = rows
        }
    }

    private val pairExtension = RecordingPairExtension()
    private val vatExtension = RecordingVatExtension()

    private fun useCase(withExtensions: Boolean = false) = SaveSenderProfile(
        service,
        PartyFormExtensionRegistry(if (withExtensions) listOf(pairExtension) else emptyList()),
        SellerVatRegistrationsExtensionRegistry(if (withExtensions) listOf(vatExtension) else emptyList()),
    )

    private fun command(
        id: Long? = null,
        invoicePrefix: String = "MU-",
        sellerIdentifierValue: String = "",
        sellerIdentifierSchemeId: String = "",
        isDefault: Boolean = false,
        partyPairs: Map<String, PartyPairState> = emptyMap(),
        vatRegistrations: List<SellerVatRegistrationRow> = emptyList(),
    ) = SaveSenderProfileCommand(
        id = id,
        name = "  Muster GmbH ",
        street = "Weg 1",
        postalCode = "10115",
        city = "Berlin",
        countryAlpha2 = "AT",
        taxNumber = "12/345/67890",
        vatId = "de 123456789",
        iban = " de89 3704 0044 0532 0130 00 ",
        bic = "COBADEFFXXX",
        bankName = " Musterbank ",
        defaultPaymentTermId = 42L,
        invoicePrefix = invoicePrefix,
        invoiceNotesByLanguage = mapOf("de" to "Text"),
        phone = " +49 30 123 ",
        email = " billing@muster.example ",
        website = " https://www.muster.example ",
        registeredSeat = " Berlin ",
        managingDirector = " M. Muster ",
        courtOfJurisdiction = " Berlin ",
        registerCourt = " AG Charlottenburg ",
        commercialRegisterNumber = " HRB 12345 ",
        sellerIdentifierValue = sellerIdentifierValue,
        sellerIdentifierSchemeId = sellerIdentifierSchemeId,
        legalRegistrationSchemeId = " 0021 ",
        isDefault = isDefault,
        legalForm = LegalForm.entries.first(),
        craftsChamberRegistered = true,
        partyPairs = partyPairs,
        vatRegistrations = vatRegistrations,
        proposedSellerNumber = "V-001",
    )

    private fun saved(id: Long? = 9L) = SenderProfile().also { it.edit { this.id = id } }

    private fun captureSaved(command: SaveSenderProfileCommand, withExtensions: Boolean = false): SenderProfile {
        whenever(service.save(any(), any(), any(), any())).thenReturn(saved())
        useCase(withExtensions).saveSenderProfile(command)
        val captor = argumentCaptor<SenderProfile>()
        verify(service).save(captor.capture(), any(), any(), any())
        return captor.firstValue
    }

    @Test
    fun `a new profile is created from the command, trimmed and blank-normalised`() {
        val profile = captureSaved(command(sellerIdentifierValue = " 123456789 ", sellerIdentifierSchemeId = " 0088 ", isDefault = true))

        assertEquals("Muster GmbH", profile.name)
        assertEquals("12/345/67890", profile.taxNumber?.value)
        assertEquals("DE123456789", profile.vatId?.value)
        assertEquals("DE89370400440532013000", profile.iban?.value)
        assertEquals("COBADEFFXXX", profile.bic?.value)
        assertEquals("Musterbank", profile.bankName)
        assertEquals("MU-", profile.invoicePrefix)
        assertEquals("+49 30 123", profile.phone)
        assertEquals("billing@muster.example", profile.email?.value)
        assertEquals("https://www.muster.example", profile.website)
        assertEquals("Berlin", profile.registeredSeat)
        assertEquals("M. Muster", profile.managingDirector)
        assertEquals("Berlin", profile.courtOfJurisdiction)
        assertEquals("AG Charlottenburg", profile.registerCourt)
        assertEquals("HRB 12345", profile.commercialRegisterNumber)
        assertEquals("123456789", profile.sellerIdentifier?.value)
        assertEquals("0088", profile.sellerIdentifier?.schemeId)
        assertEquals("0021", profile.legalRegistrationSchemeId)
        assertEquals(LegalForm.entries.first(), profile.legalForm)
        assertTrue(profile.craftsChamberRegistered)
        assertTrue(profile.isDefault)
        assertNull(profile.id)
    }

    @Test
    fun `the save receives the proposed number, the notes and the payment term id`() {
        whenever(service.save(any(), any(), any(), any())).thenReturn(saved())

        useCase().saveSenderProfile(command())

        val selections = argumentCaptor<SellerDefaultSelections>()
        verify(service).save(any(), eq("V-001"), eq(mapOf("de" to "Text")), selections.capture())
        assertEquals(42L, selections.firstValue.paymentTermId)
    }

    @Test
    fun `the billing address is saved after the profile with the returned id and the typed country`() {
        whenever(service.save(any(), any(), any(), any())).thenReturn(saved(9L))

        val id = useCase().saveSenderProfile(command())

        assertEquals(9L, id)
        verify(service).saveAddress(9L, Address.AddressType.BILLING, "Weg 1", "10115", "Berlin", "AT")
    }

    @Test
    fun `a blank prefix falls back to RE- and blank optional values become null`() {
        val blank = command(invoicePrefix = "   ").copy(
            bankName = " ", phone = "", email = "", website = "", registeredSeat = "", managingDirector = "",
            courtOfJurisdiction = "", registerCourt = "", commercialRegisterNumber = "", legalRegistrationSchemeId = "",
            iban = "", bic = "", legalForm = null, craftsChamberRegistered = false,
        )

        val profile = captureSaved(blank)

        assertEquals("RE-", profile.invoicePrefix)
        assertNull(profile.bankName)
        assertNull(profile.iban)
        assertNull(profile.bic)
        assertNull(profile.phone)
        assertNull(profile.email)
        assertNull(profile.website)
        assertNull(profile.registeredSeat)
        assertNull(profile.managingDirector)
        assertNull(profile.courtOfJurisdiction)
        assertNull(profile.registerCourt)
        assertNull(profile.commercialRegisterNumber)
        assertNull(profile.legalRegistrationSchemeId)
        assertNull(profile.legalForm)
        assertFalse(profile.craftsChamberRegistered)
        assertFalse(profile.isDefault)
    }

    @Test
    fun `the seller identifier stays unset when value and scheme are both blank`() {
        assertNull(captureSaved(command()).sellerIdentifier)
    }

    @Test
    fun `a seller identifier value without a scheme is rejected`() {
        assertThrows(InvalidParameterException::class.java) {
            useCase().saveSenderProfile(command(sellerIdentifierValue = "123456789"))
        }
    }

    @Test
    fun `a seller identifier scheme without a value is rejected`() {
        assertThrows(InvalidParameterException::class.java) {
            useCase().saveSenderProfile(command(sellerIdentifierSchemeId = "0088"))
        }
    }

    @Test
    fun `an existing profile is loaded and edited in place`() {
        val existing = aSenderProfile { id = 5L; name = "Alt" }
        whenever(service.findById(5L)).thenReturn(existing)
        whenever(service.save(any(), any(), any(), any())).thenReturn(existing)

        useCase().saveSenderProfile(command(id = 5L))

        val captor = argumentCaptor<SenderProfile>()
        verify(service).save(captor.capture(), any(), any(), any())
        assertEquals(existing, captor.firstValue)
        assertEquals("Muster GmbH", existing.name)
    }

    @Test
    fun `an existing profile that is gone is reported instead of silently recreated`() {
        whenever(service.findById(5L)).thenReturn(null)

        assertThrows(InvalidParameterException::class.java) { useCase().saveSenderProfile(command(id = 5L)) }
    }

    @Test
    fun `a saved profile without an id is an error`() {
        whenever(service.save(any(), any(), any(), any())).thenReturn(saved(null))

        assertThrows(IllegalStateException::class.java) { useCase().saveSenderProfile(command()) }
    }

    @Test
    fun `registered extensions receive the submitted pair and registration rows`() {
        val rows = listOf(SellerVatRegistrationRow("LU", "LU12345678"))
        val profile = captureSaved(
            command(partyPairs = mapOf("pair" to PartyPairState("seller@example.org", "EM")), vatRegistrations = rows),
            withExtensions = true,
        )

        assertEquals(listOf(PartyPairState("seller@example.org", "EM")), pairExtension.written)
        assertEquals("seller@example.org", profile.electronicAddress?.value)
        assertEquals(rows, vatExtension.written)
    }

    @Test
    fun `a pair missing from the command is written as not set`() {
        captureSaved(command(), withExtensions = true)

        assertEquals(listOf(PartyPairState()), pairExtension.written)
    }

    /**
     * Pins the mirror contract between this use case's field assignments and the entity's
     * `copyEditableFieldsFrom` (the save path for existing profiles): a field written here that the
     * copy drops is a silently discarded edit.
     */
    @Test
    fun `copyEditableFieldsFrom mirrors every field the command writes`() {
        val written = captureSaved(
            command(sellerIdentifierValue = "123456789", sellerIdentifierSchemeId = "0088", isDefault = true),
        )

        val copied = SenderProfile()
        copied.copyEditableFieldsFrom(written)

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
        assertEquals(written.legalForm, copied.legalForm)
        assertEquals(written.craftsChamberRegistered, copied.craftsChamberRegistered)
        assertEquals(written.isDefault, copied.isDefault)
    }
}
