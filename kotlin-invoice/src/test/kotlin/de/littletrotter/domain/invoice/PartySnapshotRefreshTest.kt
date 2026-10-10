package de.littletrotter.domain.invoice

import de.littletrotter.domain.masterdata.ElectronicAddress
import de.littletrotter.domain.masterdata.PartyIdentifier
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.VatId

/** P2 — Invoice.applyPartySnapshots/sellerSnapshot/buyerSnapshot and the refresh preview diff. */
class PartySnapshotRefreshTest {

    private fun sender(name: String = "Seller GmbH") = SenderProfile().apply {
        this.name = name
        vatId = VatId("DE123456789")
    }

    private fun recipient(name: String = "Buyer AG", routingId: String? = "991-01484-64") = Recipient().apply {
        customerNumber = CustomerNumber("KD-1")
        this.name = name
        this.routingId = routingId
        vatId = VatId("DE987654321")
    }

    // ── Invoice.applyPartySnapshots / sellerSnapshot / buyerSnapshot ────────────

    @Test
    fun `applyPartySnapshots sets both rows and back-references the invoice`() {
        val invoice = Invoice()
        val seller = sender().snapshotAsSeller()
        val buyer = recipient().snapshotAsBuyer()

        invoice.applyPartySnapshots(seller, buyer)

        assertEquals(2, invoice.partySnapshotList.size)
        assertEquals(seller, invoice.sellerSnapshot())
        assertEquals(buyer, invoice.buyerSnapshot())
        assertEquals(invoice, seller.invoice)
        assertEquals(invoice, buyer.invoice)
    }

    @Test
    fun `a second applyPartySnapshots overwrites the existing rows in place instead of adding new ones`() {
        val invoice = Invoice()
        invoice.applyPartySnapshots(sender("Alt GmbH").snapshotAsSeller(), recipient("Alt AG").snapshotAsBuyer("L-1"))
        val sellerRow = invoice.sellerSnapshot()
        val buyerRow = invoice.buyerSnapshot()

        invoice.applyPartySnapshots(sender("Neu GmbH").snapshotAsSeller(), recipient("Neu AG").snapshotAsBuyer("L-2"))

        assertEquals(2, invoice.partySnapshotList.size)
        assertSame(sellerRow, invoice.sellerSnapshot())
        assertSame(buyerRow, invoice.buyerSnapshot())
        assertEquals("Neu GmbH", sellerRow!!.name)
        assertEquals("Neu AG", buyerRow!!.name)
        assertEquals("L-2", buyerRow.buyerReference)
        assertEquals(invoice, sellerRow.invoice)
    }

    @Test
    fun `sellerSnapshot finds the seller row regardless of where it sits in the list`() {
        // applyPartySnapshots always inserts SELLER before BUYER, so a plain lookup test never
        // exercises the "keep scanning" branch of the firstOrNull predicate. Insert them in the
        // opposite order directly to prove the lookup doesn't depend on insertion order.
        val invoice = Invoice()
        val seller = sender().snapshotAsSeller()
        val buyer = recipient().snapshotAsBuyer()
        invoice.partySnapshotList.asMutableForTest().add(buyer)
        invoice.partySnapshotList.asMutableForTest().add(seller)

        assertEquals(seller, invoice.sellerSnapshot())
    }

    @Test
    fun `applyPartySnapshots called twice replaces the old rows instead of accumulating`() {
        val invoice = Invoice()
        invoice.applyPartySnapshots(sender().snapshotAsSeller(), recipient().snapshotAsBuyer())

        val newSeller = sender("Renamed GmbH").snapshotAsSeller()
        val newBuyer = recipient("Renamed AG").snapshotAsBuyer()
        invoice.applyPartySnapshots(newSeller, newBuyer)

        assertEquals(2, invoice.partySnapshotList.size, "must replace, not accumulate")
        assertEquals("Renamed GmbH", invoice.sellerSnapshot()?.name)
        assertEquals("Renamed AG", invoice.buyerSnapshot()?.name)
    }

    // ── previewPartySnapshotRefresh ──────────────────────────────────────────

    @Test
    fun `preview reports no changes when master data is unchanged`() {
        val s = sender()
        val r = recipient()
        val invoice = Invoice().apply {
            senderProfile = s; recipient = r; references.buyerReference = r.routingId
            applyPartySnapshots(s.snapshotAsSeller(), r.snapshotAsBuyer(references.buyerReference))
        }

        val preview = invoice.previewPartySnapshotRefresh()
        assertFalse(preview.hasChanges)
    }

    @Test
    fun `preview reports a name change on the seller side without touching the buyer side`() {
        val s = sender()
        val r = recipient()
        val invoice = Invoice().apply {
            senderProfile = s; recipient = r; references.buyerReference = r.routingId
            applyPartySnapshots(s.snapshotAsSeller(), r.snapshotAsBuyer(references.buyerReference))
        }

        s.name = "Renamed GmbH"

        val preview = invoice.previewPartySnapshotRefresh()
        assertTrue(preview.hasChanges)
        assertTrue(preview.sellerChanges.any { it.field == "Name" && it.oldValue == "Seller GmbH" && it.newValue == "Renamed GmbH" })
        assertTrue(preview.buyerChanges.isEmpty())
    }

    @Test
    fun `preview reports a routingId change as a buyerReference diff only when the invoice itself has no override`() {
        val s = sender()
        val r = recipient()
        val invoice = Invoice().apply {
            senderProfile = s; recipient = r; references.buyerReference = r.routingId
            applyPartySnapshots(s.snapshotAsSeller(), r.snapshotAsBuyer(references.buyerReference))
        }

        r.routingId = "999-99999-99"
        // The invoice's own buyerReference field still holds the OLD value — previewPartySnapshotRefresh
        // must use it (not the recipient's live routingId) as the new snapshot's BT-10, matching
        // updateHeader's independent buyerReference field (P2-T03).
        val preview = invoice.previewPartySnapshotRefresh()
        assertFalse(preview.buyerChanges.any { it.field.contains("Leitweg") })
    }

    @Test
    fun `preview reports identifier changes with scheme included`() {
        val s = sender().apply { sellerIdentifier = PartyIdentifier.of("123", "0088") }
        val r = recipient()
        val invoice = Invoice().apply {
            senderProfile = s; recipient = r; references.buyerReference = r.routingId
            applyPartySnapshots(s.snapshotAsSeller(), r.snapshotAsBuyer(references.buyerReference))
        }

        s.sellerIdentifier = PartyIdentifier.of("999", "0088")
        val preview = invoice.previewPartySnapshotRefresh()
        val identifierDiff = preview.sellerChanges.firstOrNull { it.field.contains("Kennung") }
        assertEquals("123 (0088)", identifierDiff?.oldValue)
        assertEquals("999 (0088)", identifierDiff?.newValue)
    }

    @Test
    fun `preview against an invoice with no existing snapshot yet treats every field as new`() {
        val s = sender()
        val r = recipient()
        val invoice = Invoice().apply { senderProfile = s; recipient = r; references.buyerReference = r.routingId }

        val preview = invoice.previewPartySnapshotRefresh()
        assertTrue(preview.hasChanges)
        assertNull(preview.sellerChanges.first().oldValue)
    }

    @Test
    fun `preview refuses to run without a senderProfile to snapshot`() {
        val invoice = Invoice().apply { recipient = recipient() }
        assertThrows(IllegalArgumentException::class.java) { invoice.previewPartySnapshotRefresh() }
    }

    @Test
    fun `preview refuses to run without a recipient to snapshot`() {
        val invoice = Invoice().apply { senderProfile = sender() }
        assertThrows(IllegalArgumentException::class.java) { invoice.previewPartySnapshotRefresh() }
    }

    @Test
    fun `preview finds the existing seller snapshot regardless of row order`() {
        val s = sender()
        val r = recipient()
        val invoice = Invoice().apply { senderProfile = s; recipient = r; references.buyerReference = r.routingId }
        // Inserted in BUYER-before-SELLER order directly, bypassing applyPartySnapshots's fixed
        // ordering, so the old-seller lookup can't just match on the first list element.
        invoice.partySnapshotList.asMutableForTest().add(r.snapshotAsBuyer(invoice.references.buyerReference))
        invoice.partySnapshotList.asMutableForTest().add(s.snapshotAsSeller())

        val preview = invoice.previewPartySnapshotRefresh()

        assertFalse(preview.hasChanges, "master data is unchanged, so the existing seller row (found out of order) must match: ${preview.sellerChanges}")
    }

    @Test
    fun `preview reports a legal registration change on the seller side`() {
        val s = sender().apply {
            commercialRegisterNumber = "HRB 1111"
            legalRegistrationSchemeId = "0002"
        }
        val r = recipient()
        val invoice = Invoice().apply {
            senderProfile = s; recipient = r; references.buyerReference = r.routingId
            applyPartySnapshots(s.snapshotAsSeller(), r.snapshotAsBuyer(references.buyerReference))
        }

        s.commercialRegisterNumber = "HRB 2222"

        val preview = invoice.previewPartySnapshotRefresh()
        val diff = preview.sellerChanges.firstOrNull { it.field.contains("Registerkennung") }
        assertEquals("HRB 1111 (0002)", diff?.oldValue)
        assertEquals("HRB 2222 (0002)", diff?.newValue)
    }

    @Test
    fun `preview reports an electronic address change on the buyer side`() {
        val s = sender()
        val r = recipient().apply { electronicAddress = ElectronicAddress.of("old@buyer.example", "EM") }
        val invoice = Invoice().apply {
            senderProfile = s; recipient = r; references.buyerReference = r.routingId
            applyPartySnapshots(s.snapshotAsSeller(), r.snapshotAsBuyer(references.buyerReference))
        }

        r.electronicAddress = ElectronicAddress.of("new@buyer.example", "EM")

        val preview = invoice.previewPartySnapshotRefresh()
        val diff = preview.buyerChanges.firstOrNull { it.field.contains("Elektronische Adresse") }
        assertEquals("old@buyer.example (EM)", diff?.oldValue)
        assertEquals("new@buyer.example (EM)", diff?.newValue)
    }
}
