package de.littletrotter.application.masterdata

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.edit
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.application.invoice.port.outbound.InvoiceLookupRepository
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.domain.value.Email
import jakarta.persistence.OptimisticLockException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import de.littletrotter.domain.value.CustomerNumber

/**
 * COV — [CustomerManagementService.save]'s new-vs-existing split (only new recipients get a
 * number assigned/checked-for-collision, same pattern as [SellerProfileService.save] and
 * [ArticleManagementService.save]) had zero direct coverage — only reachable incidentally through
 * UI-level tests ([de.littletrotter.ui.view.masterdata.MasterDataBorderCasesTest]).
 *
 * [CustomerManagementService.saveWithAddresses] folds
 * the header save and the address writes into one guarded call; see its own KDoc for why the
 * previous shape (a separate, standalone `saveAddress(recipientId, ...)` this class used to
 * expose) was removed rather than hardened in place.
 */
class CustomerManagementServiceTest {

    private val recipientRepository = mock<RecipientRepository>()
    private val addressManagementService = mock<AddressManagementService>()
    private val masterDataNumberService = mock<MasterDataNumberService>()
    private val invoiceLookup = mock<InvoiceLookupRepository>()
    private val service = CustomerManagementService(
        recipientRepository, addressManagementService,
        masterDataNumberService = masterDataNumberService, invoiceLookup = invoiceLookup,
    )

    private fun validRecipient(id: Long? = null, customerNumber: String = "K-1") = aRecipient {
        this.id = id
        this.customerNumber = CustomerNumber.ofNullable(customerNumber)
        name = "Kunde GmbH"
        email = Email("kunde@example.com")
        customerType = CustomerType.CONSUMER
    }

    @Test
    fun `save fragt fuer einen neuen Kunden eine Kundennummer an und prueft auf Kollision`() {
        val recipient = validRecipient()
        whenever(masterDataNumberService.saveCustomerWithNumber(org.mockito.kotlin.eq(recipient), org.mockito.kotlin.eq(null), any(), any<() -> Recipient>()))
            .thenAnswer { inv -> inv.getArgument<() -> Recipient>(3).invoke() }
        whenever(recipientRepository.saveAndFlush(recipient)).thenReturn(recipient)

        service.save(recipient)

        verify(masterDataNumberService).assignCustomerNumber(eq(recipient), eq(null))
        verify(masterDataNumberService).assertCustomerNumberAvailable(eq("K-1"), eq(null), any())
    }

    // assertCustomerNumberAvailable is a full mock in this test class, so its own
    // `lookup` callback argument (recipientRepository.findByCustomerNumberIgnoreCase) was never
    // actually invoked by any test above; only the method call itself was verified.
    @Test
    fun `happy path - save's duplicate-number lookup callback queries the repository case-insensitively`() {
        val recipient = validRecipient()
        whenever(
            masterDataNumberService.assertCustomerNumberAvailable(any(), anyOrNull(), any()),
        ).thenAnswer { inv -> inv.getArgument<(String) -> Recipient?>(2).invoke(inv.getArgument(0)) }
        whenever(masterDataNumberService.saveCustomerWithNumber(eq(recipient), eq(null), any(), any<() -> Recipient>()))
            .thenAnswer { inv -> inv.getArgument<() -> Recipient>(3).invoke() }
        whenever(recipientRepository.saveAndFlush(recipient)).thenReturn(recipient)

        service.save(recipient)

        verify(recipientRepository).findByCustomerNumberIgnoreCase("K-1")
    }

    // findById/count had no direct coverage anywhere in this file.
    @Test
    fun `happy path - findById delegates to the repository`() {
        val recipient = validRecipient(id = 9L)
        whenever(recipientRepository.findById(9L)).thenReturn(recipient)

        assertEquals(recipient, service.findById(9L))
    }

    @Test
    fun `happy path - count delegates to the repository`() {
        whenever(recipientRepository.count("Kunde")).thenReturn(4L)

        assertEquals(4L, service.count("Kunde"))
    }

    // delete() had no direct coverage in this file (only indirectly, through
    // de.littletrotter.ui.view.masterdata.MasterDataBorderCasesTest).
    @Test
    fun `happy path - delete removes a recipient with no invoices`() {
        whenever(invoiceLookup.findByRecipientId(9L, 0, 1)).thenReturn(emptyList())

        service.delete(9L)

        verify(recipientRepository).delete(9L)
    }

    @Test
    fun `boundary - delete rejects a recipient still referenced by an invoice`() {
        whenever(invoiceLookup.findByRecipientId(9L, 0, 1))
            .thenReturn(listOf(mock<de.littletrotter.domain.invoice.Invoice>()))

        assertThrows(InvalidParameterException::class.java) { service.delete(9L) }

        verify(recipientRepository, never()).delete(any())
    }

    // ── WP2 (0.9.10) — delete-vs-update: expectedVersion ───────────────────

    @Test
    fun `WP2 - delete rejects a stale expectedVersion without deleting`() {
        val managed = validRecipient(id = 9L).edit { version = 2L }
        whenever(recipientRepository.findById(9L)).thenReturn(managed)

        assertThrows(OptimisticLockException::class.java) { service.delete(9L, expectedVersion = 1L) }

        verify(recipientRepository, never()).delete(any())
    }

    @Test
    fun `WP2 - delete with a matching expectedVersion succeeds`() {
        val managed = validRecipient(id = 9L).edit { version = 2L }
        whenever(recipientRepository.findById(9L)).thenReturn(managed)
        whenever(invoiceLookup.findByRecipientId(9L, 0, 1)).thenReturn(emptyList())

        service.delete(9L, expectedVersion = 2L)

        verify(recipientRepository).delete(9L)
    }

    @Test
    fun `WP2 - delete rejects an already-deleted recipient when an expectedVersion is given`() {
        whenever(recipientRepository.findById(9L)).thenReturn(null)

        assertThrows(InvalidParameterException::class.java) { service.delete(9L, expectedVersion = 2L) }

        verify(recipientRepository, never()).delete(any())
    }

    @Test
    fun `WP2 - delete with a null expectedVersion skips the check (backward-compatible default)`() {
        whenever(invoiceLookup.findByRecipientId(9L, 0, 1)).thenReturn(emptyList())

        service.delete(9L)

        verify(recipientRepository, never()).findById(any())
        verify(recipientRepository).delete(9L)
    }

    @Test
    fun `update to a customer number held by another recipient is rejected before any flush`() {
        val recipient = validRecipient(id = 5L)
        whenever(recipientRepository.findByCustomerNumberIgnoreCase("K-1")).thenReturn(validRecipient(id = 6L))

        assertThrows(de.littletrotter.domain.exception.DuplicateCustomerNumberException::class.java) { service.save(recipient) }

        verify(recipientRepository, never()).save(any())
    }

    @Test
    fun `update keeping its own customer number is saved`() {
        val recipient = validRecipient(id = 5L)
        whenever(recipientRepository.findByCustomerNumberIgnoreCase("K-1")).thenReturn(recipient)
        whenever(recipientRepository.findById(5L)).thenReturn(recipient)
        whenever(recipientRepository.save(recipient)).thenReturn(recipient)

        assertEquals(recipient, service.save(recipient))
    }

    @Test
    fun `save fragt fuer einen bestehenden Kunden weder Nummer noch Kollisionspruefung an und aktualisiert direkt`() {
        val recipient = validRecipient(id = 5L)
        // The existing-recipient branch now reloads the currently persisted row for the
        // version compare; returning the SAME instance here means "nothing changed since load"
        // (managed === recipient, so their versions trivially agree).
        whenever(recipientRepository.findById(5L)).thenReturn(recipient)
        whenever(recipientRepository.save(recipient)).thenReturn(recipient)

        val saved = service.save(recipient)

        assertEquals(recipient, saved)
        verify(masterDataNumberService, never()).assignCustomerNumber(any<Recipient>(), anyOrNull())
        verify(masterDataNumberService, never()).assertCustomerNumberAvailable(any(), any(), any())
        verify(masterDataNumberService, never()).saveCustomerWithNumber<Recipient>(any(), any(), any(), any())
        // Every existing-recipient save force-increments, so a change under a child row
        // (an Address, saved separately by the caller) still bumps this aggregate's own version.
        verify(recipientRepository).forceVersionIncrement(recipient)
    }

    @Test
    fun `save wirft einen OptimisticLockException wenn die persistierte Version von der uebergebenen abweicht`() {
        val stale = validRecipient(id = 5L).edit { version = 1L }
        val persisted = validRecipient(id = 5L).edit { version = 2L }
        whenever(recipientRepository.findById(5L)).thenReturn(persisted)

        assertThrows(OptimisticLockException::class.java) { service.save(stale) }

        verify(recipientRepository, never()).save(any())
        verify(recipientRepository, never()).forceVersionIncrement(any())
    }

    @Test
    fun `save wirft wenn der bestehende Kunde inzwischen geloescht wurde`() {
        val recipient = validRecipient(id = 5L)
        whenever(recipientRepository.findById(5L)).thenReturn(null)

        assertThrows(InvalidParameterException::class.java) { service.save(recipient) }

        verify(recipientRepository, never()).save(any())
    }

    @Test
    fun `saveWithAddresses speichert Kopfdaten und Rechnungsadresse ueber dieselbe Instanz`() {
        val recipient = validRecipient(id = 7L)
        whenever(recipientRepository.findById(7L)).thenReturn(recipient)
        whenever(recipientRepository.save(recipient)).thenReturn(recipient)

        val saved = service.saveWithAddresses(
            recipient,
            RecipientAddressInput(
                billingStreet = "Weg 1",
                billingPostalCode = "12345",
                billingCity = "Berlin",
                billingCountry = "DE",
            ),
        )

        assertEquals(recipient, saved)
        verify(addressManagementService).saveAddress(recipient, Address.AddressType.BILLING, "Weg 1", "12345", "Berlin", "DE")
        verify(addressManagementService, never()).saveAddress(
            any<Recipient>(), eq(Address.AddressType.DELIVERY), any<String>(), any<String>(), any<String>(), any<String>(),
        )
        // The address write must happen on the SAME force-incremented instance save()
        // already guarded, never on a second, independently-fetched one.
        verify(recipientRepository).forceVersionIncrement(recipient)
    }

    @Test
    fun `saveWithAddresses speichert auch eine Lieferadresse wenn eine Strasse angegeben ist`() {
        val recipient = validRecipient(id = 7L)
        whenever(recipientRepository.findById(7L)).thenReturn(recipient)
        whenever(recipientRepository.save(recipient)).thenReturn(recipient)

        service.saveWithAddresses(
            recipient,
            RecipientAddressInput(
                billingStreet = "Weg 1",
                billingPostalCode = "12345",
                billingCity = "Berlin",
                billingCountry = "DE",
                deliveryStreet = "Lieferweg 2",
                deliveryPostalCode = "54321",
                deliveryCity = "Hamburg",
                deliveryCountry = "DE",
            ),
        )

        verify(addressManagementService).saveAddress(recipient, Address.AddressType.DELIVERY, "Lieferweg 2", "54321", "Hamburg", "DE")
    }

    // The every-other-test above always passes explicit deliveryPostalCode/City/Country;
    // their `?: ""`/`?: "DE"` fallbacks (once deliveryStreet alone triggers the DELIVERY branch)
    // had no coverage.
    @Test
    fun `boundary - saveWithAddresses defaults the missing delivery fields when only the street is given`() {
        val recipient = validRecipient(id = 8L)
        whenever(recipientRepository.findById(8L)).thenReturn(recipient)
        whenever(recipientRepository.save(recipient)).thenReturn(recipient)

        service.saveWithAddresses(
            recipient,
            RecipientAddressInput(
                billingStreet = "Weg 1",
                billingPostalCode = "12345",
                billingCity = "Berlin",
                billingCountry = "DE",
                deliveryStreet = "Lieferweg 2",
                deliveryPostalCode = null,
                deliveryCity = null,
                deliveryCountry = null,
            ),
        )

        verify(addressManagementService).saveAddress(recipient, Address.AddressType.DELIVERY, "Lieferweg 2", "", "", "DE")
    }

    @Test
    fun `saveWithAddresses wirft wenn der bestehende Kunde inzwischen geloescht wurde statt die Adresse zu speichern`() {
        val recipient = validRecipient(id = 999L)
        whenever(recipientRepository.findById(999L)).thenReturn(null)

        assertThrows(InvalidParameterException::class.java) {
            service.saveWithAddresses(
                recipient,
                RecipientAddressInput(
                    billingStreet = "Weg 1",
                    billingPostalCode = "12345",
                    billingCity = "Berlin",
                    billingCountry = "DE",
                ),
            )
        }

        verify(addressManagementService, never()).saveAddress(
            any<Recipient>(), any<Address.AddressType>(), any<String>(), any<String>(), any<String>(), any<String>(),
        )
    }

    // findAll/count had no direct unit coverage (only reached incidentally through UI
    // tests); the default sortField/sortDir parameters need an explicit call to be exercised.
    @Test
    fun `happy path - findAll delegates to the repository with the default sort`() {
        val expected = listOf(validRecipient(id = 1L))
        whenever(recipientRepository.findAll(0, 20, null, "name", SortDirection.ASC)).thenReturn(expected)

        val result = service.findAll(0, 20, null)

        assertEquals(expected, result)
    }

    @Test
    fun `boundary - findAll passes an explicit sort field and direction through unchanged`() {
        val expected = listOf(validRecipient(id = 2L))
        whenever(recipientRepository.findAll(1, 10, "Kunde", "customerNumber", SortDirection.DESC)).thenReturn(expected)

        val result = service.findAll(1, 10, "Kunde", "customerNumber", SortDirection.DESC)

        assertEquals(expected, result)
    }

    // saveWithAddresses' billingCountry default ("DE") was never left unassigned by a
    // caller in the existing tests, so its default-value evaluation line was never exercised.
    @Test
    fun `boundary - saveWithAddresses defaults billingCountry to DE when the caller omits it`() {
        val recipient = validRecipient(id = 7L)
        whenever(recipientRepository.findById(7L)).thenReturn(recipient)
        whenever(recipientRepository.save(recipient)).thenReturn(recipient)

        service.saveWithAddresses(
            recipient,
            RecipientAddressInput(
                billingStreet = "Weg 1",
                billingPostalCode = "12345",
                billingCity = "Berlin",
            ),
        )

        verify(addressManagementService).saveAddress(recipient, Address.AddressType.BILLING, "Weg 1", "12345", "Berlin", "DE")
    }
}
