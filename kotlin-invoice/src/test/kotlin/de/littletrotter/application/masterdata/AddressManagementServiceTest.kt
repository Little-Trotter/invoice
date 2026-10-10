package de.littletrotter.application.masterdata

import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.repository.AddressRepository
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.SenderProfileRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * `AddressManagementService` against fake repositories (no CDI, no DB). Focus is on the
 * business-relevant branches: the three-tier default-address fallback, historizing an address
 * already attached to an invoice, and the exclusivity of the default seller.
 */
class AddressManagementServiceTest {

    private val addresses = FakeAddressRepository()
    private val senders = FakeSenderProfileRepository()
    private val invoiceRepository = mock<InvoiceRepository>()
    private val service = AddressManagementService(addresses, senders, invoiceRepository)

    private fun recipient(id: Long = 1L) = Recipient().apply { this.id = id; name = "Müller GmbH" }
    private fun sender(id: Long = 1L) = SenderProfile().apply { this.id = id; name = "DevEdge" }

    // ── getBillingAddressForCustomer: three-tier fallback ──────────────────────

    @Test
    fun `Rechnungsadresse bevorzugt die als Standard markierte Adresse`() {
        val r = recipient()
        val nonDefault = Address.forRecipient(r, Address.AddressType.BILLING, "Weg 1", "12345", "Berlin")
        val default = Address.forRecipient(r, Address.AddressType.BILLING, "Weg 2", "12345", "Berlin", isDefault = true)
        addresses.billing[r] = listOf(nonDefault, default)

        val result = service.getBillingAddressForCustomer(r)

        assertTrue(result.isPresent)
        assertEquals("Weg 2", result.get().street)
    }

    @Test
    fun `Rechnungsadresse faellt ohne Standardmarkierung auf die erste vorhandene Adresse zurueck`() {
        val r = recipient()
        val onlyOne = Address.forRecipient(r, Address.AddressType.BILLING, "Weg 3", "12345", "Berlin")
        addresses.billing[r] = listOf(onlyOne)

        val result = service.getBillingAddressForCustomer(r)

        assertTrue(result.isPresent)
        assertEquals("Weg 3", result.get().street)
    }

    @Test
    fun `Rechnungsadresse faellt ohne jede Billing-Adresse auf die generelle Standardadresse zurueck`() {
        val r = recipient()
        addresses.billing[r] = emptyList()
        val fallback = Address.forRecipient(r, Address.AddressType.DELIVERY, "Weg 4", "12345", "Berlin", isDefault = true)
        addresses.defaultByRecipient[r] = fallback

        val result = service.getBillingAddressForCustomer(r)

        assertTrue(result.isPresent)
        assertEquals("Weg 4", result.get().street)
    }

    @Test
    fun `Rechnungsadresse bleibt leer wenn der Kunde gar keine Adresse hat`() {
        val r = recipient()
        addresses.billing[r] = emptyList()

        val result = service.getBillingAddressForCustomer(r)

        assertFalse(result.isPresent)
    }

    // ── getDeliveryAddressForCustomer: its own fallback, then falls back to billing ──

    @Test
    fun `Lieferadresse bevorzugt die als Standard markierte Lieferadresse`() {
        val r = recipient()
        val default = Address.forRecipient(r, Address.AddressType.DELIVERY, "Lager 1", "12345", "Berlin", isDefault = true)
        addresses.delivery[r] = listOf(default)

        val result = service.getDeliveryAddressForCustomer(r)

        assertTrue(result.isPresent)
        assertEquals("Lager 1", result.get().street)
    }

    @Test
    fun `Lieferadresse faellt ohne Standardmarkierung auf die erste vorhandene Lieferadresse zurueck`() {
        val r = recipient()
        val nonDefault = Address.forRecipient(r, Address.AddressType.DELIVERY, "Lager 2", "12345", "Berlin")
        addresses.delivery[r] = listOf(nonDefault)

        val result = service.getDeliveryAddressForCustomer(r)

        assertTrue(result.isPresent)
        assertEquals("Lager 2", result.get().street)
    }

    @Test
    fun `Lieferadresse faellt ohne eigene Lieferadresse auf die Rechnungsadresse zurueck`() {
        val r = recipient()
        addresses.delivery[r] = emptyList()
        val billing = Address.forRecipient(r, Address.AddressType.BILLING, "Rechnungsweg 1", "12345", "Berlin", isDefault = true)
        addresses.billing[r] = listOf(billing)

        val result = service.getDeliveryAddressForCustomer(r)

        assertTrue(result.isPresent)
        assertEquals("Rechnungsweg 1", result.get().street)
    }

    @Test
    fun `Lieferadresse bleibt leer wenn weder Liefer- noch Rechnungsadresse existiert`() {
        val r = recipient()
        addresses.delivery[r] = emptyList()
        addresses.billing[r] = emptyList()

        val result = service.getDeliveryAddressForCustomer(r)

        assertFalse(result.isPresent)
    }

    // ── defaultSeller ─────────────────────────────────────────────────────────

    @Test
    fun `defaultSeller ist leer wenn kein Anbieter als Standard markiert ist`() {
        senders.default = null
        assertFalse(service.defaultSeller.isPresent)
    }

    @Test
    fun `defaultSeller liefert den als Standard markierten Anbieter`() {
        val s = sender()
        senders.default = s
        assertEquals(s, service.defaultSeller.get())
    }

    // ── saveAddress(Recipient, ...): existing == null / historize / update ──

    @Test
    fun `saveAddress fuer Kunde legt eine neue Adresse an wenn noch keine Standardadresse existiert`() {
        val r = recipient()
        addresses.defaultByTypeRecipient[r to Address.AddressType.BILLING] = null

        val saved = service.saveAddress(r, Address.AddressType.BILLING, "Neue Str 1", "10115", "Berlin", "DE")

        assertEquals("Neue Str 1", saved.street)
        assertTrue(saved.isDefault)
        assertTrue(addresses.cleared.contains(r to Address.AddressType.BILLING))
    }

    @Test
    fun `saveAddress fuer Kunde historisiert eine bereits an eine Rechnung gebundene Adresse statt sie zu ueberschreiben`() {
        val r = recipient()
        val existing = Address.forRecipient(r, Address.AddressType.BILLING, "Alte Str 1", "10115", "Berlin", isDefault = true)
        existing.id = 42L
        addresses.defaultByTypeRecipient[r to Address.AddressType.BILLING] = existing
        whenever(invoiceRepository.existsByBillingAddressId(42L)).thenReturn(true)
        whenever(invoiceRepository.existsByDeliveryAddressId(any())).thenReturn(false)

        val saved = service.saveAddress(r, Address.AddressType.BILLING, "Neue Str 2", "10115", "Berlin", "DE")

        // A new address was created; the old one stayed untouched as a historical record.
        assertEquals("Neue Str 2", saved.street)
        assertEquals("Alte Str 1", existing.street)
    }

    @Test
    fun `saveAddress fuer Kunde historisiert eine Adresse auch wenn nur die Lieferseite an eine Rechnung gebunden ist`() {
        // addressIsAssignedToInvoice ORs both checks — this pins the second operand
        // (existsByDeliveryAddressId) as independently sufficient, not just the billing side.
        val r = recipient()
        val existing = Address.forRecipient(r, Address.AddressType.BILLING, "Alte Str 4", "10115", "Berlin", isDefault = true)
        existing.id = 44L
        addresses.defaultByTypeRecipient[r to Address.AddressType.BILLING] = existing
        whenever(invoiceRepository.existsByBillingAddressId(44L)).thenReturn(false)
        whenever(invoiceRepository.existsByDeliveryAddressId(44L)).thenReturn(true)

        val saved = service.saveAddress(r, Address.AddressType.BILLING, "Neue Str 4", "10115", "Berlin", "DE")

        assertEquals("Neue Str 4", saved.street)
        assertEquals("Alte Str 4", existing.street, "die Lieferseiten-Bindung allein muss bereits historisieren")
    }

    @Test
    fun `saveAddress fuer Kunde aktualisiert eine unbenutzte Standardadresse an Ort und Stelle`() {
        val r = recipient()
        val existing = Address.forRecipient(r, Address.AddressType.BILLING, "Alte Str 3", "10115", "Berlin", isDefault = true)
        existing.id = 43L
        addresses.defaultByTypeRecipient[r to Address.AddressType.BILLING] = existing
        whenever(invoiceRepository.existsByBillingAddressId(43L)).thenReturn(false)
        whenever(invoiceRepository.existsByDeliveryAddressId(43L)).thenReturn(false)

        val saved = service.saveAddress(r, Address.AddressType.BILLING, "Neue Str 3", "99999", "Hamburg", "AT")

        assertEquals(existing, saved, "eine unbenutzte Adresse wird in-place aktualisiert, nicht neu angelegt")
        assertEquals("Neue Str 3", saved.street)
        assertEquals("99999", saved.postalCode.value)
        assertEquals("Hamburg", saved.city)
        assertEquals(Country.AT, saved.country)
    }

    @Test
    fun `saveAddress fuer Kunde faellt bei unbekanntem Laendercode auf DE zurueck`() {
        val r = recipient()
        addresses.defaultByTypeRecipient[r to Address.AddressType.BILLING] = null

        val saved = service.saveAddress(r, Address.AddressType.BILLING, "Str", "10115", "Berlin", "XX-UNBEKANNT")

        assertEquals(Country.DE, saved.country)
    }

    @Test
    fun `boundary - saveAddress fuer Kunde faellt beim Historisieren auf DE zurueck wenn der Laendercode unbekannt ist`() {
        val r = recipient()
        val existing = Address.forRecipient(r, Address.AddressType.BILLING, "Alte Str 5", "10115", "Berlin", isDefault = true)
        existing.id = 45L
        addresses.defaultByTypeRecipient[r to Address.AddressType.BILLING] = existing
        whenever(invoiceRepository.existsByBillingAddressId(45L)).thenReturn(true)
        whenever(invoiceRepository.existsByDeliveryAddressId(any())).thenReturn(false)

        val saved = service.saveAddress(r, Address.AddressType.BILLING, "Neue Str 5", "10115", "Berlin", "XX-UNBEKANNT")

        assertEquals(Country.DE, saved.country)
    }

    @Test
    fun `boundary - saveAddress fuer Kunde faellt beim In-place-Update auf DE zurueck wenn der Laendercode unbekannt ist`() {
        val r = recipient()
        val existing = Address.forRecipient(r, Address.AddressType.BILLING, "Alte Str 6", "10115", "Berlin", isDefault = true)
        existing.id = 46L
        addresses.defaultByTypeRecipient[r to Address.AddressType.BILLING] = existing
        whenever(invoiceRepository.existsByBillingAddressId(46L)).thenReturn(false)
        whenever(invoiceRepository.existsByDeliveryAddressId(46L)).thenReturn(false)

        val saved = service.saveAddress(r, Address.AddressType.BILLING, "Neue Str 6", "10115", "Berlin", "XX-UNBEKANNT")

        assertEquals(existing, saved)
        assertEquals(Country.DE, saved.country)
    }

    @Test
    fun `boundary - addressIsAssignedToInvoice ist false fuer eine noch nie gespeicherte Adresse ohne id`() {
        val r = recipient()
        val existing = Address.forRecipient(r, Address.AddressType.BILLING, "Unpersistiert", "10115", "Berlin", isDefault = true)
        // existing.id stays null: never persisted, so no invoice can reference it.
        addresses.defaultByTypeRecipient[r to Address.AddressType.BILLING] = existing

        val saved = service.saveAddress(r, Address.AddressType.BILLING, "Neue Str 7", "10115", "Berlin", "DE")

        assertEquals(existing, saved, "ohne id kann die Adresse an keiner Rechnung haengen - in-place update")
        org.mockito.kotlin.verifyNoInteractions(invoiceRepository)
    }

    // ── getBillingAddressForSender / getDeliveryAddressesForSender ──────────────

    @Test
    fun `Rechnungsadresse des Anbieters bevorzugt die markierte Standardadresse`() {
        val s = sender()
        val default = Address.forSender(s, Address.AddressType.BILLING, "Anbieterweg 1", "12345", "Berlin", isDefault = true)
        addresses.defaultBySender[s to Address.AddressType.BILLING] = default

        val result = service.getBillingAddressForSender(s)

        assertTrue(result.isPresent)
        assertEquals("Anbieterweg 1", result.get().street)
    }

    @Test
    fun `Rechnungsadresse des Anbieters faellt ohne Standardmarkierung auf die erste Adresse zurueck`() {
        val s = sender()
        addresses.defaultBySender[s to Address.AddressType.BILLING] = null
        val first = Address.forSender(s, Address.AddressType.BILLING, "Anbieterweg 2", "12345", "Berlin")
        addresses.bySenderAndType[s to Address.AddressType.BILLING] = listOf(first)

        val result = service.getBillingAddressForSender(s)

        assertTrue(result.isPresent)
        assertEquals("Anbieterweg 2", result.get().street)
    }

    @Test
    fun `Rechnungsadresse des Anbieters bleibt leer ohne jede passende Adresse`() {
        val s = sender()
        addresses.defaultBySender[s to Address.AddressType.BILLING] = null
        addresses.bySenderAndType[s to Address.AddressType.BILLING] = emptyList()
        addresses.defaultBySenderAny[s] = null

        val result = service.getBillingAddressForSender(s)

        assertFalse(result.isPresent)
    }

    @Test
    fun `Lieferadressen des Anbieters sind leer wenn keine hinterlegt sind`() {
        val s = sender()
        addresses.bySenderAndType[s to Address.AddressType.DELIVERY] = emptyList()

        assertTrue(service.getDeliveryAddressesForSender(s).isEmpty())
    }

    @Test
    fun `Lieferadressen des Anbieters koennen mehrere Eintraege umfassen`() {
        val s = sender()
        val a1 = Address.forSender(s, Address.AddressType.DELIVERY, "Lager A", "12345", "Berlin")
        val a2 = Address.forSender(s, Address.AddressType.DELIVERY, "Lager B", "12345", "Berlin")
        addresses.bySenderAndType[s to Address.AddressType.DELIVERY] = listOf(a1, a2)

        val result = service.getDeliveryAddressesForSender(s)

        assertEquals(listOf("Lager A", "Lager B"), result.map { it.street })
    }

    // ── saveAddress(SenderProfile, ...): the same three branches as for the customer ──

    @Test
    fun `saveAddress fuer Anbieter legt eine neue Adresse an wenn noch keine existiert`() {
        val s = sender()
        addresses.defaultBySender[s to Address.AddressType.BILLING] = null

        val saved = service.saveAddress(s, Address.AddressType.BILLING, "Neuer Sitz 1", "10115", "Berlin", "DE")

        assertEquals("Neuer Sitz 1", saved.street)
        assertTrue(saved.isDefault)
    }

    @Test
    fun `saveAddress fuer Anbieter historisiert eine bereits an eine Rechnung gebundene Adresse`() {
        val s = sender()
        val existing = Address.forSender(s, Address.AddressType.BILLING, "Alter Sitz", "10115", "Berlin", isDefault = true)
        existing.id = 77L
        addresses.defaultBySender[s to Address.AddressType.BILLING] = existing
        whenever(invoiceRepository.existsByBillingAddressId(77L)).thenReturn(true)
        whenever(invoiceRepository.existsByDeliveryAddressId(any())).thenReturn(false)

        val saved = service.saveAddress(s, Address.AddressType.BILLING, "Neuer Sitz 2", "10115", "Berlin", "DE")

        assertEquals("Neuer Sitz 2", saved.street)
        assertEquals("Alter Sitz", existing.street)
    }

    @Test
    fun `saveAddress fuer Anbieter aktualisiert eine unbenutzte Adresse in-place`() {
        val s = sender()
        val existing = Address.forSender(s, Address.AddressType.BILLING, "Alter Sitz 2", "10115", "Berlin", isDefault = true)
        existing.id = 78L
        addresses.defaultBySender[s to Address.AddressType.BILLING] = existing
        whenever(invoiceRepository.existsByBillingAddressId(78L)).thenReturn(false)
        whenever(invoiceRepository.existsByDeliveryAddressId(78L)).thenReturn(false)

        val saved = service.saveAddress(s, Address.AddressType.BILLING, "Neuer Sitz 3", "20095", "Hamburg", "CH")

        assertEquals(existing, saved)
        assertEquals(Country.CH, saved.country)
    }

    @Test
    fun `boundary - saveAddress fuer Anbieter faellt bei neuer Adresse auf DE zurueck wenn der Laendercode unbekannt ist`() {
        val s = sender()
        addresses.defaultBySender[s to Address.AddressType.BILLING] = null

        val saved = service.saveAddress(s, Address.AddressType.BILLING, "Neuer Sitz 4", "10115", "Berlin", "XX-UNBEKANNT")

        assertEquals(Country.DE, saved.country)
    }

    @Test
    fun `boundary - saveAddress fuer Anbieter faellt beim Historisieren auf DE zurueck wenn der Laendercode unbekannt ist`() {
        val s = sender()
        val existing = Address.forSender(s, Address.AddressType.BILLING, "Alter Sitz 3", "10115", "Berlin", isDefault = true)
        existing.id = 79L
        addresses.defaultBySender[s to Address.AddressType.BILLING] = existing
        whenever(invoiceRepository.existsByBillingAddressId(79L)).thenReturn(true)
        whenever(invoiceRepository.existsByDeliveryAddressId(any())).thenReturn(false)

        val saved = service.saveAddress(s, Address.AddressType.BILLING, "Neuer Sitz 5", "10115", "Berlin", "XX-UNBEKANNT")

        assertEquals(Country.DE, saved.country)
    }

    @Test
    fun `boundary - saveAddress fuer Anbieter faellt beim In-place-Update auf DE zurueck wenn der Laendercode unbekannt ist`() {
        val s = sender()
        val existing = Address.forSender(s, Address.AddressType.BILLING, "Alter Sitz 4", "10115", "Berlin", isDefault = true)
        existing.id = 80L
        addresses.defaultBySender[s to Address.AddressType.BILLING] = existing
        whenever(invoiceRepository.existsByBillingAddressId(80L)).thenReturn(false)
        whenever(invoiceRepository.existsByDeliveryAddressId(80L)).thenReturn(false)

        val saved = service.saveAddress(s, Address.AddressType.BILLING, "Neuer Sitz 6", "20095", "Hamburg", "XX-UNBEKANNT")

        assertEquals(existing, saved)
        assertEquals(Country.DE, saved.country)
    }

    // ── setDefaultSeller: exclusivity of the default seller ──────────────────

    @Test
    fun `setDefaultSeller entzieht allen bisherigen Standard-Anbietern die Markierung`() {
        val old1 = sender(1L).apply { markAsDefault() }
        val old2 = sender(2L).apply { markAsDefault() }
        val untouched = sender(3L)
        senders.all = listOf(old1, old2, untouched)
        val newDefault = sender(4L)

        service.setDefaultSeller(newDefault)

        assertFalse(old1.isDefault)
        assertFalse(old2.isDefault)
        assertFalse(untouched.isDefault, "war nie Standard, darf durch den Aufruf nicht veraendert werden")
        assertTrue(newDefault.isDefault)
    }

    @Test
    fun `setDefaultSeller ist ein No-Op fuer bisherige Standard-Anbieter wenn keiner markiert war`() {
        val notDefault = sender(1L)
        senders.all = listOf(notDefault)
        val newDefault = sender(2L)

        service.setDefaultSeller(newDefault)

        assertFalse(notDefault.isDefault)
        assertTrue(newDefault.isDefault)
    }

    // ── Fakes ─────────────────────────────────────────────────────────────────

    private class FakeAddressRepository : AddressRepository {
        val billing = mutableMapOf<Recipient, List<Address>>()
        val delivery = mutableMapOf<Recipient, List<Address>>()
        val defaultByRecipient = mutableMapOf<Recipient, Address?>()
        val defaultByTypeRecipient = mutableMapOf<Pair<Recipient, Address.AddressType>, Address?>()
        val bySenderAndType = mutableMapOf<Pair<SenderProfile, Address.AddressType>, List<Address>>()
        val defaultBySender = mutableMapOf<Pair<SenderProfile, Address.AddressType>, Address?>()
        val defaultBySenderAny = mutableMapOf<SenderProfile, Address?>()
        val cleared = mutableSetOf<Pair<Any, Address.AddressType>>()

        override fun findByRecipientAndType(recipient: Recipient, type: Address.AddressType, pageIndex: Int, pageSize: Int): List<Address> =
            when (type) {
                Address.AddressType.BILLING -> billing[recipient].orEmpty()
                Address.AddressType.DELIVERY -> delivery[recipient].orEmpty()
            }

        override fun findDefaultByRecipient(recipient: Recipient): Address? = defaultByRecipient[recipient]

        override fun findDefaultByRecipientAndType(recipient: Recipient, type: Address.AddressType): Address? =
            defaultByTypeRecipient[recipient to type]

        override fun findBySenderAndType(sender: SenderProfile, type: Address.AddressType, pageIndex: Int, pageSize: Int): List<Address> =
            bySenderAndType[sender to type].orEmpty()

        override fun findDefaultBySender(sender: SenderProfile): Address? = defaultBySenderAny[sender]

        override fun findDefaultBySenderAndType(sender: SenderProfile, type: Address.AddressType): Address? =
            defaultBySender[sender to type]

        override fun clearDefaultByRecipientAndType(recipient: Recipient, type: Address.AddressType) {
            cleared.add(recipient to type)
        }

        override fun clearDefaultBySenderAndType(sender: SenderProfile, type: Address.AddressType) {
            cleared.add(sender to type)
        }

        override fun save(address: Address): Address = address

        override fun delete(id: Long) {}
    }

    private class FakeSenderProfileRepository : SenderProfileRepository {
        var default: SenderProfile? = null
        var all: List<SenderProfile> = emptyList()

        override fun findById(id: Long): SenderProfile? = all.firstOrNull { it.id == id }
        override fun findByIdForEdit(id: Long): SenderProfile? = findById(id)
        override fun findDefault(): SenderProfile? = default
        override fun findDefaults(): List<SenderProfile> = listOfNotNull(default)
        override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?): List<SenderProfile> = all
        override fun count(filterText: String?): Long = all.size.toLong()
        override fun save(profile: SenderProfile): SenderProfile = profile
        override fun forceVersionIncrement(profile: SenderProfile) {}
        override fun delete(id: Long) {}
    }
}
