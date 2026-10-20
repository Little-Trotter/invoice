package de.littletrotter.application.masterdata

import de.littletrotter.application.dto.PendingVatIdVerification
import de.littletrotter.application.dto.RecipientDetailDto
import de.littletrotter.application.dto.RecipientDraft
import de.littletrotter.application.reference.B2bProfileLifecycleService
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.edit
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.domain.value.VatId
import jakarta.persistence.OptimisticLockException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.OffsetDateTime

/** [ManageRecipient] on top of [CustomerManagementService]'s existing writes: draft-to-entity
 *  (new or loaded-by-id, with its own version check), the VAT-id verification reconciliation
 *  moved here from the dialog, and the SPI extension-pair write. A recipient is never saved
 *  without a complete billing address (street, postal code, city). */
class ManageRecipientTest {

    private val customerManagementService: CustomerManagementService = mock()
    private val recipientDetailMapper: RecipientDetailMapper = mock()
    private val partyFormExtensionRegistry: PartyFormExtensionRegistry = PartyFormExtensionRegistry(emptyList())
    private val b2bProfileService: B2bProfileLifecycleService = mock()
    private val time = de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z"))
    private val useCase = ManageRecipient(customerManagementService, recipientDetailMapper, partyFormExtensionRegistry, b2bProfileService, time)

    private fun draft(
        id: Long? = null,
        version: Long? = null,
        name: String = "Kunde AG",
        vatId: String? = null,
        vatIdValidatedByUser: Boolean = false,
        pendingVatIdVerification: PendingVatIdVerification? = null,
        billingStreet: String = "Weg 1",
        billingPostalCode: String = "10115",
        billingCity: String = "Berlin",
    ) = RecipientDraft(
        id = id,
        version = version,
        customerNumber = null,
        name = name,
        vatId = vatId,
        vatIdValidationNote = null,
        routingId = null,
        email = null,
        phone = null,
        contactPerson = null,
        iban = null,
        customerType = CustomerType.BUSINESS,
        invoiceFormat = InvoiceFormat.GERMAN, documentLanguage = "de",
        reverseChargePreference = de.littletrotter.domain.masterdata.ReverseChargePreference.ASK,
        buyerIdentifierValue = null,
        buyerIdentifierSchemeId = null,
        legalRegistrationValue = null,
        legalRegistrationSchemeId = null,
        b2bProfileId = null,
        partyPairs = emptyMap(),
        addresses = RecipientAddressInput(billingStreet = billingStreet, billingPostalCode = billingPostalCode, billingCity = billingCity),
        proposedCustomerNumber = "K-1",
        vatIdValidatedByUser = vatIdValidatedByUser,
        pendingVatIdVerification = pendingVatIdVerification,
    )

    private fun expectedDetail(id: Long) = RecipientDetailDto(
        id = id, version = 1L, customerNumber = null, shortHash = null, name = "Kunde AG", vatId = null,
        routingId = null, email = null, phone = null, contactPerson = null, iban = null,
        customerType = CustomerType.BUSINESS, invoiceFormat = InvoiceFormat.GERMAN, documentLanguage = "de",
        reverseChargePreference = de.littletrotter.domain.masterdata.ReverseChargePreference.ASK,
        buyerIdentifierValue = null, buyerIdentifierSchemeId = null, legalRegistrationValue = null,
        legalRegistrationSchemeId = null, b2bProfileId = null, partyPairs = emptyMap(),
        billingAddress = null, deliveryAddress = null, vatIdVerificationStatus = null, vatIdVerifiedAt = null,
        vatIdVerificationNote = null,
    )

    @Test
    fun `new - saveRecipientWithAddresses saves a new recipient and returns the mapped detail`() {
        val saved = aRecipient { id = 1L; name = "Kunde AG" }
        whenever(customerManagementService.saveWithAddresses(any(), any(), eq("K-1"))).thenReturn(saved)
        whenever(recipientDetailMapper.toDetailDto(saved)).thenReturn(expectedDetail(1L))

        val result = useCase.saveRecipientWithAddresses(draft())

        assertEquals(expectedDetail(1L), result)
        verify(customerManagementService, never()).findById(any())
    }

    @Test
    fun `update - saveRecipientWithAddresses loads the existing recipient and applies the draft onto it`() {
        val existing = aRecipient { id = 5L; version = 3L; name = "Alt AG" }
        whenever(customerManagementService.findById(5L)).thenReturn(existing)
        whenever(customerManagementService.saveWithAddresses(any(), any(), eq("K-1"))).thenReturn(existing)
        whenever(recipientDetailMapper.toDetailDto(existing)).thenReturn(expectedDetail(5L))

        val result = useCase.saveRecipientWithAddresses(draft(id = 5L, version = 3L, name = "Kunde AG"))

        assertEquals(expectedDetail(5L), result)
        assertEquals("Kunde AG", existing.name)
        verify(customerManagementService).saveWithAddresses(existing, draft().addresses.trimmed(), "K-1")
    }

    @Test
    fun `the draft's document language is written onto the recipient and an unknown code reads as German`() {
        val existing = aRecipient { id = 5L; version = 3L; name = "Alt AG" }
        whenever(customerManagementService.findById(5L)).thenReturn(existing)
        whenever(customerManagementService.saveWithAddresses(any(), any(), eq("K-1"))).thenReturn(existing)
        whenever(recipientDetailMapper.toDetailDto(existing)).thenReturn(expectedDetail(5L))

        useCase.saveRecipientWithAddresses(draft(id = 5L, version = 3L).copy(documentLanguage = "en"))
        assertEquals("en", existing.documentLanguage)

        useCase.saveRecipientWithAddresses(draft(id = 5L, version = 3L).copy(documentLanguage = "xx"))
        assertEquals("de", existing.documentLanguage)
    }

    @Test
    fun `version conflict - saveRecipientWithAddresses rejects a draft whose version no longer matches`() {
        val existing = aRecipient { id = 5L; version = 4L; name = "Alt AG" }
        whenever(customerManagementService.findById(5L)).thenReturn(existing)

        assertThrows(OptimisticLockException::class.java) {
            useCase.saveRecipientWithAddresses(draft(id = 5L, version = 3L))
        }
        verify(customerManagementService, never()).saveWithAddresses(any(), any(), any())
    }

    @Test
    fun `version conflict - saveRecipientWithAddresses rejects a draft for a recipient deleted meanwhile`() {
        whenever(customerManagementService.findById(5L)).thenReturn(null)

        assertThrows(OptimisticLockException::class.java) {
            useCase.saveRecipientWithAddresses(draft(id = 5L, version = 3L))
        }
    }

    @Test
    fun `reconcile - a fresh VIES result this session is applied for the matching vat id`() {
        val existing = aRecipient { id = 5L; version = 1L; vatId = VatId.of("DE136695976") }
        whenever(customerManagementService.findById(5L)).thenReturn(existing)
        whenever(customerManagementService.saveWithAddresses(any(), any(), any())).thenAnswer { it.arguments[0] }
        whenever(recipientDetailMapper.toDetailDto(any())).thenReturn(expectedDetail(5L))
        val verifiedAt = OffsetDateTime.now()

        useCase.saveRecipientWithAddresses(
            draft(
                id = 5L, version = 1L, vatId = "DE136695976",
                pendingVatIdVerification = PendingVatIdVerification("DE136695976", VatIdVerificationStatus.VERIFIED, verifiedAt),
            ),
        )

        assertEquals(VatIdVerificationStatus.VERIFIED, existing.vatIdVerificationStatus)
        assertEquals(verifiedAt, existing.vatIdVerifiedAt)
    }

    @Test
    fun `reconcile - a changed vat id resets a prior verification to UNVERIFIED`() {
        val existing = aRecipient {
            id = 5L; version = 1L; vatId = VatId.of("DE136695976")
            vatIdVerificationStatus = VatIdVerificationStatus.VERIFIED
            vatIdVerifiedAt = OffsetDateTime.now()
        }
        whenever(customerManagementService.findById(5L)).thenReturn(existing)
        whenever(customerManagementService.saveWithAddresses(any(), any(), any())).thenAnswer { it.arguments[0] }
        whenever(recipientDetailMapper.toDetailDto(any())).thenReturn(expectedDetail(5L))

        useCase.saveRecipientWithAddresses(draft(id = 5L, version = 1L, vatId = "ATU12345678"))

        assertEquals(VatIdVerificationStatus.UNVERIFIED, existing.vatIdVerificationStatus)
        assertEquals(null, existing.vatIdVerifiedAt)
    }

    @Test
    fun `reconcile - the manual checkbox alone verifies an unchanged vat id`() {
        val existing = aRecipient { id = 5L; version = 1L; vatId = VatId.of("DE136695976") }
        whenever(customerManagementService.findById(5L)).thenReturn(existing)
        whenever(customerManagementService.saveWithAddresses(any(), any(), any())).thenAnswer { it.arguments[0] }
        whenever(recipientDetailMapper.toDetailDto(any())).thenReturn(expectedDetail(5L))

        useCase.saveRecipientWithAddresses(draft(id = 5L, version = 1L, vatId = "DE136695976", vatIdValidatedByUser = true))

        assertEquals(VatIdVerificationStatus.VERIFIED, existing.vatIdVerificationStatus)
    }

    @Test
    fun `spi write - the buyer's extension pairs are written via PartyFormExtension#write`() {
        val extension = mock<de.littletrotter.spi.party.PartyFormExtension>()
        val spec = de.littletrotter.spi.party.PartyIdentifierSpec(id = "electronicAddress", i18nKeyPrefix = "x")
        whenever(extension.pairs(de.littletrotter.spi.party.PartyKind.BUYER)).thenReturn(listOf(spec))
        val registry = PartyFormExtensionRegistry(listOf(extension))
        val useCaseWithSpi = ManageRecipient(customerManagementService, recipientDetailMapper, registry, b2bProfileService, time)
        val saved = aRecipient { id = 1L }
        whenever(customerManagementService.saveWithAddresses(any(), any(), any())).thenReturn(saved)
        whenever(recipientDetailMapper.toDetailDto(saved)).thenReturn(expectedDetail(1L))
        val state = de.littletrotter.spi.party.PartyPairState("EM:buyer@example.org", "EM")

        useCaseWithSpi.saveRecipientWithAddresses(draft().copy(partyPairs = mapOf(spec.id to state)))

        verify(extension).write(any(), eq(de.littletrotter.spi.party.PartyKind.BUYER), eq(spec), eq(state))
    }

    @Test
    fun `boundary - saveRecipientWithAddresses rejects a blank billing street`() {
        assertThrows(IllegalArgumentException::class.java) {
            useCase.saveRecipientWithAddresses(draft(billingStreet = "  "))
        }
    }

    @Test
    fun `boundary - saveRecipientWithAddresses rejects a blank billing postal code`() {
        assertThrows(IllegalArgumentException::class.java) {
            useCase.saveRecipientWithAddresses(draft(billingPostalCode = "  "))
        }
    }

    @Test
    fun `boundary - saveRecipientWithAddresses rejects a blank billing city`() {
        assertThrows(IllegalArgumentException::class.java) {
            useCase.saveRecipientWithAddresses(draft(billingCity = "  "))
        }
    }

    @Test
    fun `happy path - deleteRecipient delegates to the service`() {
        useCase.deleteRecipient(42L)

        verify(customerManagementService).delete(42L)
    }

    @Test
    fun `WP2 - deleteRecipient forwards the caller's expectedVersion to the service`() {
        useCase.deleteRecipient(42L, expectedVersion = 3L)

        verify(customerManagementService).delete(42L, 3L)
    }

    @Test
    fun `b2bProfile - a draft with a b2bProfileId resolves it via B2bProfileService`() {
        val profile = B2bProfile().edit { id = 9L; name = "Profil" }
        whenever(b2bProfileService.findProfileById(9L)).thenReturn(profile)
        val saved = aRecipient { id = 1L }
        whenever(customerManagementService.saveWithAddresses(any(), any(), any())).thenAnswer { it.arguments[0] }
        whenever(recipientDetailMapper.toDetailDto(any())).thenReturn(expectedDetail(1L))

        useCase.saveRecipientWithAddresses(draft().copy(b2bProfileId = 9L))

        verify(b2bProfileService).findProfileById(9L)
    }
}
