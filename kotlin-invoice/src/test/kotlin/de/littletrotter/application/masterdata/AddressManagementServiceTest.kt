package de.littletrotter.application.masterdata

import de.littletrotter.domain.masterdata.Address
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.application.invoice.port.outbound.InvoiceLookupRepository
import de.littletrotter.domain.repository.SenderProfileRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * `AddressManagementService` against fake repositories (no CDI, no DB). Focus is on the
 * business-relevant branches: the three-tier default-address fallback, historizing an address
 * already attached to an invoice, and the exclusivity of the default seller.
 */
class AddressManagementServiceTest {

    private val recipients = mock<RecipientRepository>()
    private val senders = FakeSenderProfileRepository()
    private val invoiceLookup = mock<InvoiceLookupRepository>()
    private val service = AddressManagementService(recipients, senders, invoiceLookup)

    private fun recipient(id: Long = 1L) = aRecipient { this.id = id; name = "Müller GmbH" }
    private fun Recipient.give(vararg rows: Address) { edit { addresses = addressList + rows } }
    private fun SenderProfile.give(vararg rows: Address) { edit { addresses = addressList + rows } }

    private fun sender(id: Long = 1L) = aSenderProfile { this.id = id; name = "DevEdge" }

    // ── getBillingAddressForCustomer: three-tier fallback ──────────────────────

    @Test
    fun `Rechnungsadresse bevorzugt die als Standard markierte Adresse`() {
        val r = recipient()
        val nonDefault = Address.forRecipient(r, Address.AddressType.BILLING, "Weg 1", "12345", "Berlin")
        val default = Address.forRecipient(r, Address.AddressType.BILLING, "Weg 2", "12345", "Berlin", isDefault = true)
        r.give(nonDefault, default)

        val result = service.getBillingAddressForCustomer(r)

        assertTrue(result.isPresent)
        assertEquals("Weg 2", result.get().street)
    }

    @Test
    fun `Rechnungsadresse faellt ohne Standardmarkierung auf die erste vorhandene Adresse zurueck`() {
        val r = recipient()
        val onlyOne = Address.forRecipient(r, Address.AddressType.BILLING, "Weg 3", "12345", "Berlin")
        r.give(onlyOne)

        val result = service.getBillingAddressForCustomer(r)

        assertTrue(result.isPresent)
        assertEquals("Weg 3", result.get().street)
    }

    @Test
    fun `Rechnungsadresse faellt ohne jede Billing-Adresse auf die generelle Standardadresse zurueck`() {
        val r = recipient()
        val fallback = Address.forRecipient(r, Address.AddressType.DELIVERY, "Weg 4", "12345", "Berlin", isDefault = true)
        r.give(fallback)

        val result = service.getBillingAddressForCustomer(r)

        assertTrue(result.isPresent)
        assertEquals("Weg 4", result.get().street)
    }

    @Test
    fun `Rechnungsadresse bleibt leer wenn der Kunde gar keine Adresse hat`() {
        val r = recipient()

        val result = service.getBillingAddressForCustomer(r)

        assertFalse(result.isPresent)
    }

    // ── getDeliveryAddressForCustomer: its own fallback, then falls back to billing ──

    @Test
    fun `Lieferadresse bevorzugt die als Standard markierte Lieferadresse`() {
        val r = recipient()
        val default = Address.forRecipient(r, Address.AddressType.DELIVERY, "Lager 1", "12345", "Berlin", isDefault = true)
        r.give(default)

        val result = service.getDeliveryAddressForCustomer(r)

        assertTrue(result.isPresent)
        assertEquals("Lager 1", result.get().street)
    }

    @Test
    fun `Lieferadresse faellt ohne Standardmarkierung auf die erste vorhandene Lieferadresse zurueck`() {
        val r = recipient()
        val nonDefault = Address.forRecipient(r, Address.AddressType.DELIVERY, "Lager 2", "12345", "Berlin")
        r.give(nonDefault)

        val result = service.getDeliveryAddressForCustomer(r)

        assertTrue(result.isPresent)
        assertEquals("Lager 2", result.get().street)
    }

    @Test
    fun `Lieferadresse faellt ohne eigene Lieferadresse auf die Rechnungsadresse zurueck`() {
        val r = recipient()
        val billing = Address.forRecipient(r, Address.AddressType.BILLING, "Rechnungsweg 1", "12345", "Berlin", isDefault = true)
        r.give(billing)

        val result = service.getDeliveryAddressForCustomer(r)

        assertTrue(result.isPresent)
        assertEquals("Rechnungsweg 1", result.get().street)
    }

    @Test
    fun `Lieferadresse bleibt leer wenn weder Liefer- noch Rechnungsadresse existiert`() {
        val r = recipient()

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

        val saved = service.saveAddress(r, Address.AddressType.BILLING, "Neue Str 1", "10115", "Berlin", "DE")

        assertEquals("Neue Str 1", saved.street)
        assertTrue(saved.isDefault)
        verify(recipients, never()).saveAndFlush(any())
    }

    @Test
    fun `saveAddress fuer Kunde historisiert eine bereits an eine Rechnung gebundene Adresse statt sie zu ueberschreiben`() {
        val r = recipient()
        val existing = Address.forRecipient(r, Address.AddressType.BILLING, "Alte Str 1", "10115", "Berlin", isDefault = true)
        existing.edit { this.id = 42L }
        r.give(existing)
        whenever(invoiceLookup.existsByBillingAddressId(42L)).thenReturn(true)
        whenever(invoiceLookup.existsByDeliveryAddressId(any())).thenReturn(false)

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
        existing.edit { this.id = 44L }
        r.give(existing)
        whenever(invoiceLookup.existsByBillingAddressId(44L)).thenReturn(false)
        whenever(invoiceLookup.existsByDeliveryAddressId(44L)).thenReturn(true)

        val saved = service.saveAddress(r, Address.AddressType.BILLING, "Neue Str 4", "10115", "Berlin", "DE")

        assertEquals("Neue Str 4", saved.street)
        assertEquals("Alte Str 4", existing.street, "die Lieferseiten-Bindung allein muss bereits historisieren")
    }

    @Test
    fun `saveAddress fuer Kunde aktualisiert eine unbenutzte Standardadresse an Ort und Stelle`() {
        val r = recipient()
        val existing = Address.forRecipient(r, Address.AddressType.BILLING, "Alte Str 3", "10115", "Berlin", isDefault = true)
        existing.edit { this.id = 43L }
        r.give(existing)
        whenever(invoiceLookup.existsByBillingAddressId(43L)).thenReturn(false)
        whenever(invoiceLookup.existsByDeliveryAddressId(43L)).thenReturn(false)

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

        val saved = service.saveAddress(r, Address.AddressType.BILLING, "Str", "10115", "Berlin", "XX-UNBEKANNT")

        assertEquals(Country.DE, saved.country)
    }

    @Test
    fun `boundary - saveAddress fuer Kunde faellt beim Historisieren auf DE zurueck wenn der Laendercode unbekannt ist`() {
        val r = recipient()
        val existing = Address.forRecipient(r, Address.AddressType.BILLING, "Alte Str 5", "10115", "Berlin", isDefault = true)
        existing.edit { this.id = 45L }
        r.give(existing)
        whenever(invoiceLookup.existsByBillingAddressId(45L)).thenReturn(true)
        whenever(invoiceLookup.existsByDeliveryAddressId(any())).thenReturn(false)

        val saved = service.saveAddress(r, Address.AddressType.BILLING, "Neue Str 5", "10115", "Berlin", "XX-UNBEKANNT")

        assertEquals(Country.DE, saved.country)
    }

    @Test
    fun `boundary - saveAddress fuer Kunde faellt beim In-place-Update auf DE zurueck wenn der Laendercode unbekannt ist`() {
        val r = recipient()
        val existing = Address.forRecipient(r, Address.AddressType.BILLING, "Alte Str 6", "10115", "Berlin", isDefault = true)
        existing.edit { this.id = 46L }
        r.give(existing)
        whenever(invoiceLookup.existsByBillingAddressId(46L)).thenReturn(false)
        whenever(invoiceLookup.existsByDeliveryAddressId(46L)).thenReturn(false)

        val saved = service.saveAddress(r, Address.AddressType.BILLING, "Neue Str 6", "10115", "Berlin", "XX-UNBEKANNT")

        assertEquals(existing, saved)
        assertEquals(Country.DE, saved.country)
    }

    @Test
    fun `boundary - addressIsAssignedToInvoice ist false fuer eine noch nie gespeicherte Adresse ohne id`() {
        val r = recipient()
        val existing = Address.forRecipient(r, Address.AddressType.BILLING, "Unpersistiert", "10115", "Berlin", isDefault = true)
        // existing.id stays null: never persisted, so no invoice can reference it.
        r.give(existing)

        val saved = service.saveAddress(r, Address.AddressType.BILLING, "Neue Str 7", "10115", "Berlin", "DE")

        assertEquals(existing, saved, "ohne id kann die Adresse an keiner Rechnung haengen - in-place update")
        org.mockito.kotlin.verifyNoInteractions(invoiceLookup)
    }

    // ── getBillingAddressForSender / getDeliveryAddressesForSender ──────────────

    @Test
    fun `Rechnungsadresse des Anbieters bevorzugt die markierte Standardadresse`() {
        val s = sender()
        val default = Address.forSender(s, Address.AddressType.BILLING, "Anbieterweg 1", "12345", "Berlin", isDefault = true)
        s.give(default)

        val result = service.getBillingAddressForSender(s)

        assertTrue(result.isPresent)
        assertEquals("Anbieterweg 1", result.get().street)
    }

    @Test
    fun `Rechnungsadresse des Anbieters faellt ohne Standardmarkierung auf die erste Adresse zurueck`() {
        val s = sender()
        val first = Address.forSender(s, Address.AddressType.BILLING, "Anbieterweg 2", "12345", "Berlin")
        s.give(first)

        val result = service.getBillingAddressForSender(s)

        assertTrue(result.isPresent)
        assertEquals("Anbieterweg 2", result.get().street)
    }

    @Test
    fun `Rechnungsadresse des Anbieters bleibt leer ohne jede passende Adresse`() {
        val s = sender()

        val result = service.getBillingAddressForSender(s)

        assertFalse(result.isPresent)
    }

    @Test
    fun `Lieferadressen des Anbieters sind leer wenn keine hinterlegt sind`() {
        val s = sender()

        assertTrue(service.getDeliveryAddressesForSender(s).isEmpty())
    }

    @Test
    fun `Lieferadressen des Anbieters koennen mehrere Eintraege umfassen`() {
        val s = sender()
        val a1 = Address.forSender(s, Address.AddressType.DELIVERY, "Lager A", "12345", "Berlin")
        val a2 = Address.forSender(s, Address.AddressType.DELIVERY, "Lager B", "12345", "Berlin")
        s.give(a1, a2)

        val result = service.getDeliveryAddressesForSender(s)

        assertEquals(listOf("Lager A", "Lager B"), result.map { it.street })
    }

    // ── saveAddress(SenderProfile, ...): the same three branches as for the customer ──

    @Test
    fun `saveAddress fuer Anbieter legt eine neue Adresse an wenn noch keine existiert`() {
        val s = sender()

        val saved = service.saveAddress(s, Address.AddressType.BILLING, "Neuer Sitz 1", "10115", "Berlin", "DE")

        assertEquals("Neuer Sitz 1", saved.street)
        assertTrue(saved.isDefault)
    }

    @Test
    fun `saveAddress fuer Anbieter historisiert eine bereits an eine Rechnung gebundene Adresse`() {
        val s = sender()
        val existing = Address.forSender(s, Address.AddressType.BILLING, "Alter Sitz", "10115", "Berlin", isDefault = true)
        existing.edit { this.id = 77L }
        s.give(existing)
        whenever(invoiceLookup.existsByBillingAddressId(77L)).thenReturn(true)
        whenever(invoiceLookup.existsByDeliveryAddressId(any())).thenReturn(false)

        val saved = service.saveAddress(s, Address.AddressType.BILLING, "Neuer Sitz 2", "10115", "Berlin", "DE")

        assertEquals("Neuer Sitz 2", saved.street)
        assertEquals("Alter Sitz", existing.street)
    }

    @Test
    fun `saveAddress fuer Anbieter aktualisiert eine unbenutzte Adresse in-place`() {
        val s = sender()
        val existing = Address.forSender(s, Address.AddressType.BILLING, "Alter Sitz 2", "10115", "Berlin", isDefault = true)
        existing.edit { this.id = 78L }
        s.give(existing)
        whenever(invoiceLookup.existsByBillingAddressId(78L)).thenReturn(false)
        whenever(invoiceLookup.existsByDeliveryAddressId(78L)).thenReturn(false)

        val saved = service.saveAddress(s, Address.AddressType.BILLING, "Neuer Sitz 3", "20095", "Hamburg", "CH")

        assertEquals(existing, saved)
        assertEquals(Country.CH, saved.country)
    }

    @Test
    fun `boundary - saveAddress fuer Anbieter faellt bei neuer Adresse auf DE zurueck wenn der Laendercode unbekannt ist`() {
        val s = sender()

        val saved = service.saveAddress(s, Address.AddressType.BILLING, "Neuer Sitz 4", "10115", "Berlin", "XX-UNBEKANNT")

        assertEquals(Country.DE, saved.country)
    }

    @Test
    fun `boundary - saveAddress fuer Anbieter faellt beim Historisieren auf DE zurueck wenn der Laendercode unbekannt ist`() {
        val s = sender()
        val existing = Address.forSender(s, Address.AddressType.BILLING, "Alter Sitz 3", "10115", "Berlin", isDefault = true)
        existing.edit { this.id = 79L }
        s.give(existing)
        whenever(invoiceLookup.existsByBillingAddressId(79L)).thenReturn(true)
        whenever(invoiceLookup.existsByDeliveryAddressId(any())).thenReturn(false)

        val saved = service.saveAddress(s, Address.AddressType.BILLING, "Neuer Sitz 5", "10115", "Berlin", "XX-UNBEKANNT")

        assertEquals(Country.DE, saved.country)
    }

    @Test
    fun `boundary - saveAddress fuer Anbieter faellt beim In-place-Update auf DE zurueck wenn der Laendercode unbekannt ist`() {
        val s = sender()
        val existing = Address.forSender(s, Address.AddressType.BILLING, "Alter Sitz 4", "10115", "Berlin", isDefault = true)
        existing.edit { this.id = 80L }
        s.give(existing)
        whenever(invoiceLookup.existsByBillingAddressId(80L)).thenReturn(false)
        whenever(invoiceLookup.existsByDeliveryAddressId(80L)).thenReturn(false)

        val saved = service.saveAddress(s, Address.AddressType.BILLING, "Neuer Sitz 6", "20095", "Hamburg", "XX-UNBEKANNT")

        assertEquals(existing, saved)
        assertEquals(Country.DE, saved.country)
    }

    // ── setDefaultSeller: exclusivity of the default seller ──────────────────

    @Test
    fun `setDefaultSeller entzieht allen bisherigen Standard-Anbietern die Markierung`() {
        val old1 = sender(1L).apply { markAsDefault() }
        val untouched = sender(3L)
        senders.default = old1
        senders.all = listOf(old1, untouched)
        val newDefault = sender(4L)

        service.setDefaultSeller(newDefault)

        assertFalse(old1.isDefault)
        assertFalse(untouched.isDefault, "war nie Standard, darf durch den Aufruf nicht veraendert werden")
        assertTrue(newDefault.isDefault)
    }

    @Test
    fun `setDefaultSeller ist ein No-Op fuer bisherige Standard-Anbieter wenn keiner markiert war`() {
        val notDefault = sender(1L)
        senders.all = listOf(notDefault)
        senders.default = null
        val newDefault = sender(2L)

        service.setDefaultSeller(newDefault)

        assertFalse(notDefault.isDefault)
        assertTrue(newDefault.isDefault)
    }

    // ── Fakes ─────────────────────────────────────────────────────────────────

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
