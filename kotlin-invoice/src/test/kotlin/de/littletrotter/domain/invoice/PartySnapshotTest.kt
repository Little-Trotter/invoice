package de.littletrotter.domain.invoice

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.ElectronicAddress
import de.littletrotter.domain.masterdata.PartyIdentifier
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * P1-T06 — a party snapshot must survive a later change to the [SenderProfile]/[Recipient] row
 * it was taken from (RV-02, GoBD Checkpoint 4): [snapshotAsSeller]/[snapshotAsBuyer] freeze the
 * values at the moment they're called; nothing re-reads the live profile/recipient afterwards
 * for an already-snapshotted invoice.
 */
class PartySnapshotTest {

    private fun sender(): SenderProfile = SenderProfile().apply {
        name = "Seller GmbH"
        sellerIdentifier = PartyIdentifier.of("123456789", "0204")
        commercialRegisterNumber = "HRB 1234"
        legalRegistrationSchemeId = "0002"
        vatId = VatId("DE123456789")
        taxNumber = TaxNumber("12/345/67890")
        electronicAddress = ElectronicAddress.of("seller@example.de", "EM")
    }

    private fun recipient(): Recipient = Recipient().apply {
        customerNumber = CustomerNumber("KD-1")
        name = "Buyer AG"
        routingId = "991-01484-64"
        buyerIdentifier = PartyIdentifier.of("987654321", "0204")
        vatId = VatId("DE987654321")
        electronicAddress = ElectronicAddress.of("buyer@example.de", "EM")
    }

    @Test
    fun `snapshotAsSeller copies identifier, legal registration, vat id and electronic address`() {
        val snapshot = sender().snapshotAsSeller()

        assertEquals(PartyRole.SELLER, snapshot.partyRole)
        assertEquals("Seller GmbH", snapshot.name)
        assertEquals("123456789", snapshot.identifier?.value)
        assertEquals("0204", snapshot.identifier?.schemeId)
        assertEquals("HRB 1234", snapshot.legalRegistration?.value)
        assertEquals("0002", snapshot.legalRegistration?.schemeId)
        assertEquals("DE123456789", snapshot.vatId?.value)
        assertEquals("12/345/67890", snapshot.taxNumber?.value)
        assertEquals("seller@example.de", snapshot.electronicAddress?.value)
        assertNull(snapshot.buyerReference, "BT-10 is a buyer-side field only")
    }

    @Test
    fun `snapshotAsBuyer copies identifier, vat id, electronic address and defaults buyerReference to routingId`() {
        val snapshot = recipient().snapshotAsBuyer()

        assertEquals(PartyRole.BUYER, snapshot.partyRole)
        assertEquals("Buyer AG", snapshot.name)
        assertEquals("987654321", snapshot.identifier?.value)
        assertEquals("DE987654321", snapshot.vatId?.value)
        assertEquals("buyer@example.de", snapshot.electronicAddress?.value)
        assertEquals("991-01484-64", snapshot.buyerReference)
    }

    @Test
    fun `snapshotAsBuyer accepts an override for this invoice's buyer reference`() {
        val snapshot = recipient().snapshotAsBuyer(buyerReference = "992-00000-01")
        assertEquals("992-00000-01", snapshot.buyerReference)
    }

    @Test
    fun `a seller snapshot survives a later change to the referenced SenderProfile row`() {
        val profile = sender()
        val snapshot = profile.snapshotAsSeller()

        // Simulate exactly the scenario P1 exists to prevent: someone edits the shared
        // sender_profile row after an invoice referencing this snapshot was finalized.
        profile.name = "Renamed GmbH"
        profile.sellerIdentifier = PartyIdentifier.of("000000000", "0088")
        profile.vatId = VatId("DE000000000")

        assertEquals("Seller GmbH", snapshot.name, "snapshot name must not follow the live row")
        assertEquals("123456789", snapshot.identifier?.value, "snapshot identifier must not follow the live row")
        assertEquals("DE123456789", snapshot.vatId?.value, "snapshot VAT ID must not follow the live row")
    }

    @Test
    fun `a buyer snapshot survives a later change to the referenced Recipient row`() {
        val rec = recipient()
        val snapshot = rec.snapshotAsBuyer()

        rec.name = "Renamed AG"
        rec.buyerIdentifier = PartyIdentifier.of("111111111", "0088")
        rec.routingId = "999-99999-99"

        assertEquals("Buyer AG", snapshot.name, "snapshot name must not follow the live row")
        assertEquals("987654321", snapshot.identifier?.value, "snapshot identifier must not follow the live row")
        assertEquals("991-01484-64", snapshot.buyerReference, "snapshot buyerReference must not follow a later routingId change")
    }

    @Test
    fun `a snapshot without a name is rejected`() {
        val snapshot = InvoicePartySnapshot().apply { partyRole = PartyRole.SELLER }
        assertThrows(InvalidParameterException::class.java) { snapshot.validate() }
    }

    @Test
    fun `a buyerReference on a SELLER snapshot is rejected`() {
        val snapshot = InvoicePartySnapshot().apply {
            partyRole = PartyRole.SELLER
            name = "Seller GmbH"
            buyerReference = "991-01484-64"
        }
        assertThrows(InvalidParameterException::class.java) { snapshot.validate() }
    }

    @Test
    fun `a buyerReference on the BUYER snapshot it belongs to is accepted`() {
        val snapshot = InvoicePartySnapshot().apply {
            partyRole = PartyRole.BUYER
            name = "Buyer AG"
            buyerReference = "991-01484-64"
        }
        org.junit.jupiter.api.Assertions.assertDoesNotThrow { snapshot.validate() }
    }

    @Test
    fun `snapshotAsSeller without a commercial register scheme leaves legalRegistration unset`() {
        val profile = sender().apply { legalRegistrationSchemeId = null }
        val snapshot = profile.snapshotAsSeller()
        assertNull(snapshot.legalRegistration)
    }

    // the OUTER null check on the same line (commercialRegisterNumber itself absent, as
    // opposed to present-but-schemeless above).
    @Test
    fun `snapshotAsSeller without a commercial register number leaves legalRegistration unset`() {
        val profile = sender().apply { commercialRegisterNumber = null }
        val snapshot = profile.snapshotAsSeller()
        assertNull(snapshot.legalRegistration)
    }

    @Test
    fun `a CONSUMER recipient without a buyerIdentifier still produces a valid snapshot`() {
        val rec = Recipient().apply {
            customerNumber = CustomerNumber("KD-2")
            name = "Private Person"
            customerType = CustomerType.CONSUMER
        }
        val snapshot = rec.snapshotAsBuyer()
        assertEquals("Private Person", snapshot.name)
        assertNull(snapshot.identifier)
        org.junit.jupiter.api.Assertions.assertDoesNotThrow { snapshot.validate() }
    }

    // id/createdAt are hydrated by JPA on the persisted row only; a freshly built
    // snapshot (as every other test here constructs it) never touches those accessors.
    @Test
    fun `boundary - a freshly constructed snapshot has no id, invoice or createdAt until hydrated from the database`() {
        val snapshot = InvoicePartySnapshot()
        assertNull(snapshot.id)
        assertNull(snapshot.invoice)
        assertNull(snapshot.createdAt)

        val hydratedAt = OffsetDateTime.now()
        val invoice = Invoice()
        snapshot.id = 42L
        snapshot.invoice = invoice
        snapshot.createdAt = hydratedAt

        assertEquals(42L, snapshot.id)
        assertEquals(invoice, snapshot.invoice)
        assertEquals(hydratedAt, snapshot.createdAt)
    }
}
